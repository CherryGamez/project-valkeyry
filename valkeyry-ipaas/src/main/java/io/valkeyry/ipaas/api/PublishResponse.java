package io.valkeyry.ipaas.api;

import io.valkeyry.ipaas.domain.DispatchResult;

public record PublishResponse(
        String messageId,
        String broker,
        String destination,
        boolean externalised
) {
    public static PublishResponse from(DispatchResult r) {
        return new PublishResponse(r.messageId(), r.broker(), r.destination(), r.externalised());
    }
}
