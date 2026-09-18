# Lokální messaging: ReservationApproved v1

API zůstává samostatné; běžný `verify` nepotřebuje Docker. Kafka publisher a notification consumer jsou explicitně spuštěné procesy. Žádné emaily, frontend ani další HTTP endpoint: vedlejším efektem je pouze uložená demonstrační notifikace ve vlastní SQLite DB.

## Předpoklady a broker

Java 21, Maven Wrapper a pro skutečný integrační běh Docker Compose. Compose používá připnutý `apache/kafka:3.9.1`, jeden KRaft broker a persistentní named volume. Host port se váže **jen na 127.0.0.1:19092**. Interní listener `kafka:9092` není publikovaný. Je to plaintext lokální demo, ne produkční konfigurace.

```powershell
docker compose config --quiet
docker compose up -d kafka
docker compose run --rm kafka-init
```

Init služba počká na zdravý broker a vytvoří topic `gearreserve.reservation-approved.v1` (jedna partition, replikační faktor 1); vytvoření je idempotentní. Automatické vytváření topiců je vypnuté. Při změně topicu přes environment jej nejprve explicitně vytvořte. Skutečné spuštění, přenos přes broker a obnova po jeho výpadku byly ověřeny samostatným [Kafka integračním testem](kafka-tests.md). Recovery ověřuje opětovné publikování novým producerem po obnovení brokeru, nikoli nepřetržité přežití původního procesu.

## Procesy

Příkazy spouštějte z kořene repozitáře, každý dlouho běžící proces ve vlastním terminálu. `JAVA_HOME` musí ukazovat na Java 21. Použijte stejnou `GEARRESERVE_DB` cestu pro API i publisher, ale jiný soubor pro consumer.

```powershell
$env:GEARRESERVE_DB = 'data/gearreserve.db'
$env:GEARRESERVE_KAFKA_BOOTSTRAP = '127.0.0.1:19092'
$env:GEARRESERVE_KAFKA_TOPIC = 'gearreserve.reservation-approved.v1'
$env:GEARRESERVE_KAFKA_GROUP = 'gearreserve-notifications-v1'
$env:GEARRESERVE_NOTIFICATION_DB = 'data/notifications.db'

# Terminál 1: původní HTTP API
.\mvnw.cmd --batch-mode --no-transfer-progress compile exec:java

# Terminál 2: outbox publisher
.\mvnw.cmd --batch-mode --no-transfer-progress compile exec:java '-Dexec.mainClass=dev.gearreserve.messaging.KafkaOutboxPublisher'

# Terminál 3: notification consumer
.\mvnw.cmd --batch-mode --no-transfer-progress compile exec:java '-Dexec.mainClass=dev.gearreserve.messaging.KafkaNotificationConsumer'
```

Výchozí hodnoty environment odpovídají příkladu výše; publisher navíc umožňuje `GEARRESERVE_PUBLISH_BATCH` (výchozí 100). Nové schválení vytvoří outbox event v téže transakci jako změnu rezervace. To zahrnuje i auto-approved vytvoření. Již existující Approved rezervace se zpětně nepublikují. Opakované schválení nevytvoří nový event. Pro vytvoření/schválení použijte stávající požadavky v [API kontraktu](api-contract.md).

## Zobrazení výsledku bez brokeru

`--list` inicializuje pouze notification DB a vypíše uložené řádky, nevytváří Kafka klienta:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress compile exec:java '-Dexec.mainClass=dev.gearreserve.messaging.KafkaNotificationConsumer' '-Dexec.args=--list'
```

Případně SQLite CLI (není předpokladem Java dema):

```text
sqlite3 data/notifications.db "SELECT event_id, notification_text, created_at FROM notifications ORDER BY created_at,event_id;"
```

`notifications` má `event_id` jako primary key, kanonický payload, text a UTC čas vytvoření notifikace. Identická duplicitní zpráva zachová původní text i čas. Deduplikace porovnává normalizovaný `ReservationApproved` record, nikoli whitespace, pořadí JSON klíčů nebo ignorovaná rozšiřující pole.

## Doručení, selhání a restart

- At-least-once: pád mezi broker ACK a zápisem publisheru může způsobit opakované doručení. SQLite consumeru zabrání duplicitnímu side effectu.
- Consumer validuje sdíleným striktním v1 codec před deduplikací. Uloží notifikaci v transakci a **až po DB commitu** provede explicitní offset commit pouze pro zpracovanou partition a `record.offset + 1`.
- Žádný commit celého poll batch, žádný auto commit ani commit ve finally. Pozdější záznamy po chybě se nezpracovávají.
- Chybný JSON/verze, stejný eventId s jiným kontraktem, SQLite chyba nebo offset commit failure znamenají okamžitý fail-stop. Konfliktní zpráva nepřepíše uloženou notifikaci. Proces vrátí chybu a vypíše diagnostiku. Při timeoutu offset commitu může být jeho výsledek na brokeru neznámý; trvalá deduplikace řeší případný replay. Zpracovávací diagnostika uvádí topic/partition/offset bez celého payloadu.
- Pokud selže commit offsetu po DB commitu, po restartu přijde zpráva znovu a porovná se s uloženým kontraktem. Při výpadku DB odstraňte příčinu a spusťte consumer znovu se stejnou group a stejnou DB.
- Poison zpráva není automaticky přeskočena. Je třeba zjistit příčinu a zvolit vědomou nápravu; toto demo neimplementuje DLQ ani automatické přeskakování offsetů. Pouhý restart stejnou vadnou zprávu neopraví.
- Consumer používá `auto.offset.reset=earliest` pouze když skupina nemá platný commit. Nemazat notification DB a současně ponechat staré group offsety, pokud chcete přehrát celý topic.
- Ctrl+C požádá consumer přes `wakeup()` o ukončení a zavře klienta. Broker zastavíte `docker compose stop kafka`; `docker compose down` zachová volume. Volbu `-v` používejte pouze při vědomém smazání demo historie.

## Ověření bez brokeru

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Unit testy používají izolované dočasné SQLite soubory a MockConsumer: restart/dedup, kanonický kontrakt, konflikt, rollback, poison message uprostřed batch, DB chyba, offset failure a explicitní offsety více partitions. Nejde o náhradu skutečného Kafka integračního testu.
