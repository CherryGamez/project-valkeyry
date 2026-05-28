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
import java.nio.file.Paths;
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

        int submitted = 0, inserted = 0, duplicates = 0;
        List<String> declared = new ArrayList<>();

        for (PluginManifest.TableSpec table : manifest.getTables()) {
            ctx.log().info("· " + table.getName() + ": declaring schema…");
            JsonNode schema = MAPPER.readTree(ctx.projectBaseDir().resolve(table.getSchema()).toFile());
            ValkeyryConfigClient.DeclareResult decl = client.declareTable(manifest.getTenant(), table.getName(), schema);
            ctx.log().info("  → registered id=" + decl.id() + " configVersion=" + decl.configVersion());
            declared.add(table.getName());

            List<Path> entryFiles = expandGlob(ctx.projectBaseDir(), table.getEntries());
            if (entryFiles.isEmpty()) {
                ctx.log().warn("  no entry files matched " + table.getEntries());
                continue;
            }
            List<ValkeyryConfigClient.EntryRequest> requests = new ArrayList<>();
            for (Path entryFile : entryFiles) {
                JsonNode payload = MAPPER.readTree(entryFile.toFile());
                String recordKey = readRecordKey(payload, entryFile);
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

    private static List<Path> expandGlob(Path baseDir, String pattern) throws IOException {
        if (pattern == null || pattern.isBlank()) return List.of();
        Path absoluteBase = baseDir.toAbsolutePath();
        // Pattern is *relative* to the base dir.
        Path glob = Paths.get(pattern);
        Path parent = glob.getParent() == null ? absoluteBase : absoluteBase.resolve(glob.getParent());
        String filePattern = glob.getFileName().toString();
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
