package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.TopologyConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TopologyConfigRepository extends ReactiveCrudRepository<TopologyConfig, UUID> {
    Flux<TopologyConfig> findAllByTenantIdAndProjectId(String tenantId, String projectId);
    Mono<TopologyConfig> findByTenantIdAndProjectIdAndTopologyName(String t, String p, String name);
}
