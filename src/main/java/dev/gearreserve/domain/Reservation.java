package dev.gearreserve.domain;

import java.time.Instant;

public record Reservation(
        long id,
        long equipmentId,
        String requesterAlias,
        Instant startUtc,
        Instant endUtc,
        ReservationStatus status
) {
    public Reservation {
        if (id < 0) {
            throw new IllegalArgumentException("Reservation id cannot be negative");
        }
        if (equipmentId <= 0) {
            throw new IllegalArgumentException("Equipment id must be positive");
        }
        if (requesterAlias == null || requesterAlias.isBlank()) {
            throw new IllegalArgumentException("Requester alias cannot be blank");
        }
        if (startUtc == null || endUtc == null || !startUtc.isBefore(endUtc)) {
            throw new IllegalArgumentException("startUtc must be earlier than endUtc");
        }
        if (status == null) {
            throw new IllegalArgumentException("Reservation status is required");
        }
    }
}
