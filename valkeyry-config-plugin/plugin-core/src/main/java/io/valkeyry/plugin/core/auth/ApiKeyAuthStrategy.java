package io.valkeyry.plugin.core.auth;

import java.net.http.HttpRequest;

public final class ApiKeyAuthStrategy implements AuthStrategy {
    private final String value;
    public ApiKeyAuthStrategy(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("ApiKeyAuthStrategy requires apiKey");
        this.value = key;
    }
    @Override public void apply(HttpRequest.Builder request) { request.header("X-API-Key", value); }
}
