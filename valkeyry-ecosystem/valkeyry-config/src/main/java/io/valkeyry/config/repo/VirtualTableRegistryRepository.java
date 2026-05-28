package io.valkeyry.config.repo;

import io.valkeyry.config.domain.VirtualTableRegistry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface VirtualTableRegistryRepository extends ReactiveCrudRepository<VirtualTableRegistry, UUID> {

    @Query("""
        SELECT * FROM virtual_table_registry
         WHERE tenant_id = :tenantId
           AND table_name = :tableName
           AND is_active  = true
    """)
    Mono<VirtualTableRegistry> findActive(String tenantId, String tableName);

    @Query("""
        SELECT * FROM virtual_table_registry
         WHERE tenant_id = :tenantId
           AND table_name = :tableName
         ORDER BY config_version DESC
    """)
    Flux<VirtualTableRegistry> findHistory(String tenantId, String tableName);

    @Query("""
        SELECT * FROM virtual_table_registry
         WHERE tenant_id = :tenantId
           AND is_active  = true
         ORDER BY table_name
    """)
    Flux<VirtualTableRegistry> findAllActive(String tenantId);

    @Query("""
        UPDATE virtual_table_registry
           SET is_active = false
         WHERE tenant_id = :tenantId
           AND table_name = :tableName
           AND is_active  = true
    """)
    Mono<Long> deactivateAll(String tenantId, String tableName);
}
