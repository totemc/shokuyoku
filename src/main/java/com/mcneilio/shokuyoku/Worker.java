package com.mcneilio.shokuyoku;

import com.mcneilio.shokuyoku.driver.EventDriver;
import com.mcneilio.shokuyoku.driver.BasicEventDriver;
import com.mcneilio.shokuyoku.driver.S3StorageDriver;
import com.mcneilio.shokuyoku.driver.StorageDriver;
import com.mcneilio.shokuyoku.format.Firehose;
import com.mcneilio.shokuyoku.format.JSONColumnFormat;

import com.mcneilio.shokuyoku.util.HiveDescriptionProvider;
import com.mcneilio.shokuyoku.util.Statsd;
import com.mcneilio.shokuyoku.util.TypeDescriptionProvider;
import com.timgroup.statsd.StatsDClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.orc.TypeDescription;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;

public class Worker {

    public Worker() {

        verifyEnvironment();
        System.out.println("shokuyoku will start processing requests from topic: " + System.getenv("KAFKA_TOPIC"));
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("KAFKA_SERVERS"));
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, System.getenv("KAFKA_GROUP_ID"));
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");

        String kafkaTopic = System.getenv("WORKER_KAFKA_TOPIC")!=null ? System.getenv("WORKER_KAFKA_TOPIC") : System.getenv("KAFKA_TOPIC");

        this.descriptionProvider = new HiveDescriptionProvider();
        this.databaseName = System.getenv("HIVE_DATABASE");
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(Arrays.asList(kafkaTopic));
        this.littleEndian = System.getenv("ENDIAN") != null && System.getenv("ENDIAN").equals("little");

        statsd = Statsd.getInstance();
    }


    boolean running = true;
    long currentOffset = 0;

    protected void start() {
        StorageDriver storageDriver = null;
        if(System.getenv("S3_BUCKET")!=null) {
            storageDriver=new S3StorageDriver(System.getenv("S3_BUCKET"),System.getenv("S3_PREFIX") + "/" + System.getenv("HIVE_DATABASE") );
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("Shutdown signal received...");
                running = false;
            }
        });

        while (running) {
            ConsumerRecords<String,byte[]> records = consumer.poll(Duration.ofMillis(
                    Integer.parseInt(System.getenv("KAFKA_POLL_DURATION_MS"))));
            for (ConsumerRecord<String,byte[]> record : records) {
                if(this.iterationTime == 0 && !records.isEmpty()) {
                    this.iterationTime = System.currentTimeMillis();
                }
                Firehose f = new Firehose(record.value(), littleEndian);
                String eventName = f.getTopic();
                JSONObject msg = new JSONColumnFormat(new JSONObject(f.getMessage())).getFlattened();
                if (!msg.has("timestamp") || !msg.has("event")) {
                    continue;
                }
                String date = msg.getString("timestamp").split("T")[0];
                if (!drivers.containsKey(eventName+date)) {
                    System.out.println("Creating driver for event: " + eventName + "with date: " + date);

                    TypeDescription typeDescription = this.descriptionProvider.getInstance(this.databaseName, eventName);
                    if(typeDescription!=null)
                        drivers.put(eventName+date, new BasicEventDriver(eventName, date, typeDescription, storageDriver));
                    else {
                        continue;
                    }
                }
                drivers.get(eventName+date).addMessage(msg);
                currentOffset = record.offset();
            }
            if((System.currentTimeMillis() - iterationTime) > (Integer.parseInt(System.getenv("FLUSH_MINUTES"))*1000*60)) {
                flushDrivers();

                this.iterationTime = System.currentTimeMillis();
            }
            statsd.histogram("kafka.poll.size", records.count(), new String[]{"env:"+System.getenv("STATSD_ENV")});
        }

        // Flush before exiting
        flushDrivers();
        System.out.println("Events and kafka offset flushed. Exiting....");
    }

    private void flushDrivers() {
        drivers.forEach((s, eventDriver) -> {
            System.out.println("Flushing Event Driver for: "+s);
            eventDriver.flush(true);
        });
        drivers.clear();
        if (currentOffset != 0) {
            System.out.println("Committing offset: " + currentOffset + " at: " + Instant.now().toString()  );
            currentOffset = 0;
            consumer.commitSync();
        }
    }

    private void verifyEnvironment() {
        boolean missingEnv = false;
        if(System.getenv("KAFKA_SERVERS") == null) {
            System.out.println("KAFKA_SERVERS environment variable should contain a comma-separated list of kafka servers. e.g. localhost:9092,localhost:9093");
            missingEnv = true;
        }
        if(System.getenv("KAFKA_GROUP_ID") == null) {
            System.out.println("KAFKA_GROUP_ID environment variable should contain the name of the Kafka group. e.g. shokuyoku");
            missingEnv = true;
        }
        if(System.getenv("KAFKA_TOPIC") == null && System.getenv("WORKER_KAFKA_TOPIC") == null) {
            System.out.println("KAFKA_TOPIC environment variable should contain the topic to subscribe to. e.g. events");
            missingEnv = true;
        }
        if(System.getenv("KAFKA_POLL_DURATION_MS") == null) {
            System.out.println("KAFKA_POLL_DURATION_MS environment variable should contain the duration for the Kafka Consumer `poll` method in milliseconds. e.g. 500");
            missingEnv = true;
        }
        if(System.getenv("FLUSH_MINUTES") == null) {
            System.out.println("FLUSH_MINUTES environment variable should contain the interval between flushes in minutes. e.g. 15");
            missingEnv = true;
        }
        if(System.getenv("AWS_DEFAULT_REGION") == null) {
            System.out.println("AWS_DEFAULT_REGION environment variable should be set https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-envvars.html");
            missingEnv = true;
        }
        if(System.getenv("S3_BUCKET") == null) {
            System.out.println("S3_BUCKET environment variable should contain the bucket to write events to. e.g. my-event-bucket");
            missingEnv = true;
        }
        if(System.getenv("S3_PREFIX") == null) {
            System.out.println("S3_PREFIX environment variable should contain the folder to prefix all events. e.g. data");
            missingEnv = true;
        }
        if(System.getenv("HIVE_DATABASE") == null) {
            System.out.println("HIVE_DATABASE environment variable should contain the name of the hive database for all events. e.g. events");
            missingEnv = true;
        }
        if(System.getenv("ORC_BATCH_SIZE") == null) {
            System.out.println("ORC_BATCH_SIZE environment variable should contain the number of records per orc batch. e.g. 1024");
            missingEnv = true;
        }
        if(missingEnv) {
            System.out.println("Missing required environment variable(s); exiting.");
            System.exit(1);
        }
    }

    private final boolean littleEndian;
    String databaseName;
    KafkaConsumer<String,byte[]> consumer;
    HashMap<String, EventDriver> drivers = new HashMap<>();
    long iterationTime = 0;
    StatsDClient statsd;
    private final HiveDescriptionProvider descriptionProvider;
}
