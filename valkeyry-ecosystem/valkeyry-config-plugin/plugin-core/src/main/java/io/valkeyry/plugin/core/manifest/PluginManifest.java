package io.valkeyry.plugin.core.manifest;

import java.util.Collections;
import java.util.List;

/**
 * Parsed root of a {@code valkeyry-config.yaml} manifest.
 *
 * <p>Example:</p>
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
 *   - name:    products
 *     schema:  schemas/products.schema.json
 *     entries: data/products/*.json
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
        private String entries;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public String getEntries() { return entries; }
        public void setEntries(String entries) { this.entries = entries; }
    }
}
