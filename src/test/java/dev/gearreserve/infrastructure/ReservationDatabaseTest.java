package dev.gearreserve.infrastructure;

import dev.gearreserve.domain.Reservation;
import dev.gearreserve.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservationDatabaseTest {
    @TempDir
    Path tempDirectory;

    @Test
    void createFindAndListRoundTripUtcInstants() throws Exception {
        Database database = new Database(tempDirectory.resolve("reservations.db"));
        database.initialize();
        Instant start = Instant.parse("2026-10-01T08:00:00Z");
        Instant end = Instant.parse("2026-10-01T09:30:00Z");

        Reservation created = database.createReservation(2, "demo-vyvojar", start, end, ReservationStatus.Pending);

        assertEquals(1, created.id());
        assertEquals(start, created.startUtc());
        assertEquals(end, created.endUtc());
        assertEquals(created, database.findReservation(created.id()).orElseThrow());
        assertEquals(List.of(created), database.listReservations());
    }

    @Test
    void reservationRejectsInvalidIntervalsBeforePersistence() throws Exception {
        Database database = new Database(tempDirectory.resolve("invalid.db"));
        database.initialize();
        Instant sameInstant = Instant.parse("2026-10-01T08:00:00Z");

        assertThrows(IllegalArgumentException.class,
                () -> database.createReservation(1, "demo-vyvojar", sameInstant, sameInstant,
                        ReservationStatus.Approved));
        assertEquals(List.of(), database.listReservations());
    }

    @Test
    void initializeDoesNotDeleteExistingReservations() throws Exception {
        Database database = new Database(tempDirectory.resolve("preserved.db"));
        database.initialize();
        Reservation created = database.createReservation(
                1,
                "demo-test",
                Instant.parse("2026-10-02T08:00:00Z"),
                Instant.parse("2026-10-02T09:00:00Z"),
                ReservationStatus.Approved
        );

        database.initialize();

        assertEquals(List.of(created), database.listReservations());
    }
}
