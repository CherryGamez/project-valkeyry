package io.valkeyry.config.repo;

import io.valkeyry.config.domain.VirtualTableEntry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface VirtualTableEntryRepository extends ReactiveCrudRepository<VirtualTableEntry, UUID> {

    /** Latest version of a logical record, if any. */
    @Query("""
        SELECT * FROM virtual_table_entry
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND record_key = :recordKey
           AND is_latest  = true
    """)
    Mono<VirtualTableEntry> findLatest(String tenantId, String tableName, String recordKey);

    /** Idempotency Guard — is this exact payload already the current head? */
    @Query("""
        SELECT * FROM virtual_table_entry
         WHERE tenant_id    = :tenantId
           AND table_name   = :tableName
           AND record_key   = :recordKey
           AND payload_hash = :payloadHash
           AND is_latest    = true
    """)
    Mono<VirtualTableEntry> findDuplicate(String tenantId, String tableName, String recordKey, String payloadHash);

    /** Version trail ordered newest → oldest. */
    @Query("""
        SELECT * FROM virtual_table_entry
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND record_key = :recordKey
         ORDER BY version DESC
    """)
    Flux<VirtualTableEntry> findHistory(String tenantId, String tableName, String recordKey);

    /** Current heads of every logical record in a virtual table. */
    @Query("""
        SELECT * FROM virtual_table_entry
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND is_latest  = true
         ORDER BY record_key
         LIMIT :limit OFFSET :offset
    """)
    Flux<VirtualTableEntry> findLatestPaged(String tenantId, String tableName, int limit, int offset);

    /** Mark the previous head as superseded — must run inside the same transaction as the insert. */
    @Query("""
        UPDATE virtual_table_entry
           SET is_latest = false
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND record_key = :recordKey
           AND is_latest  = true
    """)
    Mono<Long> markPreviousLatestStale(String tenantId, String tableName, String recordKey);

    @Query("""
        SELECT COALESCE(MAX(version), 0)
          FROM virtual_table_entry
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND record_key = :recordKey
    """)
    Mono<Long> maxVersion(String tenantId, String tableName, String recordKey);

    /**
     * Dynamic JSONB criteria search — caller supplies a JSON object whose key/value pairs
     * must all match (PostgreSQL {@code @>} containment operator). Empty object → all heads.
     */
    @Query("""
        SELECT * FROM virtual_table_entry
         WHERE tenant_id  = :tenantId
           AND table_name = :tableName
           AND is_latest  = true
           AND data @> :criteria::jsonb
         ORDER BY record_key
         LIMIT :limit OFFSET :offset
    """)
    Flux<VirtualTableEntry> search(String tenantId, String tableName, String criteria, int limit, int offset);
}
