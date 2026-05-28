package io.valkeyry.ipaas.queue;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.domain.QueueAsset;
import io.valkeyry.ipaas.repository.QueueAssetRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Queue Management",
     description = "Service-Catalog declarations and lazy ingress publishing.")
@RestController
@Validated
@RequestMapping("/api/v1/{tenantId}/{projectId}")
@RequiredArgsConstructor
public class QueueManagementController {

    private final QueueManagementService service;
    private final QueueAssetRepository repo;
    private final BrokerClientFactory brokerFactory;

    @Operation(summary = "Service-Catalog: declare a persistent queue/topic asset.",
            description = "Used by ServiceNow / Backstage / GitOps to register a permanent broker topology.")
    @ApiResponse(responseCode = "200", description = "Asset declared.")
    @PostMapping("/queues/declare")
    public Mono<QueueAsset> declare(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                    @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                    @RequestBody DeclareRequest body) {
        return service.declareFromCatalog(tenantId, projectId,
                body.getDestinationName(), body.getBrokerType(),
                body.getProcessingMode(), body.getTtlSeconds());
    }

    @Operation(summary = "List queue assets for tenant/project.")
    @GetMapping("/queues")
    public Flux<QueueAsset> list(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId) {
        return service.list(tenantId, projectId);
    }

    @Operation(summary = "Lazy ingress publish.",
            description = "Publishes a payload. If the destination does not exist it is lazy-provisioned without dropping the message.")
    @PostMapping("/ingress/{destination}")
    public Mono<Map<String, Object>> ingress(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                             @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                             @PathVariable("destination") String destination,
                                             @RequestBody byte[] body) {
        return service.resolveOrLazyProvision(tenantId, projectId, destination)
                .flatMap(asset -> brokerFactory.get(asset.getBrokerType())
                        .publish(tenantId, projectId, destination, body, Map.of())
                        .thenReturn(Map.of(
                                "destination", asset.getDestinationName(),
                                "brokerType", asset.getBrokerType(),
                                "provisioningMode", asset.getProvisioningMode(),
                                "status", "ACCEPTED")));
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class DeclareRequest {
        private String destinationName;
        private String brokerType;      // RABBITMQ|KAFKA|ACTIVEMQ (nullable -> uses default)
        private String processingMode;  // QUEUE|STREAMING (nullable -> uses default)
        private Integer ttlSeconds;
    }
}
