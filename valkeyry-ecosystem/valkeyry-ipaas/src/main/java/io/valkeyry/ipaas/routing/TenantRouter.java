package io.valkeyry.ipaas.routing;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.claimcheck.ClaimCheckService;
import io.valkeyry.ipaas.config.BrokerProperties;
import io.valkeyry.ipaas.domain.DispatchResult;
import io.valkeyry.ipaas.domain.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Orchestrates the single-tenant publish pipeline:
 * <ol>
 *   <li>externalise oversize payloads via {@link ClaimCheckService};</li>
 *   <li>resolve the broker client (caller's hint or engine default);</li>
 *   <li>dispatch via {@link ReactiveBrokerClient}.</li>
 * </ol>
 *
 * <p>This router is the single-tenant counterpart to
 * {@code publish.MultiTenantPublishService} — both ultimately delegate to the same
 * {@link ReactiveBrokerClient} contract. {@code projectId} defaults to "default" for
 * tenant-scoped (non-project) traffic, since the legacy controller predates the
 * project hierarchy.</p>
 */
@Service
public class TenantRouter {

    /** Default projectId used when callers don't carry one in the message envelope. */
    public static final String DEFAULT_PROJECT_ID = "default";

    private final BrokerClientFactory brokers;
    private final ClaimCheckService claimCheck;
    private final BrokerProperties brokerProps;

    public TenantRouter(BrokerClientFactory brokers, ClaimCheckService claimCheck, BrokerProperties brokerProps) {
        this.brokers = brokers;
        this.claimCheck = claimCheck;
        this.brokerProps = brokerProps;
    }

    public Mono<DispatchResult> route(Message message, String brokerHint) {
        return claimCheck.externaliseIfNeeded(message)
                .flatMap(prepared -> {
                    String type = (brokerHint == null || brokerHint.isBlank())
                            ? brokerProps.getDefaultBroker()
                            : brokerHint;
                    ReactiveBrokerClient client = brokers.get(type);
                    Map<String, String> headers = buildHeaders(prepared);
                    byte[] payload = prepared.isClaimCheck()
                            ? ("{\"claimCheck\":\"" + prepared.claimCheckRef().key() + "\"}").getBytes()
                            : prepared.payloadInline();
                    return client.publish(message.tenantId(), DEFAULT_PROJECT_ID,
                                    prepared.destination(), payload, headers)
                            .thenReturn(new DispatchResult(
                                    client.type().toLowerCase(),
                                    prepared.destination(),
                                    prepared.id().toString(),
                                    prepared.isClaimCheck()));
                });
    }

    private Map<String, String> buildHeaders(Message m) {
        Map<String, String> headers = new HashMap<>(m.headers());
        headers.put("x-valkeyry-message-id", m.id().toString());
        headers.put("x-valkeyry-mode", m.mode());
        if (m.isClaimCheck()) {
            headers.put("x-valkeyry-claimcheck", "true");
            headers.put("x-valkeyry-claim-bucket", m.claimCheckRef().bucket());
            headers.put("x-valkeyry-claim-key", m.claimCheckRef().key());
        }
        return headers;
    }
}
