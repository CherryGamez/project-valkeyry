package io.valkeyry.plugin.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.plugin.core.auth.AuthStrategy;
import io.valkeyry.plugin.core.hash.PayloadFingerprint;
import io.valkeyry.plugin.core.http.BatchEntryError;
import io.valkeyry.plugin.core.http.ValkeyryConfigApiException;
import io.valkeyry.plugin.core.http.ValkeyryConfigClient;
import io.valkeyry.plugin.core.manifest.ManifestLoader;
import io.valkeyry.plugin.core.manifest.PluginManifest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-tool agnostic orchestration. Maven Mojo and Gradle Task both delegate here.
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>load and validate {@code valkeyry-config.yaml}</li>
 *   <li>build {@link AuthStrategy} from the manifest</li>
 *   <li>for each declared table: POST schema then POST entries:batch</li>
 *   <li>each entry payload is pre-fingerprinted (SHA-256, canonical JSON) so that the server
 *       Idempotency Guard short-circuits unchanged payloads</li>
 *   <li>collect every per-row failure the server reports — the source file path travels with
 *       the entry through the HTTP client so the final report can point the user at the
 *       exact JSON file to fix</li>
 * </ol>
 *
 * <p>Error semantics: this method returns normally on a 201 (all OK) or 207 (partial)
 * response — the caller is expected to inspect {@link PluginResult#hasFailures()} and decide
 * whether to fail the build. A 422 (every row failed) or any other non-2xx surfaces as
 * {@link ValkeyryConfigApiException} carrying the structured failure list.</p>
 */
public final class PluginEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PluginEngine() {}

    public static PluginResult run(PluginContext ctx) throws IOException, InterruptedException {
        PluginManifest manifest = ManifestLoader.load(ctx.manifestPath());
        ctx.log().info("Loaded manifest with " + manifest.getTables().size() + " table(s) → " + manifest.getEndpoint());

        ValkeyryConfigClient client = new ValkeyryConfigClient(
                URI.create(manifest.getEndpoint()),
                AuthStrategy.fromManifest(manifest.getAuth()));

        Path manifestDir = ctx.manifestPath().toAbsolutePath().getParent();
        if (manifestDir == null) manifestDir = ctx.projectBaseDir();

        int submitted = 0, inserted = 0, duplicates = 0, failed = 0;
        List<String> declared = new ArrayList<>();
        List<BatchEntryError> allFailures = new ArrayList<>();

        for (PluginManifest.TableSpec table : manifest.getTables()) {
            ctx.log().info("· " + table.getName() + ": declaring schema…");
            JsonNode schema;
            try {
                schema = table.hasInlineSchema()
                        ? MAPPER.valueToTree(table.getSchemaInline())
                        : MAPPER.readTree(manifestDir.resolve(table.getSchema()).toFile());
            } catch (IOException ioe) {
                throw new IOException("Failed to read schema for table '" + table.getName()
                        + "' from '" + (table.hasInlineSchema() ? "<inline>" : table.getSchema())
                        + "': " + ioe.getMessage(), ioe);
            }
            ValkeyryConfigClient.DeclareResult decl;
            try {
                decl = client.declareTable(manifest.getTenant(), table.getName(), schema);
            } catch (ValkeyryConfigApiException ae) {
                ctx.log().error("  ✗ " + table.getName() + ": declare-schema failed — " + ae.getMessage(), ae);
                throw ae;
            }
            ctx.log().info("  → registered id=" + decl.id() + " configVersion=" + decl.configVersion());
            declared.add(table.getName());

            List<Path> entryFiles = expandGlob(manifestDir, table.getEntries());
            if (entryFiles.isEmpty()) {
                ctx.log().warn("  no entry files matched " + table.getEntries());
                continue;
            }

            List<ValkeyryConfigClient.EntryRequest> requests = new ArrayList<>();
            for (Path entryFile : entryFiles) {
                JsonNode raw;
                try {
                    raw = MAPPER.readTree(entryFile.toFile());
                } catch (IOException ioe) {
                    // Parser errors come from Jackson with line / column info — keep that
                    // verbatim so the user can navigate straight to it.
                    throw new IOException("Failed to parse entry JSON '" + entryFile + "': "
                            + ioe.getMessage(), ioe);
                }
                // Two supported entry shapes:
                //   (a) Envelope: { "recordKey": "...", "data": { ...payload... } }  ← preferred,
                //                                                                       used by all examples.
                //   (b) Flat:     { "id": "...", ...payload... }  ← legacy; recordKey from `id` or filename.
                JsonNode payload;
                String recordKey;
                if (isEnvelope(raw)) {
                    recordKey = raw.path("recordKey").asText();
                    payload = raw.path("data");
                } else {
                    recordKey = readRecordKey(raw, entryFile);
                    payload = raw;
                }
                // Fingerprint is computed but not sent — the server will recompute identically.
                // Local computation surfaces obvious bugs (e.g. mis-encoded files) before the wire trip.
                try {
                    PayloadFingerprint.sha256(payload);
                } catch (RuntimeException rex) {
                    throw new IOException("Failed to canonicalise entry JSON '" + entryFile
                            + "' (recordKey=" + recordKey + "): " + rex.getMessage(), rex);
                }
                requests.add(new ValkeyryConfigClient.EntryRequest(recordKey, payload, entryFile.toString()));
            }

            ValkeyryConfigClient.BatchResult res;
            try {
                res = client.ingestBatch(manifest.getTenant(), table.getName(), requests);
            } catch (ValkeyryConfigApiException ae) {
                // 422 + the server's structured per-row error list. Print one line per
                // failing file BEFORE re-throwing so the user sees the report even if
                // their build harness swallows the exception's toString().
                ctx.log().error("  ✗ " + table.getName() + ": every row failed validation", ae);
                for (BatchEntryError e : ae.failures()) {
                    ctx.log().error("    " + e.formatOneLine(), null);
                }
                throw ae;
            }

            ctx.log().info("  → submitted=" + res.submitted()
                    + " inserted=" + res.inserted()
                    + " duplicates=" + res.duplicates()
                    + " failed=" + res.failed());
            for (BatchEntryError e : res.failures()) {
                ctx.log().error("    ✗ " + e.formatOneLine(), null);
            }
            submitted += res.submitted();
            inserted += res.inserted();
            duplicates += res.duplicates();
            failed += res.failed();
            allFailures.addAll(res.failures());
        }

        // Final summary — easy to spot at the bottom of a long Maven / Gradle log.
        if (failed > 0) {
            ctx.log().warn("=== valkeyry-config push: " + failed + " row(s) FAILED across "
                    + declared.size() + " table(s) ===");
            ctx.log().warn("    Fix the files listed above and re-run the build.");
        }
        return new PluginResult(submitted, inserted, duplicates, failed, declared, allFailures);
    }

    private static String readRecordKey(JsonNode payload, Path file) {
        JsonNode id = payload.path("id");
        if (id.isTextual() && !id.asText().isBlank()) return id.asText();
        if (id.isNumber()) return String.valueOf(id.asLong());
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * @return {@code true} if the entry JSON looks like {@code { "recordKey": "...", "data": {...} }}.
     * This envelope is the canonical shape used by every example under {@code examples/}.
     */
    private static boolean isEnvelope(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        JsonNode recordKey = node.get("recordKey");
        JsonNode data = node.get("data");
        return recordKey != null && recordKey.isTextual() && !recordKey.asText().isBlank()
                && data != null && data.isObject();
    }

    private static List<Path> expandGlob(Path baseDir, String pattern) throws IOException {
        if (pattern == null || pattern.isBlank()) return List.of();
        Path absoluteBase = baseDir.toAbsolutePath();
        String normalised = pattern.replace('\\', '/');
        int lastSlash = normalised.lastIndexOf('/');
        String dirPart = lastSlash < 0 ? "" : normalised.substring(0, lastSlash);
        String filePattern = lastSlash < 0 ? normalised : normalised.substring(lastSlash + 1);
        Path parent = dirPart.isEmpty() ? absoluteBase : absoluteBase.resolve(dirPart);
        if (!Files.isDirectory(parent)) return List.of();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path p : stream) {
                if (matcher.matches(p.getFileName())) out.add(p);
            }
        }
        out.sort(Path::compareTo);
        return out;
    }
}
