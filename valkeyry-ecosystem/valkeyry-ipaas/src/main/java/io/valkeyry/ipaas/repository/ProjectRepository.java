package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.Project;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProjectRepository extends ReactiveCrudRepository<Project, String> {
    Mono<Project> findByTenantIdAndName(String tenantId, String name);
    Flux<Project> findAllByTenantId(String tenantId);
}
