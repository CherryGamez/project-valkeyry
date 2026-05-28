package io.valkeyry.plugin.core.hash;

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
 * CLIENT-SIDE mirror of {@code io.valkeyry.config.validation.PayloadFingerprint}.
 *
 * <p>The algorithm MUST stay byte-for-byte identical to the server-side implementation —
 * otherwise the Idempotency Guard would never trigger and every plugin invocation would
 * insert a brand-new version.</p>
 */
public final class PayloadFingerprint {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PayloadFingerprint() {}

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
