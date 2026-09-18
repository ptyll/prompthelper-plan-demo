# ReservationApproved v1 — návrh rozšíření

Stav: implementováno v lokálním pracovním stromu, zatím nepublikováno. Ověřeno 38 broker-free testů a opt-in integrační test se skutečnou Kafka 3.9.1, včetně externě řízeného výpadku a obnovení. Podrobnosti a hranice viz [Kafka testy](kafka-tests.md). Stávající HTTP rozhraní se nemění.

## Rozsah

Událost vznikne právě jednou při prvním dosažení Approved: jak při vytvoření automaticky schválené rezervace, tak při změně Pending → Approved. Opakované schválení nevytváří nový event. Existující Approved záznamy se při migraci zpětně nepublikují. Žádné emaily, žádné osobní údaje, jen fiktivní requesterAlias a lokálně uložená demonstrační notifikace.

## Kontrakt

Topic `gearreserve.reservation-approved.v1`, key = reservationId jako desítkový řetězec (invariant producenta; konzument validuje payload, nikoli key); lokální Kafka `127.0.0.1:19092`, group `gearreserve-notifications-v1`.

```json
{
  "eventId": "85e9772d-26ca-46b8-b2a2-5075e36f68ac",
  "eventType": "ReservationApproved",
  "schemaVersion": 1,
  "occurredAt": "2026-09-18T10:15:00Z",
  "reservationId": 42,
  "equipmentId": 7,
  "requesterAlias": "demo-uzivatel"
}
```

Všechna pole povinná. eventId je UUID, occurredAt ISO-8601 UTC Instant, obě ID kladná 64bitová celá čísla, alias neprázdný řetězec. Typ a verze jsou přesně ReservationApproved/1. Konzument odmítá vadný payload a neznámou verzi; dodatečná neznámá pole lze ignorovat pro dopředně kompatibilní rozšíření stejné verze.

## Uložení a doručení

- SQLite: změna rezervace a immutable outbox payload musí být jedna transakce na jednom spojení. Selhání vložení outboxu vrátí i změnu stavu/vytvoření rezervace.
- Aditivní schema, žádné drop/delete stávajících dat. Unique constraint pro event schválení dané rezervace.
- Samostatný publisher čte omezené dávky neodeslaných zpráv podle pořadí. Pro demo jediný publisher; žádné tvrzení o distribuovaném claimingu.
- Teprve broker ACK dovoluje nastavit published_at. Při chybě zpráva zůstává v outboxu; další pokus s krátkou omezenou prodlevou, žádný busy loop.
- Pád mezi ACK a DB zápisem může vést k duplicitě: at-least-once, nikoli end-to-end exactly-once.
- Konzument používá vlastní SQLite soubor. Unikátní event_id a notifikace se uloží atomicky (jedna tabulka notifications může sloužit jako inbox i side effect).
- Offset se commituje po DB commitu, také u již známé identické události. Stejný eventId s jiným obsahem je konflikt, ne tiché přepsání.
- Vadná zpráva nebo DB chyba: fail-stop bez commitu daného offsetu, jasná diagnostika, žádné tiché přeskočení. DLQ a produkční provoz nejsou součástí malého dema.
- Procesy mají shutdown/close, konfigurace bootstrap/topic/group/DB je explicitní. Broker není podmínkou běžného API startu ani standardního verify.

## Implementační invarianty z revize návrhu

- Approval provede podmíněný UPDATE WHERE status='Pending'; affectedRows určí zda vzniká událost. Výsledek se čte na stejném spojení, commit před návratem. SQLite busy_timeout omezuje kolize souběžných HTTP požadavků.
- Outbox má UNIQUE(reservation_id,event_type) i event_id a zůstává uložený po publikaci. INSERT konflikt není tiše ignorovaný. Runtime/serialization chyba též rollbackuje doménovou změnu.
- Kafka consumer commituje explicitní TopicPartition→OffsetAndMetadata(record.offset+1), nikdy holé commitSync() podle pozice celého poll batch. Po chybě ihned stop, žádný finally commit.
- Deduplikace porovnává všechna normalizovaná kontraktní pole, nikoli původní JSON whitespace/order; ignorovaná přídavná pole nesmějí tvořit falešný konflikt.
- JSON parser striktně kontroluje typy (integer není string/float), rozsahy a povinná pole; žádná implicitní numerická coercion. Validace před dedup.
- Publisher odesílá přesně uložený payload/eventId/occurredAt; acks=all, mark až po dokončení send future. Při síťové chybě ukončit dávku a backoff, bez držené DB transakce.

## Ověření

Dočasné izolované DB v testech. Regrese původních16testů; nový event při autoapproval i explicit approval, žádný event při Pending/repeat/notfound/invalid, atomic rollback, zachování starých dat, serializace/validace, publishfailure a replay poACK, consumer dedup/conflict/reopen. Opt-in integrační profil se skutečnou Kafka: HTTP→outbox→broker→consumer→notifikace, duplicity a obnovení po nedostupnosti brokeru. Základní Maven verify nevyžaduje Docker.
