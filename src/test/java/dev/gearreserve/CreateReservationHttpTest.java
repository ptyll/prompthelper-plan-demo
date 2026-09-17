package dev.gearreserve;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateReservationHttpTest {
    @TempDir
    Path tempDirectory;

    private HttpServer server;
    private HttpClient client;
    private URI endpoint;

    @BeforeEach
    void startServer() throws Exception {
        server = App.createServer(0, tempDirectory.resolve("http.db"));
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/reservations");
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void equipmentWithoutApprovalCreatesApprovedReservationAndPreservesUtc() throws Exception {
        var response = post("""
                {"equipmentId":1,"requesterAlias":"demo-alfa","startUtc":"2026-10-01T08:00:00Z","endUtc":"2026-10-01T09:00:00Z"}
                """);

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"Approved\""));
        assertTrue(response.body().contains("\"startUtc\":\"2026-10-01T08:00:00Z\""));
        assertTrue(response.body().contains("\"endUtc\":\"2026-10-01T09:00:00Z\""));
    }

    @Test
    void equipmentRequiringApprovalCreatesPendingReservation() throws Exception {
        var response = post("""
                {"equipmentId":2,"requesterAlias":"demo-beta","startUtc":"2026-10-02T08:00:00Z","endUtc":"2026-10-02T09:00:00Z"}
                """);

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"Pending\""));
    }

    @Test
    void unknownEquipmentReturnsNotFound() throws Exception {
        var response = post("""
                {"equipmentId":999,"requesterAlias":"demo-gama","startUtc":"2026-10-03T08:00:00Z","endUtc":"2026-10-03T09:00:00Z"}
                """);

        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"equipment_not_found\"}", response.body());
    }

    @Test
    void invalidIntervalReturnsBadRequest() throws Exception {
        var response = post("""
                {"equipmentId":1,"requesterAlias":"demo-delta","startUtc":"2026-10-04T09:00:00Z","endUtc":"2026-10-04T08:00:00Z"}
                """);

        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"invalid_reservation\"}", response.body());
    }

    private HttpResponse<String> post(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
