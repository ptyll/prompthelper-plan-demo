package dev.gearreserve.messaging;

import dev.gearreserve.infrastructure.Database;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in standalone process, never started by the HTTP API. */
public final class KafkaOutboxPublisher {
    private KafkaOutboxPublisher() { }
    public static Properties producerProperties(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "15000");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        return props;
    }
    public static void main(String[] args) throws Exception {
        Database db = new Database(Path.of(env("GEARRESERVE_DB", "data/gearreserve.db")));
        db.initialize();
        String topic = env("GEARRESERVE_KAFKA_TOPIC", ReservationApproved.DEFAULT_TOPIC);
        int batch = Integer.parseInt(env("GEARRESERVE_PUBLISH_BATCH", "100"));
        AtomicBoolean running = new AtomicBoolean(true);
        Thread main = Thread.currentThread();
        Thread hook = new Thread(() -> {
            running.set(false);
            main.interrupt();
            try { main.join(7000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "outbox-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try (KafkaProducer<String,String> producer = new KafkaProducer<>(producerProperties(
                env("GEARRESERVE_KAFKA_BOOTSTRAP", "127.0.0.1:19092")))) {
            OutboxPublisher publisher = new OutboxPublisher(OutboxPublisher.databaseStore(db),
                    (t,k,p) -> producer.send(new ProducerRecord<>(t,k,p)), topic, batch);
            try {
                while (running.get()) {
                    try {
                        if (publisher.publishBatch() == 0) Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception failure) {
                        System.err.println("Outbox batch stopped; retry in 1000ms: " + failure);
                        try { Thread.sleep(1000); } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); break;
                        }
                    }
                }
            } finally { producer.close(Duration.ofSeconds(5)); }
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { /* JVM shutdown */ }
        }
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
}
