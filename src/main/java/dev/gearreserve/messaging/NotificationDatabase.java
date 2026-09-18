package dev.gearreserve.messaging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable inbox and demonstration side effect in one transaction. No external notifications. */
public final class NotificationDatabase {
    private final Path file;
    public NotificationDatabase(Path file) { this.file = file.toAbsolutePath(); }
    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try {
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    public void initialize() throws Exception {
        Files.createDirectories(file.getParent());
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS notifications (
                      event_id TEXT NOT NULL PRIMARY KEY,
                      payload TEXT NOT NULL,
                      notification_text TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )
                    """);
        }
    }
    /** Returns true for a new notification; identical normalized contracts are harmless replays. */
    public boolean store(ReservationApproved event) throws SQLException {
        String payload = ReservationApprovedCodec.encode(event);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications(event_id,payload,notification_text,created_at)
                        VALUES(?,?,?,?) ON CONFLICT(event_id) DO NOTHING
                        """)) {
                    statement.setString(1, event.eventId().toString());
                    statement.setString(2, payload);
                    statement.setString(3, "Reservation " + event.reservationId() + " approved for " + event.requesterAlias());
                    statement.setString(4, Instant.now().toString());
                    inserted = statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT payload FROM notifications WHERE event_id=?")) {
                    statement.setString(1, event.eventId().toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !event.equals(ReservationApprovedCodec.decode(result.getString(1)))) {
                            throw new IllegalStateException("Conflicting ReservationApproved eventId: " + event.eventId());
                        }
                    }
                }
                connection.commit();
                return inserted == 1;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    public record Notification(String eventId, String payload, String text, String createdAt) { }
    public List<Notification> list() throws SQLException {
        List<Notification> notifications = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT event_id,payload,notification_text,created_at FROM notifications ORDER BY created_at,event_id")) {
            while (result.next()) notifications.add(new Notification(result.getString(1), result.getString(2), result.getString(3), result.getString(4)));
        }
        return List.copyOf(notifications);
    }
}
