package io.valkeyry.ipaas.claimcheck;

import io.valkeyry.ipaas.config.ClaimCheckProperties;
import io.valkeyry.ipaas.domain.ClaimCheckRef;
import io.valkeyry.ipaas.domain.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Decides whether a publish-bound {@link Message} should have its payload externalised.
 *
 * <p>Threshold is taken from {@link ClaimCheckProperties#getThresholdBytes()}. Anything
 * smaller stays inline. Anything larger is pushed to the {@link ClaimCheckStore} and the
 * outbound envelope is rewritten to carry only a {@link ClaimCheckRef}.</p>
 */
@Service
public class ClaimCheckService {

    private final ClaimCheckProperties props;
    private final ClaimCheckStore store;

    public ClaimCheckService(ClaimCheckProperties props, ClaimCheckStore store) {
        this.props = props;
        this.store = store;
    }

    /** Returns either the original {@code message} or a clone with payload swapped for a ref. */
    public Mono<Message> externaliseIfNeeded(Message message) {
        if (!props.isEnabled() || message.payloadInline() == null) return Mono.just(message);
        if (message.payloadInline().length < props.getThresholdBytes()) return Mono.just(message);

        return store.put(message.tenantId(), message.payloadInline(), message.headers().get("content-type"))
                .map(ref -> new Message(
                        message.id(),
                        message.tenantId(),
                        message.destination(),
                        message.mode(),
                        null,
                        ref,
                        message.headers(),
                        message.createdAt()));
    }

    public Mono<byte[]> resolve(ClaimCheckRef ref) {
        return store.get(ref);
    }
}
