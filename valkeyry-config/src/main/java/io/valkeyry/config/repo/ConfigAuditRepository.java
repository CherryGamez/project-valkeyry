package io.valkeyry.config.repo;

import io.valkeyry.config.domain.ConfigAuditEntry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ConfigAuditRepository extends ReactiveCrudRepository<ConfigAuditEntry, UUID> {

    @Query("""
        SELECT * FROM config_audit_log
         WHERE tenant_id = :tenantId
         ORDER BY changed_at DESC
         LIMIT :limit OFFSET :offset
    """)
    Flux<ConfigAuditEntry> findByTenant(String tenantId, int limit, int offset);

    @Query("""
        SELECT * FROM config_audit_log
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
         ORDER BY changed_at DESC
         LIMIT :limit OFFSET :offset
    """)
    Flux<ConfigAuditEntry> findByTable(String tenantId, String tableName, int limit, int offset);

    @Query("""
        SELECT * FROM config_audit_log
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND record_key = :recordKey
         ORDER BY changed_at DESC
         LIMIT :limit OFFSET :offset
    """)
    Flux<ConfigAuditEntry> findByRecord(String tenantId, String tableName, String recordKey, int limit, int offset);

    @Query("""
        SELECT * FROM config_audit_log
         WHERE tenant_id = :tenantId
           AND actor     = :actor
         ORDER BY changed_at DESC
         LIMIT :limit OFFSET :offset
    """)
    Flux<ConfigAuditEntry> findByActor(String tenantId, String actor, int limit, int offset);
}
