package dev.gearreserve.messaging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;

/** Strict wire validation before deduplication; unknown fields are deliberately ignored. */
public final class ReservationApprovedCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ReservationApprovedCodec() { }

    public static String encode(ReservationApproved event) {
        var node = JSON.createObjectNode();
        node.put("eventId", event.eventId().toString());
        node.put("eventType", ReservationApproved.EVENT_TYPE);
        node.put("schemaVersion", ReservationApproved.SCHEMA_VERSION);
        node.put("occurredAt", event.occurredAt().toString());
        node.put("reservationId", event.reservationId());
        node.put("equipmentId", event.equipmentId());
        node.put("requesterAlias", event.requesterAlias());
        return node.toString();
    }

    public static ReservationApproved decode(String payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Expected JSON object");
            if (!ReservationApproved.EVENT_TYPE.equals(text(root, "eventType"))) throw new IllegalArgumentException("Unknown eventType");
            if (integer(root, "schemaVersion") != 1) throw new IllegalArgumentException("Unknown schemaVersion");
            String id = text(root, "eventId");
            if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid UUID");
            String time = text(root, "occurredAt");
            if (!time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z")) throw new IllegalArgumentException("Expected UTC Instant ending Z");
            return new ReservationApproved(UUID.fromString(id), java.time.OffsetDateTime.parse(time).toInstant(), integer(root, "reservationId"),
                    integer(root, "equipmentId"), text(root, "requesterAlias"));
        } catch (IOException | java.time.DateTimeException exception) {
            throw new IllegalArgumentException("Invalid ReservationApproved payload", exception);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("Required string: " + field);
        return node.textValue();
    }
    private static long integer(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException("Required int64: " + field);
        return node.longValue();
    }
}
