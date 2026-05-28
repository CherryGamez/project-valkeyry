package io.valkeyry.config.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes a deterministic SHA-256 fingerprint of a JSON payload.
 *
 * <p>The same logical content always produces the same hash regardless of original key order,
 * whitespace or insignificant numeric formatting. Achieved by recursively rebuilding the tree with
 * lexicographically sorted object keys and serialising with the canonical JSON emitter.</p>
 *
 * <p>This exact algorithm is also used by the build-tool plugin so that client-side and
 * server-side hashes always match — the Idempotency Guard depends on it.</p>
 */
public final class PayloadFingerprint {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PayloadFingerprint() {}

    /** SHA-256 of canonical-JSON of {@code node}, returned as lowercase hex (64 chars). */
    public static String sha256(JsonNode node) {
        try {
            JsonNode canonical = canonicalize(node);
            byte[] bytes = CANONICAL.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint payload", e);
        }
    }

    /** Convenience overload — parses the raw bytes first. */
    public static String sha256(byte[] rawJson) {
        try {
            JsonNode node = CANONICAL.readTree(new String(rawJson, StandardCharsets.UTF_8));
            return sha256(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not valid JSON", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return CANONICAL.nullNode();
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<String> it = src.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                sorted.put(name, canonicalize(src.get(name)));
            }
            ObjectNode dst = CANONICAL.createObjectNode();
            sorted.forEach(dst::set);
            return dst;
        }
        if (node.isArray()) {
            ArrayNode src = (ArrayNode) node;
            ArrayNode dst = CANONICAL.createArrayNode();
            for (int i = 0; i < src.size(); i++) dst.add(canonicalize(src.get(i)));
            return dst;
        }
        return node;
    }
}
