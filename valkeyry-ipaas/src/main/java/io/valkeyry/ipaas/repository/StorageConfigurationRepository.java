package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.StorageConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StorageConfigurationRepository extends ReactiveCrudRepository<StorageConfiguration, UUID> {
    Mono<StorageConfiguration> findByTenantIdAndProjectIdAndName(String t, String p, String name);
}
