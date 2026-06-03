package io.valkeyry.config.repo;

import io.valkeyry.config.domain.admin.AppUserTenant;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AppUserTenantRepository extends ReactiveCrudRepository<AppUserTenant, UUID> {

    @Query("SELECT * FROM app_user_tenant WHERE user_id = :userId")
    Flux<AppUserTenant> findByUser(UUID userId);

    @Query("DELETE FROM app_user_tenant WHERE user_id = :userId")
    Mono<Long> deleteByUser(UUID userId);

    @Query("DELETE FROM app_user_tenant WHERE tenant_id = :tenantId")
    Mono<Long> deleteByTenant(String tenantId);
}
