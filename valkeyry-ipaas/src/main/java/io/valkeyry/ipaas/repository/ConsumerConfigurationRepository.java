package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.ConsumerConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ConsumerConfigurationRepository extends ReactiveCrudRepository<ConsumerConfiguration, UUID> {
    Flux<ConsumerConfiguration> findAllByTenantIdAndProjectId(String tenantId, String projectId);
    Mono<ConsumerConfiguration> findByTenantIdAndProjectIdAndConsumerName(String t, String p, String name);
}
