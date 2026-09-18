package dev.gearreserve.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gearreserve.App;
import dev.gearreserve.infrastructure.Database;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-broker test. Never starts/stops Docker or touches application topics/groups. */
class KafkaMessagingIT {
    @TempDir Path directory;
    private static final Duration CLOSE = Duration.ofSeconds(5);
    private static final String BOOTSTRAP = System.getProperty("kafka.bootstrap", "127.0.0.1:19092");

    @Test
    @Timeout(value = 450, unit = TimeUnit.SECONDS)
    void httpOutboxKafkaNotificationAndExactReplay() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String topic = "gearreserve-it-" + suffix;
        String group = "gearreserve-it-group-" + suffix;
        Properties adminProperties = new Properties();
        adminProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        adminProperties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        adminProperties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        Admin admin = Admin.create(adminProperties);
        boolean topicCreated = false;
        boolean groupUsed = false;
        try {
            // Fail, rather than skip, when kafka-it was explicitly selected without a broker.
            assertFalse(admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).isEmpty());
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            topicCreated = true;
            Path dbPath = directory.resolve("reservations.db");
            var server = App.createServer(0, dbPath);
            server.start();
            Database db = new Database(dbPath);
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                String base = "http://127.0.0.1:" + server.getAddress().getPort();
                var pending = post(http, base + "/reservations", body(2));
                assertEquals(201, pending.statusCode(), pending.body());
                assertEquals("Pending", new ObjectMapper().readTree(pending.body()).get("status").asText());
                assertTrue(db.pendingOutbox(10).isEmpty());
                long id = new ObjectMapper().readTree(pending.body()).get("id").asLong();
                var approved = post(http, base + "/reservations/" + id + "/approve", "");
                assertEquals(200, approved.statusCode(), approved.body());
                assertEquals(approved.body(), post(http, base + "/reservations/" + id + "/approve", "").body());
                var automatic = post(http, base + "/reservations", body(1));
                assertEquals(201, automatic.statusCode(), automatic.body());
                assertEquals("Approved", new ObjectMapper().readTree(automatic.body()).get("status").asText());
            } finally { server.stop(0); }
            var original = db.pendingOutbox(10);
            assertEquals(2, original.size());
            assertNotEquals(original.get(0).payload(), original.get(1).payload());

            if (Boolean.getBoolean("kafka.recovery")) {
                Path gates = Path.of(System.getProperty("kafka.gate.dir", ""));
                assertFalse(System.getProperty("kafka.gate.dir", "").isBlank(), "Provide a fresh kafka.gate.dir");
                Files.createDirectories(gates);
                for (String name : List.of("ready-stop", "broker-down", "ready-start", "broker-up"))
                    assertFalse(Files.exists(gates.resolve(name)), "Use a fresh gate directory: " + name);
                Files.writeString(gates.resolve("ready-stop"), "Stop only the dedicated local demo broker, then create broker-down.\n");
                awaitGate(gates.resolve("broker-down"));
                KafkaProducer<String,String> offline = new KafkaProducer<>(KafkaOutboxPublisher.producerProperties(BOOTSTRAP));
                try {
                    assertThrows(Exception.class, () -> publisher(db, offline, topic).publishBatch(),
                            "Publisher must fail against the actually stopped broker");
                    assertEquals(original, db.pendingOutbox(10), "Failure must preserve exact immutable pending events");
                } finally { offline.close(CLOSE); }
                Files.writeString(gates.resolve("ready-start"), "Failed send preserved both pending events. Start broker, wait healthy, then create broker-up.\n");
                awaitGate(gates.resolve("broker-up"));
                assertFalse(admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).isEmpty());
            }

            KafkaProducer<String,String> producer = new KafkaProducer<>(KafkaOutboxPublisher.producerProperties(BOOTSTRAP));
            try {
                assertEquals(2, publisher(db, producer, topic).publishBatch());
                assertTrue(db.pendingOutbox(10).isEmpty(), "Both ACKs must mark outbox published");
                assertEquals(0, publisher(db, producer, topic).publishBatch());
                // Resend EXACT stored payload/key, not a reconstructed or newly timestamped event.
                var replay = original.getFirst();
                producer.send(new ProducerRecord<>(topic, replay.key(), replay.payload())).get(20, TimeUnit.SECONDS);
            } finally { producer.close(CLOSE); }

            NotificationDatabase notifications = new NotificationDatabase(directory.resolve("notifications.db"));
            notifications.initialize();
            KafkaConsumer<String,String> consumer = new KafkaConsumer<>(KafkaNotificationConsumer.consumerProperties(BOOTSTRAP, group));
            try {
                consumer.subscribe(List.of(topic));
                groupUsed = true;
                NotificationConsumer processor = new NotificationConsumer(consumer, notifications::store);
                int received = 0;
                long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                while (received < 3 && System.nanoTime() < deadline) {
                    var records = consumer.poll(Duration.ofMillis(500));
                    for (var record : records) {
                        assertEquals(topic, record.topic());
                        var event = ReservationApprovedCodec.decode(record.value());
                        assertEquals(Long.toString(event.reservationId()), record.key());
                        assertTrue(original.stream().anyMatch(m -> m.payload().equals(record.value())), "Kafka payload differs from outbox");
                    }
                    processor.process(records);
                    received += records.count();
                }
                assertEquals(3, received, "Two events plus explicit replay must be consumed");
                assertEquals(2, notifications.list().size(), "Replay must not duplicate the durable side effect");
                Set<String> expectedIds = new HashSet<>();
                for (var message : original) expectedIds.add(ReservationApprovedCodec.decode(message.payload()).eventId().toString());
                assertEquals(expectedIds, new HashSet<>(notifications.list().stream().map(NotificationDatabase.Notification::eventId).toList()));
                var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                assertEquals(3L, offsets.get(new TopicPartition(topic, 0)).offset(), "Admin must observe committed offset after duplicate too");
                var reopened = new NotificationDatabase(directory.resolve("notifications.db"));
                reopened.initialize();
                assertEquals(2, reopened.list().size());
            } finally { consumer.close(CLOSE); }
        } finally {
            // UUID ownership is local to this invocation; never enumerate/delete other resources.
            try {
                if (groupUsed) admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            } finally {
                try { if (topicCreated) admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS); }
                finally { admin.close(CLOSE); }
            }
        }
    }

    private static OutboxPublisher publisher(Database db, KafkaProducer<String,String> producer, String topic) {
        return new OutboxPublisher(OutboxPublisher.databaseStore(db),
                (t, k, payload) -> producer.send(new ProducerRecord<>(t, k, payload)), topic, 10);
    }
    private static void awaitGate(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) Thread.sleep(200);
        assertTrue(Files.isRegularFile(file), "External broker operator did not signal within 180s: " + file);
    }
    private static String body(int equipment) {
        return "{\"equipmentId\":" + equipment + ",\"requesterAlias\":\"demo-kafka-it\",\"startUtc\":\"2026-10-01T10:00:00Z\",\"endUtc\":\"2026-10-01T11:00:00Z\"}";
    }
    private static HttpResponse<String> post(HttpClient client, String uri, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
