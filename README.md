# GearReserve

GearReserve is a **small local teaching API** in Java 21, used in a Czech developer presentation about implementing a plan with an AI agent through PromptHelper MCP. It is not a complete reservation system or a production service.

**Want to try it yourself? Start with [`docs/try-it.md`](docs/try-it.md)** — one task, two or three phases, one fresh conversation.

For the presentation story and copyable prompts, see the [PromptHelper demo walkthrough](docs/demo-walkthrough.md). The current `main` already includes the reservation status filter; the walkthrough explains the staged story, not a new replay of that change. For your own experiment, choose a new task and your own plan.

The repository has deliberately narrow boundaries:

- it has no authentication or authorization;
- it uses only fictional aliases and seeded equipment;
- it is not deployed as a public internet service;
- the current implementation exposes the bounded endpoints documented below and no unrelated features.

For the opt-in local Kafka extension (outbox publisher, idempotent notification consumer, and CLI listing), see [messaging usage](docs/messaging-usage.md). The standard API and `verify` do not require a broker. See [real Kafka integration and recovery tests](docs/kafka-tests.md) for the opt-in verification profile.

## Prerequisites

- Eclipse Temurin or another Java 21 JDK
- No global Maven installation is required: use the included Maven Wrapper.

## Build and test

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Windows PowerShell:

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD ".maven-user-home"
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

`MAVEN_USER_HOME` is useful on locked-down machines where the default user profile is not writable.

The tests use isolated temporary SQLite databases. They never open or delete the normal local database.

## Run locally

```bash
./mvnw --batch-mode --no-transfer-progress exec:java
```

Windows PowerShell:

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD ".maven-user-home"
.\mvnw.cmd --batch-mode --no-transfer-progress exec:java
```

Then verify the real health endpoint:

```bash
curl http://localhost:7070/health
# {"status":"ok"}
```

Optional non-secret environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `GEARRESERVE_PORT` | `7070` | Local loopback port |
| `GEARRESERVE_DB` | `data/gearreserve.db` | SQLite database path |

Startup creates missing tables and inserts the three deterministic equipment rows with `ON CONFLICT DO NOTHING`. It does not drop tables or delete existing rows.

## Seeded equipment

| ID | Name | Requires approval |
|---:|---|:---:|
| 1 | USB-C projektor | no |
| 2 | Termokamera | yes |
| 3 | Sada bezdrátových mikrofonů | no |

## Implemented domain contract

### Equipment

```text
Equipment(id, name, requiresApproval)
```

### Reservation

```text
Reservation(id, equipmentId, requesterAlias, startUtc, endUtc, status)
```

Rules:

- `status` is exactly `Pending` or `Approved`;
- equipment without approval creates an `Approved` reservation;
- equipment requiring approval creates a `Pending` reservation;
- `startUtc` must be earlier than `endUtc`;
- `requesterAlias` is fictional demo data, not personal data.

## Implemented API contract

The current `main` implements the following routes in [`App.java`](src/main/java/dev/gearreserve/App.java):

| Method | Route | Contract |
|---|---|---|
| `GET` | `/health` | Return `200` with `{"status":"ok"}`. |
| `POST` | `/reservations` | Create a reservation using the approval and interval rules above; return `201`. Unknown equipment ID returns `404`. |
| `GET` | `/reservations` | List all reservations in ID order. Optional query: exactly `status=Pending` or `status=Approved`; other query strings return `400`. |
| `POST` | `/reservations/{id}/approve` | Approve a reservation; return `200`. Repeating approval is idempotent. Unknown numeric ID returns `404`. |

Invalid intervals return `400`. No additional domain features are part of this demo.

**`GET /equipment` was part of the original plan, but is not registered in `App.java` and is not an available HTTP endpoint.** The seeded equipment and the database method `listEquipment()` do not imply an HTTP route. Use the fixed demo IDs listed above when creating reservations.

See [the API contract](docs/api-contract.md) for the explicit distinction between the original planned scope and the current implementation. This documentation describes source inspection, not a new test run; the build and test commands above let you verify your own checkout.

## Repository boundaries

This public repository contains only the new GearReserve demo code and fictional data. It does not contain PromptHelper source code, private screenshots, browser profiles, session logs, private URLs, credentials, presentation files, or internal project history.

## License

[MIT](LICENSE).
