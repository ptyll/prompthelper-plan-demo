package dev.gearreserve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gearreserve.domain.Reservation;
import dev.gearreserve.domain.ReservationStatus;
import dev.gearreserve.infrastructure.Database;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.Executors;

public final class App {
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private App() {
    }

    public static void main(String[] args) throws IOException {
        int port = readPort();
        Path databasePath = Path.of(System.getenv().getOrDefault("GEARRESERVE_DB", "data/gearreserve.db"));
        HttpServer server = createServer(port, databasePath);
        server.start();
        System.out.printf("GearReserve is running at http://localhost:%d%n", server.getAddress().getPort());
    }

    public static HttpServer createServer(int port, Path databasePath) throws IOException {
        Database database = new Database(databasePath);
        database.initialize();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", App::handleHealth);
        server.createContext("/reservations", exchange -> handleReservations(exchange, database));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void handleReservations(HttpExchange exchange, Database database) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod()) && path.matches("/reservations/\\d+/approve")) {
            approveReservation(exchange, database, path);
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && "/reservations".equals(path)) {
            listReservations(exchange, database, exchange.getRequestURI().getRawQuery());
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod()) || !"/reservations".equals(path)) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        try {
            CreateReservationRequest request = JSON.readValue(exchange.getRequestBody(), CreateReservationRequest.class);
            var equipment = database.findEquipment(request.equipmentId()).orElse(null);
            if (equipment == null) {
                sendJson(exchange, 404, new ApiError("equipment_not_found"));
                return;
            }
            Reservation reservation = database.createReservation(
                    request.equipmentId(),
                    request.requesterAlias(),
                    request.startUtc(),
                    request.endUtc(),
                    equipment.requiresApproval() ? ReservationStatus.Pending : ReservationStatus.Approved
            );
            sendJson(exchange, 201, reservation);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            sendJson(exchange, 400, new ApiError("invalid_reservation"));
        } catch (SQLException exception) {
            throw new IOException("Could not create reservation", exception);
        }
    }

    private static void listReservations(HttpExchange exchange, Database database, String rawQuery) throws IOException {
        ReservationStatus status;
        if (rawQuery == null) {
            status = null;
        } else if ("status=Pending".equals(rawQuery)) {
            status = ReservationStatus.Pending;
        } else if ("status=Approved".equals(rawQuery)) {
            status = ReservationStatus.Approved;
        } else {
            sendJson(exchange, 400, new ApiError("invalid_status"));
            return;
        }

        try {
            sendJson(exchange, 200, status == null
                    ? database.listReservations()
                    : database.listReservations(status));
        } catch (SQLException exception) {
            throw new IOException("Could not list reservations", exception);
        }
    }

    private static void approveReservation(HttpExchange exchange, Database database, String path) throws IOException {
        long reservationId = Long.parseLong(path.substring("/reservations/".length(), path.length() - "/approve".length()));
        try {
            Reservation reservation = database.approveReservation(reservationId).orElse(null);
            if (reservation == null) {
                sendJson(exchange, 404, new ApiError("reservation_not_found"));
                return;
            }
            sendJson(exchange, 200, reservation);
        } catch (SQLException exception) {
            throw new IOException("Could not approve reservation", exception);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private record CreateReservationRequest(long equipmentId, String requesterAlias, Instant startUtc, Instant endUtc) {
    }

    private record ApiError(String error) {
    }

    private static int readPort() {
        String configured = System.getenv().getOrDefault("GEARRESERVE_PORT", "7070");
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("GEARRESERVE_PORT must be an integer", exception);
        }
    }
}
