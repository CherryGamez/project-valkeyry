package io.valkeyry.ipaas.routing;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.ipaas.domain.TopologyConfig;
import io.valkeyry.ipaas.repository.TopologyConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;

@Tag(name = "Routing Topologies")
@RestController
@Validated
@RequestMapping("/api/v1/{tenantId}/{projectId}/topologies")
@RequiredArgsConstructor
public class TopologyController {

    private final TopologyConfigRepository repo;
    private final TopologyExecutor executor;

    @Operation(summary = "Register/upsert a topology YAML manifest.")
    @PostMapping
    public Mono<TopologyConfig> upsert(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                       @RequestBody TopologyConfig cfg) {
        cfg.setId(cfg.getId() == null ? UUID.randomUUID() : cfg.getId());
        cfg.setTenantId(tenantId);
        cfg.setProjectId(projectId);
        cfg.setCreatedAt(OffsetDateTime.now());
        cfg.setEnabled(cfg.getEnabled() == null ? true : cfg.getEnabled());
        return repo.save(cfg);
    }

    @Operation(summary = "List declared topologies.")
    @GetMapping
    public Flux<TopologyConfig> list(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId) {
        return executor.list(tenantId, projectId);
    }

    @Operation(summary = "Deploy a topology — wires up reactive streams in-memory.")
    @PostMapping("/{topologyName}/deploy")
    public Mono<Map<String, String>> deploy(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                            @PathVariable String topologyName) {
        return repo.findByTenantIdAndProjectIdAndTopologyName(tenantId, projectId, topologyName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Topology not found: " + topologyName)))
                .flatMap(cfg -> executor.deploy(tenantId, projectId, cfg))
                .thenReturn(Map.of("status", "DEPLOYED", "topologyName", topologyName));
    }

    @Operation(summary = "Undeploy a running topology.")
    @PostMapping("/{topologyName}/undeploy")
    public Mono<Map<String, String>> undeploy(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId, @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                              @PathVariable String topologyName) {
        return executor.undeploy(tenantId, projectId, topologyName)
                .thenReturn(Map.of("status", "UNDEPLOYED", "topologyName", topologyName));
    }
}
