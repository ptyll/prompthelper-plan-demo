package dev.gearreserve.messaging;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReservationApprovedCodecTest {
    private final ReservationApproved event = new ReservationApproved(UUID.fromString("85e9772d-26ca-46b8-b2a2-5075e36f68ac"),
            Instant.parse("2026-09-18T10:15:00Z"), 42, 7, "demo-quote\"\\\n");
    @Test void roundTripAndUnknownFieldsNormalize() {
        String json = ReservationApprovedCodec.encode(event);
        assertEquals(event, ReservationApprovedCodec.decode(json));
        assertEquals(event, ReservationApprovedCodec.decode(json.replace("{", "{\"extension\":true,")));
        assertEquals(event, ReservationApprovedCodec.decode(json.replace("00Z", "00.000Z")));
        assertEquals("42", event.key());
    }
    @Test void rejectsNumericCoercionMissingFieldsAndInvalidContract() {
        String json = ReservationApprovedCodec.encode(event);
        for (String invalid : new String[]{"null", "[]", "{}", json + " {}",
                json.replace("\"reservationId\":42", "\"reservationId\":\"42\""),
                json.replace("\"reservationId\":42", "\"reservationId\":42.0"),
                json.replace("\"reservationId\":42", "\"reservationId\":9223372036854775808"),
                json.replace("\"reservationId\":42", "\"reservationId\":0"),
                json.replace("\"equipmentId\":7", "\"equipmentId\":-1"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                json.replace("ReservationApproved", "Other"),
                json.replace(event.eventId().toString(), "1-1-1-1-1"),
                json.replace("2026-09-18T10:15:00Z", "2026-09-18T10:15:00+00:00"),
                json.replace("2026-09-18T10:15:00Z", "not-time"),
                json.replace("2026-09-18T10:15:00Z", "2026-09-18T24:00:00Z"),
                json.replace("2026-09-18T10:15:00Z", "2026-09-18T23:59:60Z"),
                json.replace("\"equipmentId\":7,", ""),
                json.replace("{", "{\"reservationId\":99,")}) {
            assertThrows(IllegalArgumentException.class, () -> ReservationApprovedCodec.decode(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> new ReservationApproved(UUID.randomUUID(),Instant.now(),1,1," "));
    }
}
