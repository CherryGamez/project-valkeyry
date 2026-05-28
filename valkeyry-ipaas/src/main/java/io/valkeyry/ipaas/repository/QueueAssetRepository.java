package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.QueueAsset;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface QueueAssetRepository extends ReactiveCrudRepository<QueueAsset, UUID> {

    Mono<QueueAsset> findByTenantIdAndProjectIdAndDestinationName(String tenantId, String projectId, String destination);

    Flux<QueueAsset> findAllByTenantIdAndProjectId(String tenantId, String projectId);

    @Query("SELECT EXISTS(SELECT 1 FROM queue_assets WHERE tenant_id = :t AND project_id = :p AND destination_name = :d)")
    Mono<Boolean> existsByTriple(String t, String p, String d);
}
