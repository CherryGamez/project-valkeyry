package io.valkeyry.plugin.core.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed root of a {@code valkeyry-config.yaml} manifest.
 *
 * <p>Example — schema loaded from a file:</p>
 * <pre>
 * endpoint:    https://valkeyry-config.acme.io
 * tenant:      acme-prod
 * auth:
 *   type:     basic            # or "api-key"
 *   username: build-bot
 *   password: ${VALKEYRY_PASS}
 * tables:
 *   - name:    customers
 *     schema:  schemas/customers.schema.json
 *     entries: data/customers/*.json
 * </pre>
 *
 * <p>Example — schema declared inline in YAML (supports the same widget set as the UI:
 * dropdowns via {@code enum}, checkboxes via {@code type: boolean}, multi-choice via
 * {@code type: array} + {@code items.enum}):</p>
 * <pre>
 * tables:
 *   - name: feature_flags
 *     schemaInline:
 *       $schema: "https://json-schema.org/draft/2020-12/schema"
 *       type: object
 *       required: [key, enabled]
 *       properties:
 *         key:     { type: string, title: "Flag key", pattern: "^[a-z0-9_.-]+$" }
 *         enabled: { type: boolean, title: "Enabled", default: false }
 *         rolloutPercent:
 *           type: integer
 *           title: "Rollout %"
 *           minimum: 0
 *           maximum: 100
 *         audiences:
 *           type: array
 *           title: "Audiences (multi-choice)"
 *           uniqueItems: true
 *           items:
 *             type: string
 *             enum: [internal, beta, ga, enterprise]
 *       additionalProperties: false
 *     entries: data/flags/*.json
 * </pre>
 */
public final class PluginManifest {

    private String endpoint;
    private String tenant;
    private AuthSpec auth = new AuthSpec();
    private List<TableSpec> tables = List.of();

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public AuthSpec getAuth() { return auth; }
    public void setAuth(AuthSpec auth) { this.auth = auth; }
    public List<TableSpec> getTables() { return Collections.unmodifiableList(tables); }
    public void setTables(List<TableSpec> tables) { this.tables = tables; }

    public static final class AuthSpec {
        private String type = "basic";
        private String username;
        private String password;
        private String apiKey;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public static final class TableSpec {
        private String name;
        private String schema;
        private Map<String, Object> schemaInline = new LinkedHashMap<>();
        private String entries;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        /**
         * Inline JSON-Schema declared directly in YAML. When present, takes precedence over
         * the file referenced by {@link #getSchema()}. The map is interpreted as a regular
         * JSON-Schema document — Draft 2020-12 — and pushed verbatim to the registry.
         */
        public Map<String, Object> getSchemaInline() { return schemaInline; }
        public void setSchemaInline(Map<String, Object> schemaInline) {
            this.schemaInline = schemaInline == null ? new LinkedHashMap<>() : schemaInline;
        }
        public boolean hasInlineSchema() { return schemaInline != null && !schemaInline.isEmpty(); }
        public String getEntries() { return entries; }
        public void setEntries(String entries) { this.entries = entries; }
    }
}
