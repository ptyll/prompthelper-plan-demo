package dev.gearreserve.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable normalized v1 contract, shared by producer and consumer. */
public record ReservationApproved(UUID eventId, Instant occurredAt, long reservationId,
                                  long equipmentId, String requesterAlias) {
    public static final String EVENT_TYPE = "ReservationApproved";
    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_TOPIC = "gearreserve.reservation-approved.v1";

    public ReservationApproved {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (reservationId <= 0 || equipmentId <= 0) throw new IllegalArgumentException("IDs must be positive");
        if (requesterAlias == null || requesterAlias.isBlank()) throw new IllegalArgumentException("requesterAlias is required");
    }

    public String key() { return Long.toString(reservationId); }
}
