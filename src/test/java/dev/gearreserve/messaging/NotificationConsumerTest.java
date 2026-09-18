package dev.gearreserve.messaging;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationConsumerTest {
    @TempDir Path directory;
    private static final TopicPartition PARTITION = new TopicPartition(ReservationApproved.DEFAULT_TOPIC, 0);
    private ReservationApproved event() { return new ReservationApproved(UUID.randomUUID(), Instant.parse("2026-09-18T10:15:00Z"), 42, 7, "demo-alias"); }
    private NotificationDatabase database() throws Exception {
        var database = new NotificationDatabase(directory.resolve("notifications.db")); database.initialize(); return database;
    }
    private ConsumerRecords<String,String> records(String... payloads) {
        List<ConsumerRecord<String,String>> records = new ArrayList<>();
        for (int i=0; i<payloads.length; i++) records.add(new ConsumerRecord<>(PARTITION.topic(), 0, i, "42", payloads[i]));
        return new ConsumerRecords<>(Map.of(PARTITION, records));
    }
    private static class TrackingConsumer extends MockConsumer<String,String> {
        final List<Map<TopicPartition,OffsetAndMetadata>> commits = new ArrayList<>();
        boolean rejectCommit;
        TrackingConsumer() { super(OffsetResetStrategy.EARLIEST); }
        @Override public synchronized void commitSync(Map<TopicPartition,OffsetAndMetadata> offsets) {
            if (rejectCommit) throw new CommitFailedException("simulated offset failure");
            commits.add(Map.copyOf(offsets));
        }
        @Override public synchronized void commitSync() { fail("Parameterless commit can skip poison records in a batch"); }
    }
    @Test void dedupSurvivesReopenAndKeepsOriginalNotification() throws Exception {
        var database = database(); var event = event();
        assertTrue(database.store(event)); var original = database.list().getFirst();
        var reopened = database(); assertFalse(reopened.store(event));
        assertEquals(List.of(original), reopened.list());
    }
    @Test void sameIdDifferentContractFailsAndPreservesOriginal() throws Exception {
        var database = database(); var event = event(); database.store(event);
        var different = new ReservationApproved(event.eventId(), event.occurredAt(), 43, 7, "demo-alias");
        assertThrows(IllegalStateException.class, () -> database.store(different));
        assertEquals(ReservationApprovedCodec.encode(event), database.list().getFirst().payload());
        assertEquals(1, database.list().size());
    }
    @Test void databaseInsertFailureRollsBackAndCanRetry() throws Exception {
        var database = database(); var event = event();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("notifications.db")); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_notification BEFORE INSERT ON notifications BEGIN SELECT RAISE(ABORT, 'simulated failure'); END");
            assertThrows(java.sql.SQLException.class, () -> database.store(event));
            assertTrue(database.list().isEmpty());
            statement.execute("DROP TRIGGER fail_notification");
        }
        assertTrue(database.store(event));
    }
    @Test void normalizedReplayIgnoresJsonOrderWhitespaceExtraFieldsAndInstantPrecision() throws Exception {
        var database = database(); var event = event();
        String payload = ReservationApprovedCodec.encode(event);
        String replay = payload.replace("2026-09-18T10:15:00Z", "2026-09-18T10:15:00.000Z")
                .replace("{", "{\n \"futureField\":true, ").replace(event.eventId().toString(), event.eventId().toString().toUpperCase(Locale.ROOT));
        try (var consumer = new TrackingConsumer()) {
            new NotificationConsumer(consumer,database::store).process(records(payload,replay));
            assertEquals(1,database.list().size()); assertEquals(2,consumer.commits.size());
            assertEquals(2,consumer.commits.getLast().get(PARTITION).offset());
        }
    }
    @Test void poisonInBatchStopsBeforeLaterRecordAndCommitsOnlyFirstOffset() throws Exception {
        var database = database();
        try (var consumer = new TrackingConsumer()) {
            var processor = new NotificationConsumer(consumer,database::store);
            assertThrows(IllegalArgumentException.class, () -> processor.process(records(ReservationApprovedCodec.encode(event()), "not-json", ReservationApprovedCodec.encode(event()))));
            assertEquals(1,database.list().size()); assertEquals(1,consumer.commits.size());
            assertEquals(1,consumer.commits.getFirst().get(PARTITION).offset());
            assertThrows(IllegalStateException.class, () -> processor.process(records(ReservationApprovedCodec.encode(event()))));
        }
    }
    @Test void unknownVersionIsValidatedEvenForKnownIdAndDoesNotCommit() throws Exception {
        var database = database(); var event = event(); database.store(event);
        try (var consumer = new TrackingConsumer()) {
            assertThrows(IllegalArgumentException.class, () -> new NotificationConsumer(consumer,database::store).process(records(ReservationApprovedCodec.encode(event).replace("\"schemaVersion\":1", "\"schemaVersion\":2"))));
            assertTrue(consumer.commits.isEmpty()); assertEquals(1,database.list().size());
        }
    }
    @Test void conflictStopsBatchWithoutOffsetOrLaterSideEffect() throws Exception {
        var database = database(); var event = event(); database.store(event);
        var conflict = new ReservationApproved(event.eventId(),event.occurredAt(),42,8,"demo-alias");
        try (var consumer = new TrackingConsumer()) {
            assertThrows(IllegalStateException.class, () -> new NotificationConsumer(consumer,database::store).process(records(ReservationApprovedCodec.encode(conflict),ReservationApprovedCodec.encode(event()))));
            assertTrue(consumer.commits.isEmpty()); assertEquals(1,database.list().size());
        }
    }
    @Test void dbFailureNeverCommitsOffset() {
        try (var consumer = new TrackingConsumer()) {
            assertThrows(java.sql.SQLException.class, () -> new NotificationConsumer(consumer,e -> { throw new java.sql.SQLException("unavailable"); }).process(records(ReservationApprovedCodec.encode(event()))));
            assertTrue(consumer.commits.isEmpty());
        }
    }
    @Test void offsetFailureStopsAfterDatabaseCommitAndReplayIsSafe() throws Exception {
        var database = database(); var event = event();
        try (var consumer = new TrackingConsumer()) {
            consumer.rejectCommit = true;
            assertThrows(CommitFailedException.class, () -> new NotificationConsumer(consumer,database::store).process(records(ReservationApprovedCodec.encode(event),ReservationApprovedCodec.encode(event()))));
            assertEquals(1,database.list().size()); assertTrue(consumer.commits.isEmpty());
            consumer.rejectCommit = false;
            new NotificationConsumer(consumer,database::store).process(records(ReservationApprovedCodec.encode(event)));
            assertEquals(1,database.list().size()); assertEquals(1,consumer.commits.size());
        }
    }
    @Test void commitsOnlyExplicitCurrentPartitionAfterStoreReturns() throws Exception {
        try (var consumer = new TrackingConsumer()) {
            var second = new TopicPartition(PARTITION.topic(),1);
            var batch = new ConsumerRecords<String,String>(new LinkedHashMap<>(Map.of(
                    PARTITION,List.of(new ConsumerRecord<>(PARTITION.topic(),0,8,"42",ReservationApprovedCodec.encode(event()))),
                    second,List.of(new ConsumerRecord<>(second.topic(),1,12,"42",ReservationApprovedCodec.encode(event()))))));
            final int[] stored = {0};
            new NotificationConsumer(consumer,e -> { assertEquals(stored[0],consumer.commits.size()); stored[0]++; return true; }).process(batch);
            assertEquals(2,consumer.commits.size());
            assertTrue(consumer.commits.stream().allMatch(m -> m.size()==1));
            assertTrue(consumer.commits.stream().anyMatch(m -> m.containsKey(PARTITION) && m.get(PARTITION).offset()==9));
            assertTrue(consumer.commits.stream().anyMatch(m -> m.containsKey(second) && m.get(second).offset()==13));
        }
    }
    @Test void consumerConfigurationDisablesAutoCommit() {
        var props = KafkaNotificationConsumer.consumerProperties("127.0.0.1:19092","gearreserve-notifications-v1");
        assertEquals("false",props.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals("earliest",props.getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertEquals("gearreserve-notifications-v1",props.getProperty(ConsumerConfig.GROUP_ID_CONFIG));
    }
}
