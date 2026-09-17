# GearReserve

GearReserve is a deliberately small Java 21 API used in a Czech developer presentation about implementing a plan with an AI agent through PromptHelper MCP.

The repository is a **local teaching demo**, not a production service:

- it has no authentication or authorization;
- it uses only fictional aliases and seeded equipment;
- it is not deployed as a public internet service;
- the current implementation exposes the bounded endpoints documented below and no unrelated features.

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

These endpoints are implemented and covered by the repository's Maven verification suite:

| Method | Route | Contract |
|---|---|---|
| `GET` | `/equipment` | List seeded equipment. |
| `POST` | `/reservations` | Create a reservation using the approval and interval rules above. |
| `GET` | `/reservations?status=Pending|Approved` | List reservations; optional status filter. Unknown filter returns `400`. |
| `POST` | `/reservations/{id}/approve` | Approve a pending reservation. Repeating approval is idempotent. Unknown ID returns `404`. |

Invalid intervals return `400`. No additional domain features are part of this demo.

## Repository boundaries

This public repository contains only the new GearReserve demo code and fictional data. It does not contain PromptHelper source code, private screenshots, browser profiles, session logs, private URLs, credentials, presentation files, or internal project history.

## License

No license has been selected yet. All rights remain with the repository owner until an explicit license is added.
