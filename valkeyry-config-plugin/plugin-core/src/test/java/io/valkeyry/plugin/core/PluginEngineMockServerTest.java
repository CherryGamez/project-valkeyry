package io.valkeyry.plugin.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of {@link PluginEngine} against a hand-rolled mock HTTP server.
 * Verifies: manifest → declare → batch ingest with the correct payloads.
 */
class PluginEngineMockServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final AtomicInteger declareConfigVersion = new AtomicInteger(1);
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/tenants/demo/tables", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("DECLARE:" + body);
            byte[] response = ("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"configVersion\":"
                    + declareConfigVersion.getAndIncrement() + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/tenants/demo/tables/customers/entries:batch", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("BATCH:" + body);
            byte[] response = "{\"submitted\":2,\"inserted\":2,\"duplicates\":0,\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void runDeclaresThenBatchIngestsAllEntries(@TempDir Path dir) throws Exception {
        // Lay out a sample project.
        Path schemaDir = Files.createDirectories(dir.resolve("schemas"));
        Files.writeString(schemaDir.resolve("customers.schema.json"),
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}");

        Path entriesDir = Files.createDirectories(dir.resolve("data").resolve("customers"));
        Files.writeString(entriesDir.resolve("alice.json"), "{\"id\":\"alice\",\"tier\":\"gold\"}");
        Files.writeString(entriesDir.resolve("bob.json"),   "{\"id\":\"bob\",\"tier\":\"silver\"}");

        Path manifest = dir.resolve("valkeyry-config.yaml");
        Files.writeString(manifest, ("""
                endpoint: http://localhost:%d
                tenant: demo
                auth:
                  type: basic
                  username: build-bot
                  password: s3cret
                tables:
                  - name: customers
                    schema: schemas/customers.schema.json
                    entries: data/customers/*.json
                """).formatted(port));

        PluginResult res = PluginEngine.run(new PluginContext(manifest, dir, new TestLog()));

        assertThat(res.submitted()).isEqualTo(2);
        assertThat(res.inserted()).isEqualTo(2);
        assertThat(res.duplicates()).isZero();
        assertThat(res.tablesDeclared()).containsExactly("customers");
        assertThat(requests).anyMatch(s -> s.startsWith("DECLARE:"));
        assertThat(requests).anyMatch(s -> s.startsWith("BATCH:") && s.contains("alice") && s.contains("bob"));
    }

    private static final class TestLog implements PluginLog {
        @Override public void info(String msg) { System.out.println("INFO " + msg); }
        @Override public void warn(String msg) { System.out.println("WARN " + msg); }
        @Override public void error(String msg, Throwable t) { System.err.println("ERR " + msg); }
    }
}
