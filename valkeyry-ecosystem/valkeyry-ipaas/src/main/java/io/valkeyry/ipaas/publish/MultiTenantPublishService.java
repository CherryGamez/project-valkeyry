package io.valkeyry.ipaas.publish;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.queue.QueueManagementService;
import io.valkeyry.ipaas.repository.UserAccessPolicyRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Cross-tenant fan-out publish. For each target the caller must hold PROJECT_WRITE
 * on the matching workspace (unless {@code ipaas.security.allow-anonymous=true}).
 * Targets that fail RBAC are skipped; the rest are still published — the response
 * reports per-target accept/deny outcomes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiTenantPublishService {

    private final QueueManagementService queueService;
    private final BrokerClientFactory brokerFactory;
    private final UserAccessPolicyRepository policyRepository;
    private final IpaasProperties props;

    public Flux<PublishOutcome> publish(Jwt callerJwt, List<PublishTarget> targets, String payload) {
        boolean anonymous = props.getSecurity() != null && props.getSecurity().isAllowAnonymous();
        String subject = callerJwt == null ? null : callerJwt.getSubject();
        byte[] body = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);

        return Flux.fromIterable(targets)
                .flatMap(t -> authorize(anonymous, subject, t)
                        .flatMap(allowed -> allowed
                                ? doPublish(t, body)
                                : Mono.just(PublishOutcome.denied(t))));
    }

    private Mono<Boolean> authorize(boolean anonymous, String subject, PublishTarget t) {
        if (anonymous) return Mono.just(true);
        if (subject == null) return Mono.just(false);
        return policyRepository.findBySubjectAndTenantIdAndProjectIdAndPrivilege(
                        subject, t.tenantId, t.projectId, "PROJECT_WRITE")
                .map(p -> true)
                .defaultIfEmpty(false);
    }

    private Mono<PublishOutcome> doPublish(PublishTarget t, byte[] body) {
        return queueService.resolveOrLazyProvision(t.tenantId, t.projectId, t.destination)
                .flatMap(asset -> brokerFactory.get(asset.getBrokerType())
                        .publish(t.tenantId, t.projectId, t.destination, body, java.util.Map.of(
                                "x-multi-publish", "true"))
                        .thenReturn(PublishOutcome.accepted(t, asset.getBrokerType(), asset.getProvisioningMode())))
                .onErrorResume(ex -> {
                    log.warn("Multi-publish leg failed for {}: {}", t, ex.toString());
                    return Mono.just(PublishOutcome.error(t, ex.getMessage()));
                });
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PublishTarget {
        private String tenantId;
        private String projectId;
        private String destination;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PublishOutcome {
        private String tenantId;
        private String projectId;
        private String destination;
        private String status;       // ACCEPTED | DENIED | ERROR
        private String brokerType;
        private String provisioningMode;
        private String error;

        public static PublishOutcome accepted(PublishTarget t, String broker, String mode) {
            return new PublishOutcome(t.tenantId, t.projectId, t.destination, "ACCEPTED", broker, mode, null);
        }
        public static PublishOutcome denied(PublishTarget t) {
            return new PublishOutcome(t.tenantId, t.projectId, t.destination, "DENIED", null, null,
                    "Caller lacks PROJECT_WRITE on this workspace.");
        }
        public static PublishOutcome error(PublishTarget t, String err) {
            return new PublishOutcome(t.tenantId, t.projectId, t.destination, "ERROR", null, null, err);
        }
    }
}
