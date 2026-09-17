package dev.gearreserve;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gearreserve.infrastructure.Database;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public final class App {
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

    private static int readPort() {
        String configured = System.getenv().getOrDefault("GEARRESERVE_PORT", "7070");
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("GEARRESERVE_PORT must be an integer", exception);
        }
    }
}
