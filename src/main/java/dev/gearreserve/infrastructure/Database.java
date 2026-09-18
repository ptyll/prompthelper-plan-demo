package dev.gearreserve.infrastructure;

import dev.gearreserve.domain.Equipment;
import dev.gearreserve.domain.Reservation;
import dev.gearreserve.domain.ReservationStatus;
import dev.gearreserve.messaging.ReservationApproved;
import dev.gearreserve.messaging.ReservationApprovedCodec;
import java.util.UUID;

import java.time.Instant;
import java.util.Optional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class Database {
    private static final List<Equipment> SEED_EQUIPMENT = List.of(
            new Equipment(1, "USB-C projektor", false),
            new Equipment(2, "Termokamera", true),
            new Equipment(3, "Sada bezdrátových mikrofonů", false)
    );

    private final String jdbcUrl;

    public Database(Path databasePath) {
        Path absolutePath = databasePath.toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + absolutePath;
    }

    public void initialize() throws IOException {
        Path databasePath = Path.of(jdbcUrl.substring("jdbc:sqlite:".length()));
        Path parent = databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Connection connection = open()) {
            createSchema(connection);
            insertSeedWithoutDeleting(connection);
        } catch (SQLException exception) {
            throw new IOException("Could not initialize the GearReserve database", exception);
        }
    }

    public List<Equipment> listEquipment() throws SQLException {
        String sql = "SELECT id, name, requires_approval FROM equipment ORDER BY id";
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<Equipment> equipment = new ArrayList<>();
            while (result.next()) {
                equipment.add(new Equipment(
                        result.getLong("id"),
                        result.getString("name"),
                        result.getBoolean("requires_approval")
                ));
            }
            return List.copyOf(equipment);
        }
    }

    public Optional<Equipment> findEquipment(long id) throws SQLException {
        String sql = "SELECT id, name, requires_approval FROM equipment WHERE id = ?";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new Equipment(result.getLong("id"), result.getString("name"),
                        result.getBoolean("requires_approval")))
                        : Optional.empty();
            }
        }
    }

    public Reservation createReservation(long equipmentId, String requesterAlias, Instant startUtc,
                                         Instant endUtc, ReservationStatus status) throws SQLException {
        Reservation candidate = new Reservation(0, equipmentId, requesterAlias, startUtc, endUtc, status);
        String sql = """
                INSERT INTO reservations(equipment_id, requester_alias, start_utc, end_utc, status)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            connection.setAutoCommit(false);
            try {
            statement.setLong(1, candidate.equipmentId());
            statement.setString(2, candidate.requesterAlias());
            statement.setString(3, candidate.startUtc().toString());
            statement.setString(4, candidate.endUtc().toString());
            statement.setString(5, candidate.status().name());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("SQLite did not return a reservation id");
                }
                Reservation created = new Reservation(keys.getLong(1), candidate.equipmentId(), candidate.requesterAlias(),
                        candidate.startUtc(), candidate.endUtc(), candidate.status());
                if (created.status() == ReservationStatus.Approved) insertApprovalEvent(connection, created);
                connection.commit();
                return created;
            }
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<Reservation> findReservation(long id) throws SQLException {
        try (Connection connection = open()) {
            return findReservation(connection, id);
        }
    }

    private Optional<Reservation> findReservation(Connection connection, long id) throws SQLException {
        String sql = "SELECT id, equipment_id, requester_alias, start_utc, end_utc, status FROM reservations WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readReservation(result)) : Optional.empty();
            }
        }
    }

    public Optional<Reservation> approveReservation(long id) throws SQLException {
        String sql = "UPDATE reservations SET status = 'Approved' WHERE id = ? AND status = 'Pending'";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            try {
                statement.setLong(1, id);
                int changed = statement.executeUpdate(); // write first: avoid deferred read-to-write lock upgrades
                Optional<Reservation> result = findReservation(connection, id);
                if (changed == 1) insertApprovalEvent(connection, result.orElseThrow());
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    public List<Reservation> listReservations() throws SQLException {
        String sql = """
                SELECT id, equipment_id, requester_alias, start_utc, end_utc, status
                FROM reservations ORDER BY id
                """;
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return readReservations(result);
        }
    }

    public List<Reservation> listReservations(ReservationStatus status) throws SQLException {
        String sql = """
                SELECT id, equipment_id, requester_alias, start_utc, end_utc, status
                FROM reservations WHERE status = ? ORDER BY id
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            try (ResultSet result = statement.executeQuery()) {
                return readReservations(result);
            }
        }
    }

    private static List<Reservation> readReservations(ResultSet result) throws SQLException {
        List<Reservation> reservations = new ArrayList<>();
        while (result.next()) {
            reservations.add(readReservation(result));
        }
        return List.copyOf(reservations);
    }

    private static Reservation readReservation(ResultSet result) throws SQLException {
        return new Reservation(
                result.getLong("id"),
                result.getLong("equipment_id"),
                result.getString("requester_alias"),
                Instant.parse(result.getString("start_utc")),
                Instant.parse(result.getString("end_utc")),
                ReservationStatus.valueOf(result.getString("status"))
        );
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { original.addSuppressed(rollbackFailure); }
    }

    private static void insertApprovalEvent(Connection connection, Reservation reservation) throws SQLException {
        ReservationApproved event = new ReservationApproved(UUID.randomUUID(), Instant.now(), reservation.id(),
                reservation.equipmentId(), reservation.requesterAlias());
        String payload = ReservationApprovedCodec.encode(event);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO outbox(event_id, reservation_id, event_type, payload) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, event.eventId().toString());
            insert.setLong(2, event.reservationId());
            insert.setString(3, ReservationApproved.EVENT_TYPE);
            insert.setString(4, payload);
            insert.executeUpdate();
        }
    }

    public record OutboxMessage(long id, String eventId, long reservationId, String payload) {
        public String key() { return Long.toString(reservationId); }
    }

    /** Reads a bounded snapshot and closes the connection before any network send. */
    public List<OutboxMessage> pendingOutbox(int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT id,event_id,reservation_id,payload FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ?")) {
            query.setInt(1, limit);
            try (ResultSet rows = query.executeQuery()) {
                List<OutboxMessage> result = new ArrayList<>();
                while (rows.next()) result.add(new OutboxMessage(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getString(4)));
                return List.copyOf(result);
            }
        }
    }

    public void markPublished(long id) throws SQLException {
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ? AND published_at IS NULL")) {
            update.setString(1, Instant.now().toString());
            update.setLong(2, id);
            if (update.executeUpdate() != 1) throw new SQLException("Outbox row is missing or already published: " + id);
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL UNIQUE,
                        reservation_id INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        published_at TEXT,
                        UNIQUE(reservation_id,event_type)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TRIGGER IF NOT EXISTS outbox_immutable_payload
                    BEFORE UPDATE OF event_id,reservation_id,event_type,payload ON outbox
                    BEGIN SELECT RAISE(ABORT, 'outbox event is immutable'); END
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS equipment (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        requires_approval INTEGER NOT NULL CHECK (requires_approval IN (0, 1))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reservations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        equipment_id INTEGER NOT NULL,
                        requester_alias TEXT NOT NULL,
                        start_utc TEXT NOT NULL,
                        end_utc TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('Pending', 'Approved')),
                        FOREIGN KEY (equipment_id) REFERENCES equipment(id)
                    )
                    """);
        }
    }

    private static void insertSeedWithoutDeleting(Connection connection) throws SQLException {
        String sql = """
                INSERT INTO equipment(id, name, requires_approval)
                VALUES (?, ?, ?)
                ON CONFLICT(id) DO NOTHING
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Equipment item : SEED_EQUIPMENT) {
                statement.setLong(1, item.id());
                statement.setString(2, item.name());
                statement.setBoolean(3, item.requiresApproval());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
