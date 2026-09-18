# ReservationApproved outbox publisher (F3)

The HTTP API needs no Kafka connection. Auto-approved creation and the first Pending → Approved transition atomically persist a v1 event in SQLite `outbox`. Repeated approval and legacy Approved rows do not emit events. Existing rows are never backfilled. Domain change and payload insertion commit on the same connection or both roll back.

Run the API as before. In a separate process with Java 21:

```powershell
$env:GEARRESERVE_DB = 'data/gearreserve.db'
$env:GEARRESERVE_KAFKA_BOOTSTRAP = '127.0.0.1:19092'
$env:GEARRESERVE_KAFKA_TOPIC = 'gearreserve.reservation-approved.v1'
$env:GEARRESERVE_PUBLISH_BATCH = '100'
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dexec.mainClass=dev.gearreserve.messaging.KafkaOutboxPublisher' exec:java
```

These are also the defaults. Run exactly **one publisher** for this demo DB. Broker provisioning and the notification consumer are documented in [messaging usage](messaging-usage.md). Batch size is bounded to 1..1000. Kafka client is pinned to 3.9.1.

The publisher sends the stored immutable JSON unchanged, with reservationId as the Kafka key and `acks=all`. It waits for the send future before setting `published_at`. No DB connection/transaction spans the network send. The first send/ACK/mark error stops the batch, reports an error and waits 1000ms before retrying; idle polling waits 500ms. Delivery timeout is 15s, metadata blocking 5s, future wait bounded to 30s. Interrupt/shutdown closes the producer. Published rows remain stored.

An ACK followed by failure before the SQLite mark can replay the identical event. This is **at-least-once**, not end-to-end exactly-once. Consumer deduplication is required.

## Shared contract for the consumer phase

`dev.gearreserve.messaging.ReservationApproved` is an immutable normalized record with UUID `eventId`, Instant `occurredAt`, positive int64 `reservationId`/`equipmentId`, and nonblank `requesterAlias`. Constants define event type, schema version and default topic. `key()` returns the decimal reservation ID.

`ReservationApprovedCodec.decode(String)` strictly checks JSON types, required fields, UUID shape, UTC timestamp ending Z, version/type, integer ranges and positive IDs; numeric strings/floats are rejected. Unknown fields are ignored. Duplicate JSON fields and trailing documents are rejected. Invalid wire values throw `IllegalArgumentException`. Decode **before deduplication**. Record equality compares all normalized contract fields, so equivalent JSON order/whitespace, UTC fractional precision and ignored extra fields do not cause false conflicts. The consumer can decode its stored JSON and compare records for duplicate/conflict handling.

`encode(record)` emits v1 JSON. The publisher does not re-encode: it sends the payload captured at the domain transaction.

Standard `mvnw verify` is broker-free and includes fake ACK/failure/replay, HTTP, concurrent approval, rollback and legacy migration coverage. No live Kafka verification is claimed in F3.
