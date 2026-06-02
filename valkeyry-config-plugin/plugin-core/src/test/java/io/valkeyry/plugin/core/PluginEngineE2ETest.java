package io.valkeyry.plugin.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end {@link PluginEngine} test.
 *
 * <p>Spins up a tiny in-process HTTP server emulating just the two endpoints the plugin
 * calls (<code>POST /api/v1/tenants/{tenant}/tables</code> and
 * <code>POST /api/v1/tenants/{tenant}/tables/{name}/entries:batch</code>), writes a real
 * <code>valkeyry-config.yaml</code> on disk, and exercises every supported plugin shape:</p>
 *
 * <ol>
 *   <li><b>Single table, file-based schema</b> — the classic flow.</li>
 *   <li><b>Single table, <code>schemaInline:</code></b> — verifies the inline-YAML schema
 *       reaches the server byte-identical, complete with dropdown/checkbox/multi-choice.</li>
 *   <li><b>Multi-table manifest</b> — three tables in one push, mixing both schema styles.</li>
 *   <li><b>Idempotency-skip</b> — server returns inserted&lt;submitted, plugin reports it cleanly.</li>
 * </ol>
 *
 * <p>The mock server records every request so the assertions can verify auth headers,
 * payload shapes and call ordering. No real network sockets cross the test boundary.</p>
 */
class PluginEngineE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    /** All requests received, in order, so tests can assert on ordering + payload shape. */
    private final List<RecordedRequest> received = new ArrayList<>();
    /** Server-side "stored" data per table, used to simulate the idempotency dedup behaviour. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> storage = new ConcurrentHashMap<>();
    private final AtomicInteger configVersion = new AtomicInteger(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::route);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        received.clear();
        storage.clear();
        configVersion.set(1);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Test 1 — file-based schema, the classic flow
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void file_based_schema_end_to_end(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("schemas"));
        Files.createDirectories(project.resolve("data"));
        Files.writeString(project.resolve("schemas/feature_flags.schema.json"), """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "required": ["key", "enabled"],
                  "properties": {
                    "key":     { "type": "string" },
                    "enabled": { "type": "boolean" }
                  }
                }
                """);
        Files.writeString(project.resolve("data/checkout.json"), """
                { "recordKey": "checkout.v2", "data": { "key": "checkout.v2", "enabled": true } }
                """);
        Files.writeString(project.resolve("data/dark-mode.json"), """
                { "recordKey": "dark.mode", "data": { "key": "dark.mode", "enabled": false } }
                """);
        Files.writeString(project.resolve("valkeyry-config.yaml"),
                "endpoint: http://127.0.0.1:" + port + "\n" +
                "tenant: demo-tenant\n" +
                "auth: { type: api-key, apiKey: plugin-test-key }\n" +
                "tables:\n" +
                "  - name: feature_flags\n" +
                "    schema: schemas/feature_flags.schema.json\n" +
                "    entries: data/*.json\n");

        PluginResult result = PluginEngine.run(new TestContext(project).ctx());

        assertThat(result.tablesDeclared()).containsExactly("feature_flags");
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.duplicates()).isZero();

        // Two requests: 1× declare, 1× batch ingest
        assertThat(received).hasSize(2);
        assertThat(received.get(0).path).isEqualTo("/api/v1/tenants/demo-tenant/tables");
        assertThat(received.get(0).headers.get("X-API-Key")).contains("plugin-test-key");
        assertThat(received.get(1).path).isEqualTo("/api/v1/tenants/demo-tenant/tables/feature_flags/entries:batch");
        JsonNode batchBody = MAPPER.readTree(received.get(1).body);
        assertThat(batchBody.path("entries").size()).isEqualTo(2);
        assertThat(batchBody.path("entries").get(0).path("recordKey").asText()).isIn("checkout.v2", "dark.mode");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Test 2 — inline schema (all UI widget types)
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void inline_schema_end_to_end(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("data"));
        Files.writeString(project.resolve("data/abc.json"), """
                { "recordKey": "ABC-1001", "data": {
                    "sku": "ABC-1001", "name": "Hoodie", "price": 49.99,
                    "category": "apparel", "tags": ["new", "sale"], "inStock": true } }
                """);
        Files.writeString(project.resolve("valkeyry-config.yaml"), """
                endpoint: http://127.0.0.1:%d
                tenant: demo-tenant
                auth: { type: api-key, apiKey: plugin-test-key }
                tables:
                  - name: products
                    schemaInline:
                      type: object
                      required: [sku, price, category]
                      properties:
                        sku:      { type: string, pattern: "^[A-Z]{3}-\\\\d{4}$" }
                        name:     { type: string }
                        price:    { type: number, minimum: 0, maximum: 999999.99 }
                        category: { type: string, enum: [apparel, electronics, books] }
                        tags:
                          type: array
                          uniqueItems: true
                          items: { type: string, enum: [new, sale, clearance] }
                        inStock:  { type: boolean, default: true }
                    entries: data/*.json
                """.formatted(port));

        PluginResult result = PluginEngine.run(new TestContext(project).ctx());
        assertThat(result.inserted()).isEqualTo(1);

        // Verify the schema was uploaded with every widget type preserved
        JsonNode declareBody = MAPPER.readTree(received.get(0).body);
        JsonNode schema = declareBody.path("schema");
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("sku", "price", "category");
        // Dropdown (string + enum)
        assertThat(schema.path("properties").path("category").path("enum"))
                .extracting(JsonNode::asText).containsExactly("apparel", "electronics", "books");
        // Multi-choice (array + items.enum)
        assertThat(schema.path("properties").path("tags").path("items").path("enum"))
                .extracting(JsonNode::asText).containsExactly("new", "sale", "clearance");
        // Checkbox (boolean) with default
        assertThat(schema.path("properties").path("inStock").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("properties").path("inStock").path("default").asBoolean()).isTrue();
        // Numeric range
        assertThat(schema.path("properties").path("price").path("minimum").asDouble()).isEqualTo(0.0);
        assertThat(schema.path("properties").path("price").path("maximum").asDouble()).isEqualTo(999999.99);
        // Regex pattern survived the YAML→JSON-Schema round-trip
        assertThat(schema.path("properties").path("sku").path("pattern").asText()).isEqualTo("^[A-Z]{3}-\\d{4}$");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Test 3 — multi-table manifest, mixed schema styles
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void multi_table_manifest(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("schemas"));
        Files.createDirectories(project.resolve("data/customers"));
        Files.createDirectories(project.resolve("data/flags"));
        Files.writeString(project.resolve("schemas/customers.schema.json"), """
                { "type": "object", "required": ["email"], "properties": { "email": { "type": "string" } } }""");
        Files.writeString(project.resolve("data/customers/alice.json"), """
                {"recordKey":"alice","data":{"email":"alice@acme.io"}}""");
        Files.writeString(project.resolve("data/customers/bob.json"), """
                {"recordKey":"bob","data":{"email":"bob@acme.io"}}""");
        Files.writeString(project.resolve("data/flags/dark.json"), """
                {"recordKey":"dark.mode","data":{"key":"dark.mode","enabled":true}}""");
        Files.writeString(project.resolve("valkeyry-config.yaml"), """
                endpoint: http://127.0.0.1:%d
                tenant: demo-tenant
                auth: { type: api-key, apiKey: plugin-test-key }
                tables:
                  - name: customers
                    schema: schemas/customers.schema.json
                    entries: data/customers/*.json
                  - name: feature_flags
                    schemaInline:
                      type: object
                      properties:
                        key:     { type: string }
                        enabled: { type: boolean }
                    entries: data/flags/*.json
                """.formatted(port));

        PluginResult result = PluginEngine.run(new TestContext(project).ctx());

        assertThat(result.tablesDeclared()).containsExactly("customers", "feature_flags");
        assertThat(result.submitted()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(3);
        // 2 declares + 2 batches
        assertThat(received).hasSize(4);
        assertThat(received).extracting(r -> r.path).containsExactly(
                "/api/v1/tenants/demo-tenant/tables",
                "/api/v1/tenants/demo-tenant/tables/customers/entries:batch",
                "/api/v1/tenants/demo-tenant/tables",
                "/api/v1/tenants/demo-tenant/tables/feature_flags/entries:batch");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Test 4 — idempotency-skip reported back to the build log
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void idempotency_skip(@TempDir Path project) throws Exception {
        // Pre-populate the mock storage so the first ingest reports a duplicate.
        storage.computeIfAbsent("products", k -> new ConcurrentHashMap<>()).put("ABC-1001", "preexisting");

        Files.createDirectories(project.resolve("data"));
        Files.writeString(project.resolve("data/x.json"), """
                {"recordKey":"ABC-1001","data":{"sku":"ABC-1001"}}""");
        Files.writeString(project.resolve("data/y.json"), """
                {"recordKey":"ABC-1002","data":{"sku":"ABC-1002"}}""");
        Files.writeString(project.resolve("valkeyry-config.yaml"), """
                endpoint: http://127.0.0.1:%d
                tenant: demo-tenant
                auth: { type: api-key, apiKey: plugin-test-key }
                tables:
                  - name: products
                    schemaInline: { type: object, properties: { sku: { type: string } } }
                    entries: data/*.json
                """.formatted(port));

        PluginResult result = PluginEngine.run(new TestContext(project).ctx());
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(1);    // only ABC-1002 was new
        assertThat(result.duplicates()).isEqualTo(1);  // ABC-1001 was pre-existing
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Mock backend
    // ─────────────────────────────────────────────────────────────────────
    private void route(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        RecordedRequest r = new RecordedRequest();
        r.method = ex.getRequestMethod();
        r.path = ex.getRequestURI().getPath();
        r.body = new String(body, StandardCharsets.UTF_8);
        ex.getRequestHeaders().forEach((k, v) -> r.headers.put(k, String.join(",", v)));
        received.add(r);

        if (r.path.endsWith("/entries:batch") && r.method.equals("POST")) {
            String table = extractTable(r.path);
            JsonNode payload = MAPPER.readTree(body);
            int submitted = 0, inserted = 0, dups = 0;
            var bucket = storage.computeIfAbsent(table, k -> new ConcurrentHashMap<>());
            var inserted_list = MAPPER.createArrayNode();
            for (JsonNode e : payload.path("entries")) {
                submitted++;
                String key = e.path("recordKey").asText();
                if (bucket.containsKey(key)) { dups++; }
                else {
                    bucket.put(key, e.path("data").toString());
                    inserted++;
                    var v = MAPPER.createObjectNode();
                    v.put("recordKey", key);
                    v.put("version", 1);
                    inserted_list.add(v);
                }
            }
            var resp = MAPPER.createObjectNode();
            resp.put("submitted", submitted);
            resp.put("inserted", inserted);
            resp.put("duplicates", dups);
            resp.set("inserted_views", inserted_list);
            send(ex, 201, resp.toString());
            return;
        }
        if (r.path.endsWith("/tables") && r.method.equals("POST")) {
            JsonNode payload = MAPPER.readTree(body);
            var resp = MAPPER.createObjectNode();
            resp.put("id", java.util.UUID.randomUUID().toString());
            resp.put("tableName", payload.path("tableName").asText());
            resp.put("configVersion", configVersion.getAndIncrement());
            resp.set("schema", payload.path("schema"));
            send(ex, 201, resp.toString());
            return;
        }
        send(ex, 404, "{}");
    }

    private static String extractTable(String path) {
        // /api/v1/tenants/{tenant}/tables/{name}/entries:batch
        String[] parts = path.split("/");
        return parts[parts.length - 2];
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static final class RecordedRequest {
        String method;
        String path;
        String body;
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Minimal PluginContext implementation for the test
    // ─────────────────────────────────────────────────────────────────────
    private static final class TestContext {
        private final Path baseDir;
        TestContext(Path baseDir) { this.baseDir = baseDir; }
        PluginContext ctx() {
            return new PluginContext(baseDir.resolve("valkeyry-config.yaml"), baseDir, new TestLog());
        }
    }

    private static final class TestLog implements PluginLog {
        final List<String> lines = new ArrayList<>();
        @Override public void info(String msg)                        { lines.add("[INFO] " + msg); }
        @Override public void warn(String msg)                        { lines.add("[WARN] " + msg); }
        @Override public void error(String msg, Throwable t)          { lines.add("[ERROR] " + msg + " :: " + t); }
    }
}
