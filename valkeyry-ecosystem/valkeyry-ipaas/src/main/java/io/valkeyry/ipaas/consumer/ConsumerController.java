package io.valkeyry.ipaas.consumer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.ipaas.domain.ConsumerConfiguration;
import io.valkeyry.ipaas.repository.ConsumerConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Consumer Management")
@RestController
@Validated
@RequestMapping("/api/v1/{tenantId}/{projectId}/consumers")
@RequiredArgsConstructor
public class ConsumerController {

    private final ConsumerConfigurationRepository repo;
    private final DynamicConsumerManager manager;

    @Operation(summary = "Create / upsert a reactive consumer configuration.")
    @PostMapping
    public Mono<ConsumerConfiguration> create(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                              @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                              @RequestBody ConsumerConfiguration cfg) {
        cfg.setId(cfg.getId() == null ? UUID.randomUUID() : cfg.getId());
        cfg.setTenantId(tenantId);
        cfg.setProjectId(projectId);
        cfg.setEnabled(cfg.getEnabled() == null ? true : cfg.getEnabled());
        cfg.setCreatedAt(OffsetDateTime.now());
        return repo.save(cfg);
    }

    @Operation(summary = "List consumers in scope.")
    @GetMapping
    public Flux<ConsumerConfiguration> list(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId) {
        return repo.findAllByTenantIdAndProjectId(tenantId, projectId);
    }

    @Operation(summary = "Activate a configured consumer (start non-blocking stream).")
    @PostMapping("/{consumerId}/activate")
    public Mono<Map<String, String>> activate(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                              @PathVariable UUID consumerId) {
        return manager.activate(tenantId, projectId, consumerId)
                .map(key -> Map.of("status", "ACTIVE", "registryKey", key));
    }

    @Operation(summary = "Deactivate a running consumer stream.")
    @PostMapping("/{consumerId}/deactivate")
    public Mono<Map<String, Boolean>> deactivate(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                                 @PathVariable UUID consumerId) {
        return manager.deactivate(tenantId, projectId, consumerId).map(b -> Map.of("disposed", b));
    }
}
