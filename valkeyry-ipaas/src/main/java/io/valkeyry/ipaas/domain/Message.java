package io.valkeyry.ipaas.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Carrier envelope produced by the Publish API and routed by the engine.
 *
 * <p>The {@code payloadInline} is the raw bytes the caller posted. If those bytes exceed the
 * {@link io.valkeyry.ipaas.config.ClaimCheckProperties#thresholdBytes} they are externalised
 * and {@code claimCheckRef} is populated instead — at that point downstream broker adapters
 * publish only the lightweight reference.</p>
 */
public record Message(
        UUID id,
        String tenantId,
        String destination,
        String mode,                // "QUEUE" or "STREAM"
        byte[] payloadInline,
        ClaimCheckRef claimCheckRef,
        Map<String, String> headers,
        Instant createdAt
) {
    public Message {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
        mode = (mode == null || mode.isBlank()) ? "QUEUE" : mode.toUpperCase();
    }

    public boolean isClaimCheck() { return claimCheckRef != null; }
}
