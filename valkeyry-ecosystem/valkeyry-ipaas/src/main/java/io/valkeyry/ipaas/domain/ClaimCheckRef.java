package io.valkeyry.ipaas.domain;

/** Reference to an externalised payload — see {@link io.valkeyry.ipaas.claimcheck.ClaimCheckService}. */
public record ClaimCheckRef(
        String store,        // "s3"
        String bucket,
        String key,
        long sizeBytes,
        String sha256
) {}
