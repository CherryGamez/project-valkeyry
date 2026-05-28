package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.Tenant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TenantRepository extends ReactiveCrudRepository<Tenant, String> {
    Mono<Tenant> findByName(String name);
}
