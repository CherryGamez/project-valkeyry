package io.valkeyry.plugin.core;

import com.sun.net.httpserver.HttpServer;
import io.valkeyry.plugin.core.http.BatchEntryError;
import io.valkeyry.plugin.core.http.ValkeyryConfigApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the build-tool plugin correctly surfaces per-row failures returned by the server's
 * new {@code BatchIngestResponse.errors[]} contract — and that it ties each failure back to
 * the JSON file the entry was loaded from. Without {@code sourceFile} attribution, a CI
 * developer would have to count rows in their data folder by hand to identify the offender.
 */
class PluginEngineBulkErrorTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger configVersion = new AtomicInteger(1);
        server.createContext("/api/v1/tenants/demo/tables", exchange -> {
            byte[] resp = ("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"configVersion\":"
                    + configVersion.getAndIncrement() + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        // Returns a 207 multi-status: row 1 inserted, row 2 schema-violation.
        server.createContext("/api/v1/tenants/demo/tables/customers/entries:batch", exchange -> {
            String body = """
                    {
                      "submitted": 2,
                      "inserted": 1,
                      "duplicates": 0,
                      "failed": 1,
                      "results": [],
                      "errors": [
                        {
                          "index": 1,
                          "recordKey": "broken-row",
                          "status": "failed",
                          "errorType": "schema-violation",
                          "httpStatus": 422,
                          "message": "Schema validation failed for row 1 (recordKey=broken-row)",
                          "violations": [
                            "$.email: must be a valid email",
                            "$.age: must be >= 0"
                          ]
                        }
                      ]
                    }""";
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(207, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void partialFailureReportsExactFileAndRowAndViolations(@TempDir Path dir) throws Exception {
        Path schemaDir = Files.createDirectories(dir.resolve("schemas"));
        Files.writeString(schemaDir.resolve("customers.schema.json"),
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}");

        Path entriesDir = Files.createDirectories(dir.resolve("data").resolve("customers"));
        Files.writeString(entriesDir.resolve("01-good.json"),   "{\"id\":\"good-row\",\"tier\":\"gold\"}");
        Files.writeString(entriesDir.resolve("02-broken.json"), "{\"id\":\"broken-row\",\"tier\":\"silver\"}");

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

        TestLog log = new TestLog();
        PluginResult res = PluginEngine.run(new PluginContext(manifest, dir, log));

        assertThat(res.submitted()).isEqualTo(2);
        assertThat(res.inserted()).isEqualTo(1);
        assertThat(res.duplicates()).isZero();
        assertThat(res.failed()).isEqualTo(1);
        assertThat(res.hasFailures()).isTrue();

        assertThat(res.failures()).hasSize(1);
        BatchEntryError f = res.failures().get(0);
        assertThat(f.index()).isEqualTo(1);
        assertThat(f.recordKey()).isEqualTo("broken-row");
        assertThat(f.errorType()).isEqualTo("schema-violation");
        assertThat(f.httpStatus()).isEqualTo(422);
        assertThat(f.violations()).contains("$.email: must be a valid email", "$.age: must be >= 0");
        // The plugin must attribute the failure to the originating JSON file.
        assertThat(f.sourceFile()).endsWith("02-broken.json");

        // The plugin must log the failing file as an error line for the build console.
        assertThat(log.errors()).anyMatch(line ->
                line.contains("02-broken.json") && line.contains("schema-violation"));
    }

    @Test
    void allRowsFailedRaisesStructuredException(@TempDir Path dir) throws Exception {
        // Replace the previous batch handler with one that returns 422 + every row failed.
        server.removeContext("/api/v1/tenants/demo/tables/customers/entries:batch");
        server.createContext("/api/v1/tenants/demo/tables/customers/entries:batch", exchange -> {
            String body = """
                    {
                      "submitted": 1,
                      "inserted": 0,
                      "duplicates": 0,
                      "failed": 1,
                      "results": [],
                      "errors": [
                        {
                          "index": 0,
                          "recordKey": "boom",
                          "status": "failed",
                          "errorType": "internal",
                          "httpStatus": 500,
                          "message": "NullPointerException: oops — search server log for traceId=abc-123",
                          "traceId": "abc-123"
                        }
                      ]
                    }""";
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        Path schemaDir = Files.createDirectories(dir.resolve("schemas"));
        Files.writeString(schemaDir.resolve("customers.schema.json"),
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}");
        Path entriesDir = Files.createDirectories(dir.resolve("data").resolve("customers"));
        Files.writeString(entriesDir.resolve("01-boom.json"), "{\"id\":\"boom\",\"tier\":\"x\"}");

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

        assertThatThrownBy(() -> PluginEngine.run(new PluginContext(manifest, dir, new TestLog())))
                .isInstanceOf(ValkeyryConfigApiException.class)
                .satisfies(t -> {
                    ValkeyryConfigApiException ae = (ValkeyryConfigApiException) t;
                    assertThat(ae.statusCode()).isEqualTo(422);
                    assertThat(ae.failures()).hasSize(1);
                    assertThat(ae.failures().get(0).traceId()).isEqualTo("abc-123");
                    assertThat(ae.failures().get(0).sourceFile()).endsWith("01-boom.json");
                    // The message must include the file path so a developer scanning the
                    // build log can navigate straight to it.
                    assertThat(ae.getMessage()).contains("01-boom.json").contains("traceId=abc-123");
                });
    }

    /** In-process collector for {@link PluginLog} calls so the test can introspect them. */
    static final class TestLog implements PluginLog {
        private final java.util.List<String> info = new java.util.ArrayList<>();
        private final java.util.List<String> warn = new java.util.ArrayList<>();
        private final java.util.List<String> error = new java.util.ArrayList<>();
        @Override public void info(String msg)               { info.add(msg); }
        @Override public void warn(String msg)               { warn.add(msg); }
        @Override public void error(String msg, Throwable t) { error.add(msg); }
        java.util.List<String> infos()  { return info; }
        java.util.List<String> warns()  { return warn; }
        java.util.List<String> errors() { return error; }
    }
}
