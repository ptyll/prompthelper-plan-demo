package dev.gearreserve.infrastructure;

import dev.gearreserve.domain.Equipment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
    @TempDir
    Path tempDirectory;

    @Test
    void initializeCreatesDeterministicSeedWithoutDeletingExistingRows() throws Exception {
        Path path = tempDirectory.resolve("seed-test.db");
        Database database = new Database(path);
        database.initialize();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.prepareStatement(
                     "INSERT INTO equipment(id, name, requires_approval) VALUES (99, 'Vlastní testovací položka', 0)")) {
            statement.executeUpdate();
        }

        database.initialize();
        List<Equipment> equipment = database.listEquipment();

        assertEquals(4, equipment.size());
        assertEquals(List.of(1L, 2L, 3L, 99L), equipment.stream().map(Equipment::id).toList());
        assertEquals(1, equipment.stream().filter(Equipment::requiresApproval).count());
        assertTrue(equipment.stream().anyMatch(item -> item.name().equals("Vlastní testovací položka")));
    }
}
