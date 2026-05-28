package io.valkeyry.plugin.core.auth;

import java.net.http.HttpRequest;

/** Strategy that mutates an outbound HTTP request to carry credentials. */
public interface AuthStrategy {
    void apply(HttpRequest.Builder request);

    static AuthStrategy fromManifest(io.valkeyry.plugin.core.manifest.PluginManifest.AuthSpec spec) {
        String type = spec.getType() == null ? "basic" : spec.getType().toLowerCase();
        return switch (type) {
            case "basic"   -> new BasicAuthStrategy(spec.getUsername(), spec.getPassword());
            case "api-key", "apikey" -> new ApiKeyAuthStrategy(spec.getApiKey());
            default        -> throw new IllegalArgumentException("Unsupported auth.type=" + type);
        };
    }
}
