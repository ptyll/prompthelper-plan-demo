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

class ListReservationsHttpTest {
    @TempDir
    Path tempDirectory;

    private HttpServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        Path databasePath = tempDirectory.resolve("list.db");
        Database database = new Database(databasePath);
        database.initialize();
        database.createReservation(2, "demo-prvni", Instant.parse("2026-10-06T08:00:00Z"),
                Instant.parse("2026-10-06T09:00:00Z"), ReservationStatus.Pending);
        database.createReservation(1, "demo-druhy", Instant.parse("2026-10-07T08:00:00Z"),
                Instant.parse("2026-10-07T09:00:00Z"), ReservationStatus.Approved);
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
    void unfilteredListReturnsAllReservationsInIdOrder() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/reservations")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        int first = response.body().indexOf("\"requesterAlias\":\"demo-prvni\"");
        int second = response.body().indexOf("\"requesterAlias\":\"demo-druhy\"");
        assertTrue(first >= 0 && second > first, response.body());
    }
}
