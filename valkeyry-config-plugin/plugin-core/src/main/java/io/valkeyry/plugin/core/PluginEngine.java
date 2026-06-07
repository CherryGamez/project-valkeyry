package io.valkeyry.plugin.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.plugin.core.auth.AuthStrategy;
import io.valkeyry.plugin.core.hash.PayloadFingerprint;
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
 * </ol>
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

        // Schema files and entry globs in the manifest are resolved relative to the manifest's
        // own parent directory. This lets a build (e.g. examples/maven/01-flag/pom.xml) point at
        // a manifest in a sibling folder (../../01-flag/valkeyry-config.yaml) and still pick up
        // the data/ and schemas/ that live next to that YAML.
        Path manifestDir = ctx.manifestPath().toAbsolutePath().getParent();
        if (manifestDir == null) manifestDir = ctx.projectBaseDir();

        int submitted = 0, inserted = 0, duplicates = 0;
        List<String> declared = new ArrayList<>();

        for (PluginManifest.TableSpec table : manifest.getTables()) {
            ctx.log().info("· " + table.getName() + ": declaring schema…");
            JsonNode schema = table.hasInlineSchema()
                    ? MAPPER.valueToTree(table.getSchemaInline())
                    : MAPPER.readTree(manifestDir.resolve(table.getSchema()).toFile());
            ValkeyryConfigClient.DeclareResult decl = client.declareTable(manifest.getTenant(), table.getName(), schema);
            ctx.log().info("  → registered id=" + decl.id() + " configVersion=" + decl.configVersion());
            declared.add(table.getName());

            List<Path> entryFiles = expandGlob(manifestDir, table.getEntries());
            if (entryFiles.isEmpty()) {
                ctx.log().warn("  no entry files matched " + table.getEntries());
                continue;
            }
            List<ValkeyryConfigClient.EntryRequest> requests = new ArrayList<>();
            for (Path entryFile : entryFiles) {
                JsonNode raw = MAPPER.readTree(entryFile.toFile());
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
                PayloadFingerprint.sha256(payload);
                requests.add(new ValkeyryConfigClient.EntryRequest(recordKey, payload));
            }
            ValkeyryConfigClient.BatchResult res = client.ingestBatch(manifest.getTenant(), table.getName(), requests);
            ctx.log().info("  → submitted=" + res.submitted() + " inserted=" + res.inserted() + " duplicates=" + res.duplicates());
            submitted += res.submitted();
            inserted += res.inserted();
            duplicates += res.duplicates();
        }
        return new PluginResult(submitted, inserted, duplicates, declared);
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
        // Pattern is *relative* to the base dir and uses '/' as the separator (manifest is YAML,
        // authored once and consumed on any OS). Do NOT build a Path from the raw pattern —
        // '*' and '?' are illegal NTFS characters on Windows and Paths.get throws
        // InvalidPathException before we ever reach the matcher (issue surfaces only on Windows
        // because POSIX paths happen to accept '*'). Split the literal directory portion from
        // the filename glob portion as plain strings instead.
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
