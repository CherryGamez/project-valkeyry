package io.valkeyry.ipaas.domain;

/** Outcome of broker dispatch. */
public record DispatchResult(
        String broker,           // "kafka", "rabbit", "activemq"
        String destination,
        String messageId,
        boolean externalised     // true if payload was claim-checked
) {}
