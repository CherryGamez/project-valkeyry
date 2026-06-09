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
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight JDK-HttpClient wrapper used by the build-tool plugins. Reactive on the
 * server side, blocking-but-thread-safe here because a Maven/Gradle build is itself
 * synchronous and we want zero Spring-Boot dependencies on the plugin classpath.
 *
 * <p>Error handling note: any non-2xx response is decoded as an RFC-7807 ProblemDetail
 * when possible and re-raised as a {@link ValkeyryConfigApiException}. Partial-success
 * responses (HTTP 207) are NOT treated as errors — they're surfaced via
 * {@link BatchResult#failures()} so the caller can decide whether to fail the build.</p>
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
            throw decodeFailure("Declare table '" + tableName + "' failed", resp, List.of());
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

        // 201 (all OK), 207 (partial), and 422 (all failed) all carry a structured body.
        // Any other non-2xx is a hard failure — declare table missing, auth refused, …
        int status = resp.statusCode();
        if (status != 201 && status != 207 && status != 422 && status / 100 != 2) {
            throw decodeFailure("Batch ingest to '" + tableName + "' failed", resp, entries);
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(resp.body());
        } catch (IOException ioe) {
            throw new ValkeyryConfigApiException(status, null,
                    "Server returned HTTP " + status + " but the body was not JSON: "
                            + abbreviate(resp.body(), 200),
                    null, resp.body(), List.of());
        }

        int submitted   = parsed.path("submitted").asInt(entries.size());
        int inserted    = parsed.path("inserted").asInt(0);
        int duplicates  = parsed.path("duplicates").asInt(0);
        int failed      = parsed.path("failed").asInt(0);

        List<BatchEntryError> failures = new ArrayList<>();
        JsonNode errors = parsed.path("errors");
        if (errors.isArray()) {
            for (JsonNode err : errors) {
                int idx = err.path("index").asInt(-1);
                String sourceFile = (idx >= 0 && idx < entries.size())
                        ? entries.get(idx).sourceFile() : null;
                List<String> violations = new ArrayList<>();
                if (err.path("violations").isArray()) {
                    for (JsonNode v : err.path("violations")) violations.add(v.asText());
                }
                failures.add(new BatchEntryError(
                        idx,
                        err.path("recordKey").asText(null),
                        err.path("errorType").asText("unknown"),
                        err.path("httpStatus").asInt(status),
                        err.path("message").asText(""),
                        violations,
                        err.path("traceId").asText(null),
                        sourceFile
                ));
            }
        }

        // 422 means the entire batch failed — turn into an exception so the build fails
        // fast with the structured failure list. 207 (partial) is returned to the caller
        // for them to decide.
        if (status == 422 && inserted == 0 && duplicates == 0) {
            throw new ValkeyryConfigApiException(status,
                    "urn:valkeyry:error:batch-ingest-all-failed",
                    "All " + submitted + " row(s) in batch failed validation for table '"
                            + tableName + "'",
                    null, resp.body(), failures);
        }
        return new BatchResult(submitted, inserted, duplicates, failed, failures);
    }

    /**
     * Decodes a non-2xx response (RFC-7807 ProblemDetail when possible) and packages it
     * into a {@link ValkeyryConfigApiException} carrying every field the caller would
     * want for a developer-friendly error.
     */
    private ValkeyryConfigApiException decodeFailure(String contextLabel, HttpResponse<String> resp,
                                                     List<EntryRequest> entries) {
        int status = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        String type = null, detail = null, traceId = null;
        List<BatchEntryError> failures = new ArrayList<>();
        try {
            JsonNode p = mapper.readTree(body);
            if (p.isObject()) {
                type    = p.path("type").asText(null);
                detail  = p.path("detail").asText(null);
                if (detail == null || detail.isBlank()) detail = p.path("title").asText(null);
                traceId = p.path("traceId").asText(null);
                JsonNode violations = p.path("violations");
                if (violations.isArray() && violations.size() > 0) {
                    List<String> v = new ArrayList<>();
                    for (JsonNode x : violations) v.add(x.asText());
                    // Surface the violations as a single synthetic failure so the message
                    // formatter prints them — no per-row index available here.
                    failures.add(new BatchEntryError(
                            -1, null, "schema-violation", status,
                            detail == null ? "Schema validation failed" : detail,
                            v, traceId, null));
                }
                // Server's batch response may still arrive with status != 207/422 (e.g. via
                // a proxy). Try to pull rows out too.
                JsonNode errs = p.path("errors");
                if (errs.isArray()) {
                    for (JsonNode err : errs) {
                        int idx = err.path("index").asInt(-1);
                        String sourceFile = (idx >= 0 && idx < entries.size())
                                ? entries.get(idx).sourceFile() : null;
                        List<String> vs = new ArrayList<>();
                        if (err.path("violations").isArray()) {
                            for (JsonNode v : err.path("violations")) vs.add(v.asText());
                        }
                        failures.add(new BatchEntryError(
                                idx, err.path("recordKey").asText(null),
                                err.path("errorType").asText("unknown"),
                                err.path("httpStatus").asInt(status),
                                err.path("message").asText(""),
                                vs, err.path("traceId").asText(null), sourceFile));
                    }
                }
            }
        } catch (IOException ignored) {
            // Non-JSON body — fall back to raw.
        }
        if (detail == null || detail.isBlank()) {
            detail = body.isBlank() ? contextLabel : contextLabel + " — " + abbreviate(body, 400);
        }
        return new ValkeyryConfigApiException(status, type, detail, traceId, body, failures);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "… (" + (s.length() - max) + " more chars)";
    }

    /**
     * Single entry to push.
     *
     * <p>{@code sourceFile} is optional — supply it when the entry was loaded from a real
     * file on disk so the plugin can blame the right file when the row fails. Pass
     * {@code null} when the entry came from memory.</p>
     */
    public record EntryRequest(String recordKey, JsonNode data, String sourceFile) {
        public EntryRequest(String recordKey, JsonNode data) { this(recordKey, data, null); }
    }
    public record DeclareResult(String id, long configVersion) {}

    /**
     * Outcome of a single batch ingest call.
     *
     * <p>{@code failed} == {@code failures.size()}. {@code failures} is empty on a clean
     * 201 response and populated on a 207 partial-success response (a 422 turns into a
     * {@link ValkeyryConfigApiException}).</p>
     */
    public record BatchResult(int submitted, int inserted, int duplicates, int failed,
                              List<BatchEntryError> failures) {
        public BatchResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
        /** Pre-v8 ctor — kept for backwards-compat with existing tests. */
        public BatchResult(int submitted, int inserted, int duplicates) {
            this(submitted, inserted, duplicates, 0, List.of());
        }
    }
}
