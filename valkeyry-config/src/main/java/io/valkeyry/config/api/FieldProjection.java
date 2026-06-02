package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Selective JSON output for read endpoints.
 *
 * <p>The {@code ?fields=…} query param accepts a comma-separated list. Bare names
 * filter top-level {@link EntryView} fields ({@code id}, {@code version}, {@code data}, …).
 * Dotted names of the form {@code data.&lt;key&gt;} reach into the entry's
 * {@code data} JSONB and keep only the named sub-keys.</p>
 *
 * <p>Empty / null filter → the original view is returned untouched (the default
 * contract: every column unless the caller opts out).</p>
 *
 * <pre>
 *   GET /…/entries                         → {id, version, data:{email,role,…}, …}
 *   GET /…/entries?fields=recordKey,data.role
 *                                          → {recordKey, data:{role}}
 *   GET /…/entries?fields=data.email
 *                                          → {data:{email}}
 * </pre>
 */
public final class FieldProjection {

    private FieldProjection() {}

    /**
     * Apply a {@code fields=} projection to a {@link JsonNode} view.
     *
     * @param view   any JSON object — typically the Jackson serialisation of {@link EntryView}.
     * @param fields the raw value of the {@code fields=} query param (may be {@code null} or blank).
     * @param mapper Jackson mapper used to mint the result object.
     * @return the original {@code view} if the filter is empty, else a new
     *         {@code ObjectNode} containing only the requested fields.
     */
    public static JsonNode apply(JsonNode view, String fields, ObjectMapper mapper) {
        if (fields == null || fields.isBlank() || !view.isObject()) return view;
        Set<String> wanted = new LinkedHashSet<>();
        for (String tok : fields.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) wanted.add(t);
        }
        if (wanted.isEmpty()) return view;

        ObjectNode out = mapper.createObjectNode();
        // Top-level whitelist.
        wanted.stream()
                .filter(f -> !f.contains("."))
                .filter(view::has)
                .forEach(f -> out.set(f, view.get(f)));
        // Nested data.* whitelist.
        ObjectNode dataOut = null;
        for (String f : wanted) {
            if (!f.startsWith("data.")) continue;
            String sub = f.substring("data.".length());
            JsonNode data = view.get("data");
            if (data == null || !data.isObject() || !data.has(sub)) continue;
            if (dataOut == null) dataOut = mapper.createObjectNode();
            dataOut.set(sub, data.get(sub));
        }
        if (dataOut != null) out.set("data", dataOut);
        return out;
    }
}
