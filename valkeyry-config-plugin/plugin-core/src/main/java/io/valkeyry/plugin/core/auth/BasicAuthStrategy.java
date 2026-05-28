package io.valkeyry.plugin.core.auth;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class BasicAuthStrategy implements AuthStrategy {
    private final String header;
    public BasicAuthStrategy(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("BasicAuthStrategy requires username + password");
        }
        String raw = username + ":" + password;
        this.header = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void apply(HttpRequest.Builder request) { request.header("Authorization", header); }
}
