package dev.gearreserve;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthSmokeTest {
    @TempDir
    Path tempDirectory;

    @Test
    void healthEndpointReturnsOk() throws Exception {
        HttpServer server = App.createServer(0, tempDirectory.resolve("health-test.db"));
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("{\"status\":\"ok\"}", response.body());
        } finally {
            server.stop(0);
        }
    }
}
