# Opt-in real Kafka integration tests

Requires Java 21, Maven Wrapper and an already running dedicated local Kafka broker (default `127.0.0.1:19092`). `./mvnw verify` remains broker-independent: Surefire runs the ordinary tests, not `KafkaMessagingIT`. Selecting `kafka-it` binds Failsafe to `integration-test` and `verify`. **Missing broker is a failure, never a skipped success.**

From the repository root (PowerShell: use `.\mvnw.cmd`):

```sh
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress -Pkafka-it verify
# Override only when using another dedicated test broker:
./mvnw -Pkafka-it -Dkafka.bootstrap=127.0.0.1:19092 verify
```

The test never starts or stops Docker. To prepare the demo broker yourself see `messaging-usage.md`. Do not point these tests at a shared production cluster.

## What the test proves

- A fresh temporary SQLite API database creates a Pending reservation over HTTP, explicitly approves it, repeats approval and creates an automatically Approved reservation.
- Exactly two immutable pending outbox events result. The real Kafka producer uses the application producer configuration and `OutboxPublisher`; broker ACKs empty the pending list.
- The exact original key and payload of one event are explicitly sent again (no new event ID or timestamp).
- A real KafkaConsumer, `NotificationConsumer` and a separate temporary NotificationDatabase consume three records but produce exactly two durable notifications.
- Admin API independently reads committed offset 3 on the single-partition test topic, including the replay. Reopening the notification DB retains both effects.
- Each invocation uses a UUID topic `gearreserve-it-*` and group `gearreserve-it-group-*`. Admin creates/deletes only that invocation's names; the default demo topic and existing application databases are untouched.

Failsafe reports are under `target/failsafe-reports`. HTTP calls, admin requests, producer sends, consumer polling, closes and gate waits are bounded. A whole test has a 450-second limit and the fork a 480-second limit. On abrupt termination or broker failure, cleanup may fail: recover the dedicated broker and delete only the exact UUID topic/group reported by that invocation, never a wildcard or all topics.

## Optional real outage and recovery

The same test can include an externally orchestrated outage. No mocks and no Docker process invocation inside Java. Run only on a broker you are authorized to stop and with no other dependent tests/apps running.

Choose a **fresh empty absolute directory**, shared by test and operator. In terminal A:

```powershell
$gates = Join-Path $PWD ('target/kafka-gates-' + [guid]::NewGuid().ToString())
$gates # copy this exact path to terminal B
.\mvnw.cmd --batch-mode --no-transfer-progress -Pkafka-it '-Dkafka.recovery=true' "-Dkafka.gate.dir=$gates" verify
```

Terminal B, from this repo, uses that exact directory. Wait for each marker before the corresponding action; each wait in the test permits 180 seconds:

1. **`ready-stop` exists:** initial real-broker connectivity and unique topic creation succeeded, API has two pending events. Stop only this local Compose broker:
   ```powershell
   docker compose stop kafka
   # Only after successful stop:
   New-Item -ItemType File -Path (Join-Path $gates 'broker-down')
   ```
2. **`ready-start` exists:** the test observed an actual producer exception while offline and verified both original events are still pending unchanged. Restart the same broker with its retained volume (do not run `down -v`):
   ```powershell
   docker compose start kafka
   docker compose up -d --wait kafka
   # Only after successful health/readiness check:
   New-Item -ItemType File -Path (Join-Path $gates 'broker-up')
   ```
3. The test creates a new producer, publishes retained messages, explicitly replays one, consumes all three, verifies two effects and committed offset, then cleans up its own topic/group.

Never signal `broker-down` without stopping the broker or `broker-up` before it is healthy. A stale/nonempty marker directory is rejected. If the test fails midway, restore the broker manually even if `ready-start` was not written. This scenario proves recovery with publisher recreation (a process-restart equivalent), not all distributed crash interleavings or production reliability.
