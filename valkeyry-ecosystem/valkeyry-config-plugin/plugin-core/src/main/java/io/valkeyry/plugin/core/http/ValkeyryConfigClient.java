package io.valkeyry.plugin.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.valkeyry.plugin.core.auth.AuthStrategy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;

/**
 * Lightweight JDK-HttpClient wrapper used by the build-tool plugins. Reactive on the
 * server side, blocking-but-thread-safe here because a Maven/Gradle build is itself
 * synchronous and we want zero Spring-Boot dependencies on the plugin classpath.
 */
public final class ValkeyryConfigClient {

    private final URI base;
    private final AuthStrategy auth;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ValkeyryConfigClient(URI base, AuthStrategy auth) {
        this.base = base;
        this.auth = auth;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public DeclareResult declareTable(String tenant, String tableName, JsonNode schema) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("tableName", tableName);
        body.set("schema", schema);
        URI uri = base.resolve("/api/v1/tenants/" + tenant + "/tables");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body.toString()));
        auth.apply(req);
        HttpResponse<String> resp = client.send(req.build(), BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Declare failed " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode parsed = mapper.readTree(resp.body());
        return new DeclareResult(parsed.path("id").asText(), parsed.path("configVersion").asLong());
    }

    public BatchResult ingestBatch(String tenant, String tableName, List<EntryRequest> entries)
            throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("entries");
        for (EntryRequest e : entries) {
            ObjectNode entryNode = mapper.createObjectNode();
            entryNode.put("recordKey", e.recordKey());
            entryNode.set("data", e.data());
            arr.add(entryNode);
        }
        URI uri = base.resolve("/api/v1/tenants/" + tenant + "/tables/" + tableName + "/entries:batch");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body.toString()));
        auth.apply(req);
        HttpResponse<String> resp = client.send(req.build(), BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Batch ingest failed " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode parsed = mapper.readTree(resp.body());
        return new BatchResult(
                parsed.path("submitted").asInt(),
                parsed.path("inserted").asInt(),
                parsed.path("duplicates").asInt());
    }

    public record EntryRequest(String recordKey, JsonNode data) {}
    public record DeclareResult(String id, long configVersion) {}
    public record BatchResult(int submitted, int inserted, int duplicates) {}
}
