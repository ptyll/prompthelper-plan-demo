package dev.gearreserve.messaging;

import dev.gearreserve.infrastructure.Database;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Single publisher only. At-least-once: an ACK followed by a failed mark is replayed. */
public final class OutboxPublisher {
    public interface Store {
        List<Database.OutboxMessage> pending(int limit) throws Exception;
        void markPublished(long id) throws Exception;
    }
    @FunctionalInterface
    public interface Sender { Future<?> send(String topic, String key, String payload) throws Exception; }
    private final Store store;
    private final Sender sender;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(Store store, Sender sender, String topic, int batchSize) {
        this.store = Objects.requireNonNull(store);
        this.sender = Objects.requireNonNull(sender);
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic required");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batch must be 1..1000");
        this.topic = topic;
        this.batchSize = batchSize;
    }

    public static Store databaseStore(Database db) {
        return new Store() {
            public List<Database.OutboxMessage> pending(int limit) throws Exception { return db.pendingOutbox(limit); }
            public void markPublished(long id) throws Exception { db.markPublished(id); }
        };
    }

    /** Stops at the first error; caller must back off before retrying. No DB connection spans send. */
    public int publishBatch() throws Exception {
        int count = 0;
        for (var message : store.pending(batchSize)) {
            sender.send(topic, message.key(), message.payload()).get(30, TimeUnit.SECONDS);
            store.markPublished(message.id());
            count++;
        }
        return count;
    }
}
