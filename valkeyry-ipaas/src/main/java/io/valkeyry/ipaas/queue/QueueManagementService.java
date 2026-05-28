package io.valkeyry.ipaas.queue;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.domain.QueueAsset;
import io.valkeyry.ipaas.repository.QueueAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Dual-mode provisioning engine:
 *   - declareFromCatalog(): admin-driven explicit declaration (CATALOG).
 *   - resolveOrLazyProvision(): self-healing lazy creation on first publish (LAZY_PROVISIONED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueManagementService {

    private final QueueAssetRepository repo;
    private final BrokerClientFactory brokerFactory;
    private final IpaasProperties props;
    private final WebClient webClient;

    public Mono<QueueAsset> declareFromCatalog(String tenantId, String projectId, String destinationName,
                                               String brokerType, String processingMode, Integer ttlSeconds) {
        String resolvedBroker  = brokerType  != null ? brokerType  : props.getDefaultBroker();
        String resolvedMode    = processingMode != null ? processingMode : props.getProcessingMode();
        ReactiveBrokerClient broker = brokerFactory.get(resolvedBroker);

        QueueAsset asset = QueueAsset.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .destinationName(destinationName)
                .brokerType(resolvedBroker)
                .processingMode(resolvedMode)
                .provisioningMode("CATALOG")
                .ttlSeconds(ttlSeconds)
                .createdAt(OffsetDateTime.now())
                .build();

        return broker.declareDestination(tenantId, projectId, destinationName, resolvedMode)
                .then(repo.save(asset));
    }

    /** Returns the existing asset or programmatically lazy-creates it on first ingress hit. */
    public Mono<QueueAsset> resolveOrLazyProvision(String tenantId, String projectId, String destinationName) {
        return repo.findByTenantIdAndProjectIdAndDestinationName(tenantId, projectId, destinationName)
                .switchIfEmpty(Mono.defer(() -> lazyProvision(tenantId, projectId, destinationName)));
    }

    private Mono<QueueAsset> lazyProvision(String tenantId, String projectId, String destinationName) {
        log.warn("LAZY-PROVISION: tenant={} project={} dest={}", tenantId, projectId, destinationName);
        ReactiveBrokerClient broker = brokerFactory.get(props.getDefaultBroker());
        QueueAsset asset = QueueAsset.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId).projectId(projectId)
                .destinationName(destinationName)
                .brokerType(props.getDefaultBroker())
                .processingMode(props.getProcessingMode())
                .provisioningMode("LAZY_PROVISIONED")
                .createdAt(OffsetDateTime.now())
                .build();
        return broker.declareDestination(tenantId, projectId, destinationName, props.getProcessingMode())
                .then(repo.save(asset))
                .doOnSuccess(saved -> fireAdminAlert(saved).subscribe());
    }

    private Mono<Void> fireAdminAlert(QueueAsset asset) {
        return webClient.post()
                .uri(props.getWebhook().getAdminAlertUrl())
                .bodyValue(java.util.Map.of(
                        "event", "LAZY_PROVISIONED",
                        "tenantId", asset.getTenantId().toString(),
                        "projectId", asset.getProjectId().toString(),
                        "destination", asset.getDestinationName(),
                        "brokerType", asset.getBrokerType()))
                .retrieve().bodyToMono(Void.class)
                .onErrorResume(ex -> {
                    log.warn("Admin alert webhook failed (non-blocking): {}", ex.toString());
                    return Mono.empty();
                });
    }

    public Flux<QueueAsset> list(String tenantId, String projectId) {
        return repo.findAllByTenantIdAndProjectId(tenantId, projectId);
    }
}
