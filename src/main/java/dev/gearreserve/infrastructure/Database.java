package dev.gearreserve.infrastructure;

import dev.gearreserve.domain.Equipment;

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

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
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
