/*
 *  Copyright © 2018 Xu Shiyan (xu.shiyan.raymond@gmail.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.github.xushiyan.kafka.connect.datagen.performance;

import com.github.xushiyan.kafka.connect.datagen.utils.Version;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class DatagenTask extends SourceTask {
    private static final String[] ips = new String[]{
            "27.128.178",
            "1.8.110",
            "1.15.255",
            "14.102.158",
            "14.104.149",
            "220.243.255",
            "1.69.127",
            "1.180.240",
            "36.24.191",
            "14.18.159",
            "39.169.231"};

    private Random random = new Random();
    private DatagenConnectorConfig config;
    private Gson gson;
    private JsonObject messageTemplate;

    // generate data from file
    private BufferedReader fileReader;

    public String version() {
        return Version.get();
    }

    public void start(Map<String, String> props) {
        this.config = new DatagenConnectorConfig(DatagenConnectorConfig.definition(), props);
        this.gson = new GsonBuilder().create();
        this.messageTemplate = this.gson.fromJson(this.config.messageTemplate, JsonObject.class);
        List<String> randomFieldsValues = config.randomFields;
        if (randomFieldsValues.size() == 1 && randomFieldsValues.get(0).startsWith("fromFile")) {
            String[] splits = randomFieldsValues.get(0).split(":");
            String fileName = splits[1];
            try {
                this.fileReader = new BufferedReader(new FileReader(fileName));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private final Map<String, ?> sourcePartition = new HashMap<>();
    private final Map<String, ?> sourceOffset = new HashMap<>();

    public List<SourceRecord> poll() throws InterruptedException {
        DatagenConnectorConfig config = this.config;
        Gson gson = this.gson;
        JsonObject msgTemplate = this.messageTemplate;

        Thread.sleep(config.pollInterval);

        List<SourceRecord> records = new ArrayList<>(config.pollSize);
        Random randomizer = new Random();

        // generate data randomly from template
        if (fileReader == null) {
            List<String> randomFieldsValues = config.randomFields;
            Map<String, String[]> randomFieldsValueMap = new HashMap<>();
            for (String kv : randomFieldsValues) {
                String[] fieldAndValues = kv.split(":");
                String fieldName = fieldAndValues[0];
                String[] values = fieldAndValues[1].split(Pattern.quote("|"));
                randomFieldsValueMap.put(fieldName, values);
            }

            for (int i = 0; i < config.pollSize; i++) {
                JsonObject msg = msgTemplate.deepCopy();

                for (Map.Entry<String, String[]> entry : randomFieldsValueMap.entrySet()) {
                    String[] values = entry.getValue();
                    if (entry.getValue().length == 1 && entry.getValue()[0].equals("random_ip")) {
                        msg.addProperty(entry.getKey(), randomIP());
                    } else if (entry.getValue().length == 1 && entry.getValue()[0].equals("uuid")) {
                        msg.addProperty(entry.getKey(), UUID.randomUUID().toString());
                    } else if (entry.getValue()[0].startsWith("random_int")) {
                        int lowerBound = Integer.parseInt(entry.getValue()[1]);
                        int upperBound = Integer.parseInt(entry.getValue()[2]);
                        msg.addProperty(entry.getKey(), randomInt(lowerBound, upperBound));
                    } else {
                        msg.addProperty(entry.getKey(), values[randomizer.nextInt(values.length)]);
                    }
                }

                Instant now = Instant.now();
                long nanos = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

                msg.addProperty(config.eventTimestampField, nanos);

                records.add(new SourceRecord(sourcePartition, sourceOffset, config.topicName, Schema.STRING_SCHEMA, gson.toJson(msg)));
            }
        } else {
            // generate data from file
            for (int i = 0; i < config.pollSize; i++) {
                JsonObject msg = msgTemplate.deepCopy();
                Object[] fields =  msg.keySet().toArray();
                String line = null;
                try {
                    line = fileReader.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
                if (line == null) {
                    return null;
                }
                String[] splits = line.split(",");
                for (int j=0; j< fields.length; ++j) {
                    msg.addProperty(fields[j].toString(), splits[j]);
                }

                if (config.eventTimestampField != null && config.eventTimestampField.length() > 0) {
                    Instant now = Instant.now();
                    long nanos = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();
                    msg.addProperty(config.eventTimestampField, nanos);
                }

                records.add(new SourceRecord(sourcePartition, sourceOffset, config.topicName, Schema.STRING_SCHEMA, gson.toJson(msg)));
            }
        }

        return records;
    }

    public void stop() {

    }

    private String randomIP() {
        return ips[random.nextInt(ips.length)] + "." + random.nextInt(256);
    }

    private int randomInt(int lowerBound, int upperBound) {
        return random.nextInt(upperBound - lowerBound) + lowerBound;
    }
}
