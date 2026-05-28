package io.valkeyry.ipaas.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Map;

public record PublishRequest(
        @NotBlank String destination,
        String broker,                       // optional; null → engine default
        String mode,                          // "QUEUE" | "STREAM"
        Map<String, String> headers,
        @NotNull byte[] payload               // already base64-decoded by Jackson
) {
    public PublishRequest {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
    }
}
