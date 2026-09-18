# GearReserve domain and API contract

GearReserve is a small local teaching API, not a complete reservation system. This document separates the original planned contract from the current implementation on `main`. A planned route is not a promise that the running API exposes it.

## Original planned contract

The initial skeleton exposed only `GET /health`. That describes the starting point, **not the current state**.

### Domain records

- `Equipment(id, name, requiresApproval)`
- `Reservation(id, equipmentId, requesterAlias, startUtc, endUtc, status)`
- `status ∈ { Pending, Approved }`

### Business rules

1. `startUtc` must be earlier than `endUtc`; otherwise return HTTP `400`.
2. A reservation for equipment with `requiresApproval=false` starts as `Approved`.
3. A reservation for equipment with `requiresApproval=true` starts as `Pending`.
4. Approving an already approved reservation is idempotent and returns the same approved result.
5. Unknown equipment or reservation IDs return HTTP `404` where applicable.
6. An unknown reservation status filter returns HTTP `400`.
7. Requester aliases are fictional demo values and are not personal data.

### Planned domain routes

- `GET /equipment`
- `POST /reservations`
- `GET /reservations` with optional `status=Pending|Approved`
- `POST /reservations/{id}/approve`

These are the original planning boundaries, not a list of completed endpoints. No other domain capabilities were included in that plan.

## Current implementation on `main`

Source of truth: [`App.java`](../src/main/java/dev/gearreserve/App.java) registers `/health` and `/reservations`; the reservation handler dispatches the operations below.

| Method and route | Current behavior |
|---|---|
| `GET /health` | `200`, JSON `{"status":"ok"}`. |
| `POST /reservations` | Accepts `equipmentId`, `requesterAlias`, `startUtc`, `endUtc`; returns the created reservation with `201`. Equipment determines `Pending` or `Approved`. Unknown equipment returns `404`; an invalid interval returns `400`. |
| `GET /reservations` | `200`, JSON array of all reservations ordered by ID. |
| `GET /reservations?status=Pending` | `200`, only `Pending` reservations, ordered by ID. |
| `GET /reservations?status=Approved` | `200`, only `Approved` reservations, ordered by ID. |
| `POST /reservations/{id}/approve` | For a numeric reservation ID, returns the approved reservation with `200`; repeated approval is idempotent. Unknown numeric ID returns `404`. |
| `GET /equipment` | **Not implemented as an HTTP route.** It remains an unimplemented item from the original plan. |

The list handler accepts no query string or exactly `status=Pending` / `status=Approved`. Other query strings, including an unknown or differently cased status, return `400` with `{"error":"invalid_status"}`. This is deliberately narrow query handling, not a general query-parameter API.

Equipment is seeded in SQLite and used when creating reservations. [`Database.java`](../src/main/java/dev/gearreserve/infrastructure/Database.java) has `listEquipment()`, but the existence of that database method does not expose `GET /equipment`. The fixed seed IDs are documented in the [README](../README.md#seeded-equipment).

The status filter is already present on current `main`, along with [`ListReservationsHttpTest.java`](../src/test/java/dev/gearreserve/ListReservationsHttpTest.java). These statements are based on reading source files, not a new test execution or a claim that the full original plan is complete.

## Messaging extension

The HTTP paths and responses above stay unchanged. The messaging extension adds:

- Creating an automatically Approved reservation atomically adds a `ReservationApproved` v1 event to SQLite `outbox`.
- Creating Pending adds no event. Its first approval adds one event in the same transaction as the status change.
- Repeating approval adds no event; legacy Approved rows are not backfilled.
- A separately started publisher sends the immutable event to Kafka; the HTTP server itself does not require a broker.
- A separately started consumer stores a deduplicated demonstration notification in its own SQLite DB. No email or new HTTP endpoint is introduced.

See [message contract and guarantees](messaging-design.md) and [local runtime instructions](messaging-usage.md).

## Presentation and independent practice

The [PromptHelper demo walkthrough](demo-walkthrough.md) explains the staged story: a small API task, an intentionally deferred filter, and a new session that loads the saved plan before continuing. It is not a new replay of the filter implementation against today's `main`. For an independent attempt, choose a new bounded task and your own plan rather than asking an agent to implement the existing filter again.
