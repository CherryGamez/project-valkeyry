package io.valkeyry.ipaas.claimcheck;

import io.valkeyry.ipaas.domain.ClaimCheckRef;
import reactor.core.publisher.Mono;

/** Pluggable externalised payload store. */
public interface ClaimCheckStore {

    String name();

    Mono<ClaimCheckRef> put(String tenantId, byte[] payload, String contentType);

    Mono<byte[]> get(ClaimCheckRef ref);
}
