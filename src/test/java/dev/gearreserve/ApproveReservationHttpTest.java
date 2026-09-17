package dev.gearreserve;

import com.sun.net.httpserver.HttpServer;
import dev.gearreserve.domain.ReservationStatus;
import dev.gearreserve.infrastructure.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApproveReservationHttpTest {
    @TempDir
    Path tempDirectory;

    private Path databasePath;
    private HttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        databasePath = tempDirectory.resolve("approve.db");
        server = App.createServer(0, databasePath);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void pendingReservationBecomesApproved() throws Exception {
        long id = create(ReservationStatus.Pending);

        var response = approve(id);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"Approved\""));
        assertEquals(ReservationStatus.Approved, new Database(databasePath).findReservation(id).orElseThrow().status());
    }

    @Test
    void repeatedApprovalIsIdempotentAndDoesNotCreateAnotherReservation() throws Exception {
        Database database = new Database(databasePath);
        long id = create(ReservationStatus.Pending);

        var first = approve(id);
        var second = approve(id);

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(first.body(), second.body());
        assertEquals(1, database.listReservations().size());
    }

    @Test
    void unknownReservationReturnsNotFound() throws Exception {
        var response = approve(999);

        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"reservation_not_found\"}", response.body());
    }

    private long create(ReservationStatus status) throws Exception {
        Database database = new Database(databasePath);
        return database.createReservation(2, "demo-schvalovatel",
                Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T09:00:00Z"), status).id();
    }

    private HttpResponse<String> approve(long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/reservations/" + id + "/approve"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
