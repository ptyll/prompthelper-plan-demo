package dev.gearreserve.messaging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import java.util.Map;

/** Processes records sequentially; any storage, contract or offset failure must stop the caller. */
public final class NotificationConsumer {
    @FunctionalInterface public interface Store { boolean store(ReservationApproved event) throws Exception; }
    private final Consumer<String, String> consumer;
    private final Store store;
    private boolean failed;
    public NotificationConsumer(Consumer<String, String> consumer, Store store) {
        this.consumer = consumer;
        this.store = store;
    }
    public void process(ConsumerRecords<String, String> records) throws Exception {
        if (failed) throw new IllegalStateException("Consumer is fail-stopped; create a new instance after remediation");
        try {
            for (ConsumerRecord<String, String> record : records) {
                try {
                    ReservationApproved event = ReservationApprovedCodec.decode(record.value());
                    store.store(event); // Returns only after the SQLite transaction committed (including replay).
                    consumer.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(Math.addExact(record.offset(), 1))));
                } catch (Exception failure) {
                    System.err.printf("Notification record failed: topic=%s partition=%d offset=%d error=%s%n",
                            record.topic(), record.partition(), record.offset(), failure.getClass().getSimpleName());
                    throw failure;
                }
            }
        } catch (Exception failure) {
            failed = true;
            throw failure;
        }
    }
}
