package io.valkeyry.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bind for {@code valkeyry.api-keys.table} — a comma-separated list of
 * {@code key:tenant1[,tenant2…]} tuples. Empty disables the API-key auth converter.
 */
@ConfigurationProperties(prefix = "valkeyry.api-keys")
public class ApiKeyProperties {

    private String table = "";

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }

    /** Parse the encoded string lazily — never throws, never returns null. */
    public Map<String, List<String>> parsed() {
        Map<String, List<String>> out = new HashMap<>();
        if (table == null || table.isBlank()) return Collections.unmodifiableMap(out);
        for (String tuple : table.split(";")) {
            String trimmed = tuple.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String[] tenants = trimmed.substring(colon + 1).split(",");
            out.put(key, List.copyOf(Arrays.stream(tenants).map(String::trim).filter(s -> !s.isEmpty()).toList()));
        }
        return Collections.unmodifiableMap(out);
    }
}
