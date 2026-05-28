package io.valkeyry.ipaas.api;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.claimcheck.ClaimCheckService;
import io.valkeyry.ipaas.config.BrokerProperties;
import io.valkeyry.ipaas.domain.ClaimCheckRef;
import io.valkeyry.ipaas.domain.ReceivedMessage;
import io.valkeyry.ipaas.routing.TenantRouter;
import io.valkeyry.ipaas.security.TenantAccessGuard;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-Sent-Events subscription endpoint for single-tenant streaming.
 *
 * <p>Bridges the push-based {@link ReactiveBrokerClient#subscribe(String, String, String, String,
 * java.util.function.Function)} contract into a pull-based reactive {@link Flux} for SSE. Claim-check
 * references in incoming messages are resolved on the fly so consumers always see fully
 * materialised payloads — clears the {@code x-valkeyry-claimcheck} header in the process.</p>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/messages")
public class SubscribeController {

    private final BrokerClientFactory brokers;
    private final TenantAccessGuard guard;
    private final ClaimCheckService claimCheck;
    private final BrokerProperties brokerProps;

    public SubscribeController(BrokerClientFactory brokers, TenantAccessGuard guard,
                               ClaimCheckService claimCheck, BrokerProperties brokerProps) {
        this.brokers = brokers;
        this.guard = guard;
        this.claimCheck = claimCheck;
        this.brokerProps = brokerProps;
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ReceivedMessage> subscribe(@PathVariable String tenantId,
                                           @RequestParam String destination,
                                           @RequestParam(required = false) String broker,
                                           @RequestParam(required = false, defaultValue = "QUEUE") String mode,
                                           Authentication auth) {
        String brokerType = (broker == null || broker.isBlank()) ? brokerProps.getDefaultBroker() : broker;
        return guard.check(auth, tenantId)
                .thenMany(stream(tenantId, brokerType, destination, mode))
                .flatMap(this::resolveClaimCheckIfPresent);
    }

    /**
     * Bridges {@link ReactiveBrokerClient#subscribe} (push, Disposable, manual ack) into a
     * reactive {@link Flux} that an SSE client consumes. Every delivered message is auto-ACKed
     * because the SSE client doesn't carry an ack channel back — for at-most-once delivery use
     * the multi-tenant consumer-manager API instead.
     */
    private Flux<ReceivedMessage> stream(String tenantId, String brokerType, String destination, String mode) {
        ReactiveBrokerClient client = brokers.get(brokerType);
        return Flux.create(sink -> {
            Disposable d = client.subscribe(tenantId, TenantRouter.DEFAULT_PROJECT_ID, destination, mode,
                    in -> {
                        sink.next(toReceivedMessage(client.type(), destination, in));
                        return Mono.just(true);
                    });
            sink.onDispose(d::dispose);
        });
    }

    private ReceivedMessage toReceivedMessage(String brokerType, String destination, ReactiveBrokerClient.IncomingMessage in) {
        Map<String, String> headers = in.getHeaders() == null ? new HashMap<>() : new HashMap<>(in.getHeaders());
        boolean claimCheck = "true".equalsIgnoreCase(headers.get("x-valkeyry-claimcheck"));
        return new ReceivedMessage(in.getMessageId(), brokerType.toLowerCase(), destination,
                in.getPayload(), headers, claimCheck);
    }

    private Mono<ReceivedMessage> resolveClaimCheckIfPresent(ReceivedMessage msg) {
        if (!msg.claimCheck()) return Mono.just(msg);
        String bucket = msg.headers().get("x-valkeyry-claim-bucket");
        String key = msg.headers().get("x-valkeyry-claim-key");
        if (bucket == null || key == null) return Mono.just(msg);
        ClaimCheckRef ref = new ClaimCheckRef("s3", bucket, key, 0L, "");
        return claimCheck.resolve(ref)
                .map(bytes -> new ReceivedMessage(msg.messageId(), msg.broker(), msg.destination(),
                        bytes, msg.headers(), false));
    }
}
