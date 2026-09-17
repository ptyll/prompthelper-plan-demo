# GearReserve domain and API contract

This document fixes the scope for subsequent implementation phases. The baseline skeleton implements only `GET /health`.

## Domain records

- `Equipment(id, name, requiresApproval)`
- `Reservation(id, equipmentId, requesterAlias, startUtc, endUtc, status)`
- `status ∈ { Pending, Approved }`

## Business rules

1. `startUtc` must be earlier than `endUtc`; otherwise return HTTP `400`.
2. A reservation for equipment with `requiresApproval=false` starts as `Approved`.
3. A reservation for equipment with `requiresApproval=true` starts as `Pending`.
4. Approving an already approved reservation is idempotent and returns the same approved result.
5. Unknown equipment or reservation IDs return HTTP `404` where applicable.
6. An unknown reservation status filter returns HTTP `400`.
7. Requester aliases are fictional demo values and are not personal data.

## Planned routes

- `GET /equipment`
- `POST /reservations`
- `GET /reservations` with optional `status=Pending|Approved`
- `POST /reservations/{id}/approve`

No other domain capabilities are in scope.
