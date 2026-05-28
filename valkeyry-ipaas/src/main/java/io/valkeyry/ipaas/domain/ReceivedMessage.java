package io.valkeyry.ipaas.domain;

import java.util.Collections;
import java.util.Map;

/**
 * Inbound message produced by {@link io.valkeyry.ipaas.broker.ReactiveBrokerClient#subscribe(
 *   String, String, String, String, java.util.function.Function)} and surfaced through the
 * single-tenant SSE Subscribe API.
 *
 * <p>{@code claimCheck} signals the producer externalised the payload — the
 * {@code x-valkeyry-claim-bucket} / {@code x-valkeyry-claim-key} headers point to the actual
 * bytes in S3. The subscribe controller resolves those automatically before streaming to clients.</p>
 */
public record ReceivedMessage(
        String messageId,
        String broker,
        String destination,
        byte[] payload,
        Map<String, String> headers,
        boolean claimCheck
) {
    public ReceivedMessage {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
    }
}
