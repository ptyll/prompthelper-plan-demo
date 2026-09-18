package dev.gearreserve.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone, opt-in consumer. Poison messages require operator remediation, never silent skipping. */
public final class KafkaNotificationConsumer {
    private KafkaNotificationConsumer() { }
    public static Properties consumerProperties(String bootstrap, String group) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        return properties;
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"--list".equals(args[0])))
            throw new IllegalArgumentException("Usage: KafkaNotificationConsumer [--list]");
        NotificationDatabase database = new NotificationDatabase(Path.of(env("GEARRESERVE_NOTIFICATION_DB", "data/notifications.db")));
        database.initialize();
        if (args.length == 1) {
            for (var notification : database.list()) System.out.println(notification);
            return;
        }
        AtomicBoolean running = new AtomicBoolean(true);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(
                env("GEARRESERVE_KAFKA_BOOTSTRAP", "127.0.0.1:19092"),
                env("GEARRESERVE_KAFKA_GROUP", "gearreserve-notifications-v1")));
        Thread main = Thread.currentThread();
        Thread hook = new Thread(() -> {
            running.set(false);
            consumer.wakeup(); // The only consumer operation invoked from the shutdown thread.
            try { main.join(7000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "notifications-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            consumer.subscribe(List.of(env("GEARRESERVE_KAFKA_TOPIC", ReservationApproved.DEFAULT_TOPIC)));
            NotificationConsumer processor = new NotificationConsumer(consumer, database::store);
            while (running.get()) processor.process(consumer.poll(Duration.ofMillis(500)));
        } catch (WakeupException wakeup) {
            if (running.get()) throw wakeup;
        } catch (Exception failure) {
            System.err.println("Notification consumer fail-stopped; DB commit precedes offset commit; commit failure can leave offset outcome unknown: " + failure);
            throw failure;
        } finally {
            consumer.close(Duration.ofSeconds(5)); // auto commit disabled; no finally commit.
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { /* JVM shutdown */ }
        }
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
}
