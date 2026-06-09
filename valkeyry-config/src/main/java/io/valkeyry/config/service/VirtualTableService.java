package io.valkeyry.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import io.valkeyry.config.api.EntryView;
import io.valkeyry.config.api.VirtualTableView;
import io.valkeyry.config.domain.VirtualTableEntry;
import io.valkeyry.config.domain.VirtualTableRegistry;
import io.valkeyry.config.error.IdempotentDuplicateException;
import io.valkeyry.config.error.VirtualTableNotFoundException;
import io.valkeyry.config.repo.VirtualTableEntryRepository;
import io.valkeyry.config.repo.VirtualTableRegistryRepository;
import io.valkeyry.config.service.query.PredicateCompiler;
import io.valkeyry.config.validation.JsonSchemaValidatorService;
import io.valkeyry.config.validation.PayloadFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core orchestration of the headless schema registry.
 *
 * <p>Encapsulates all the moving parts that have to stay consistent on every write:</p>
 * <ol>
 *   <li>resolve the active {@link VirtualTableRegistry} row for {@code (tenant, table)};</li>
 *   <li>validate the payload against its JSON Schema;</li>
 *   <li>compute the canonical SHA-256 fingerprint;</li>
 *   <li>short-circuit if the fingerprint matches the current head (Idempotency Guard);</li>
 *   <li>flip the previous head and insert the next version inside one R2DBC transaction.</li>
 * </ol>
 */
@Service
public class VirtualTableService {

    private static final Logger log = LoggerFactory.getLogger(VirtualTableService.class);

    private final VirtualTableRegistryRepository registries;
    private final VirtualTableEntryRepository entries;
    private final JsonSchemaValidatorService validator;
    private final ObjectMapper mapper;
    private final TransactionalOperator tx;
    private final ConfigAuditService audit;
    private final DatabaseClient db;

    public VirtualTableService(VirtualTableRegistryRepository registries,
                               VirtualTableEntryRepository entries,
                               JsonSchemaValidatorService validator,
                               ObjectMapper mapper,
                               TransactionalOperator tx,
                               ConfigAuditService audit,
                               DatabaseClient db) {
        this.registries = registries;
        this.entries = entries;
        this.validator = validator;
        this.mapper = mapper;
        this.tx = tx;
        this.audit = audit;
        this.db = db;
    }

    // -----------------------------------------------------------------------
    // Declaration / schema management
    // -----------------------------------------------------------------------

    public Mono<VirtualTableView> declare(String tenantId, String tableName, JsonNode schema,
                                          String subject, String actorTrack, String requestId) {
        // Validate the schema itself is parseable JSON.
        if (schema == null || !schema.isObject()) {
            return Mono.error(new IllegalArgumentException("schema must be a JSON object"));
        }
        // Capture the pre-existing schema (if any) so the audit entry shows before → after.
        Mono<JsonNode> beforeMono = registries.findActive(tenantId, tableName)
                .map(reg -> parseSchemaSafely(reg.getSchemaDefinition()))
                .defaultIfEmpty(mapper.nullNode());

        Mono<VirtualTableRegistry> upsert = registries.findActive(tenantId, tableName)
                .flatMap(active -> {
                    long next = active.getConfigVersion() + 1;
                    return registries.deactivateAll(tenantId, tableName).thenReturn(next);
                })
                .switchIfEmpty(Mono.just(1L))
                .flatMap(version -> {
                    VirtualTableRegistry row = new VirtualTableRegistry();
                    row.setId(UUID.randomUUID());
                    row.setTenantId(tenantId);
                    row.setTableName(tableName);
                    row.setSchemaDefinition(Json.of(schema.toString()));
                    row.setConfigVersion(version);
                    row.setActive(true);
                    row.setCreatedAt(Instant.now());
                    row.setCreatedBy(subject);
                    return registries.save(row);
                });
        return tx.transactional(beforeMono.zipWith(upsert)
                .flatMap(t -> {
                    JsonNode before = t.getT1();
                    VirtualTableRegistry saved = t.getT2();
                    ConfigAuditService.Operation op = (before == null || before.isNull())
                            ? ConfigAuditService.Operation.DECLARE_TABLE
                            : ConfigAuditService.Operation.REVISE_TABLE;
                    return audit.record(tenantId, tableName, op, null, before, schema, subject, actorTrack, requestId)
                            .thenReturn(saved);
                }))
                .map(this::toView);
    }

    public Flux<VirtualTableView> listTables(String tenantId) {
        return registries.findAllActive(tenantId).map(this::toView);
    }

    public Mono<VirtualTableView> getActive(String tenantId, String tableName) {
        return registries.findActive(tenantId, tableName)
                .switchIfEmpty(Mono.error(new VirtualTableNotFoundException(tenantId, tableName)))
                .map(this::toView);
    }

    // -----------------------------------------------------------------------
    // Ingestion
    // -----------------------------------------------------------------------

    /**
     * Atomically validates + versions + persists one record.
     *
     * @return the newly stored {@link EntryView}, or fails with {@link IdempotentDuplicateException}
     *         when the same payload-hash is already the active head.
     */
    public Mono<EntryView> ingest(String tenantId, String tableName, String recordKey, JsonNode payload,
                                  String subject, String actorTrack, String requestId) {
        Mono<EntryView> work = registries.findActive(tenantId, tableName)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Ingest failed: no active virtual table tenant={} table={} recordKey={}",
                            tenantId, tableName, recordKey);
                    return Mono.error(new VirtualTableNotFoundException(tenantId, tableName));
                }))
                .flatMap(reg -> {
                    JsonNode schemaNode = parseSchemaSafely(reg.getSchemaDefinition());
                    try {
                        validator.validateOrThrow(schemaNode, payload);
                    } catch (io.valkeyry.config.error.SchemaValidationException sve) {
                        // Log here with full context — the global handler only sees the bare
                        // exception otherwise. Downstream callers (e.g. the batch endpoint)
                        // re-catch and turn this into a per-row error.
                        log.warn("Schema validation failed tenant={} table={} recordKey={} violations={}",
                                tenantId, tableName, recordKey, sve.violations());
                        throw sve;
                    }
                    String hash = PayloadFingerprint.sha256(payload);
                    return entries.findDuplicate(tenantId, tableName, recordKey, hash)
                            .flatMap(dup -> audit.record(tenantId, tableName,
                                            ConfigAuditService.Operation.DEDUP_SKIP,
                                            recordKey, payload, payload, subject, actorTrack, requestId)
                                    .then(Mono.<EntryView>error(new IdempotentDuplicateException(recordKey, hash))))
                            .switchIfEmpty(insertNextVersion(tenantId, tableName, recordKey, payload, hash,
                                    subject, actorTrack, requestId));
                });
        return tx.transactional(work);
    }

    private Mono<EntryView> insertNextVersion(String tenantId, String tableName, String recordKey,
                                              JsonNode payload, String hash, String subject,
                                              String actorTrack, String requestId) {
        // Capture previous payload (if any) for the audit before-value.
        Mono<JsonNode> beforeMono = entries.findLatest(tenantId, tableName, recordKey)
                .map(e -> parsePayloadSafely(e.getData()))
                .defaultIfEmpty(mapper.nullNode());

        return beforeMono.flatMap(before -> entries.maxVersion(tenantId, tableName, recordKey)
                .defaultIfEmpty(0L)
                .flatMap(prev -> entries.markPreviousLatestStale(tenantId, tableName, recordKey)
                        .thenReturn(prev))
                .flatMap(prev -> {
                    VirtualTableEntry row = new VirtualTableEntry();
                    row.setId(UUID.randomUUID());
                    row.setTenantId(tenantId);
                    row.setTableName(tableName);
                    row.setRecordKey(recordKey);
                    row.setPayloadHash(hash);
                    row.setVersion(prev + 1);
                    row.setLatest(true);
                    row.setData(Json.of(payload.toString()));
                    row.setCreatedAt(Instant.now());
                    row.setCreatedBy(subject);
                    return entries.save(row);
                })
                .flatMap(saved -> audit.record(tenantId, tableName,
                                ConfigAuditService.Operation.INGEST_RECORD,
                                recordKey, before, payload, subject, actorTrack, requestId)
                        .thenReturn(saved))
                .map(this::toView)
                .doOnNext(v -> log.debug("Ingested {}/{}/{} v{}", tenantId, tableName, recordKey, v.version())));
    }

    // -----------------------------------------------------------------------
    // Soft-delete & rollback
    // -----------------------------------------------------------------------

    /**
     * Soft-deletes the current head of a logical record.
     *
     * <p>Marks the current {@code is_latest = true} row as superseded ({@code is_latest = false})
     * and writes a {@code DELETE_RECORD} audit row whose {@code beforeValue} captures the payload
     * that was deleted. No new {@code virtual_table_entry} row is inserted, so {@link #browse} naturally
     * stops returning the record. History remains intact (older versions stay queryable).</p>
     *
     * <p>Failing with {@link VirtualTableNotFoundException} when no head exists for that key.</p>
     */
    /**
     * Soft-deletes every version of a table.
     *
     * <p>Uses {@link VirtualTableRegistryRepository#deactivateAll(String, String)} to flip the
     * {@code is_active} flag on every registry row for the table. Existing entries stay in
     * place (the entry table is not touched) — they're effectively orphaned. A `DELETE_TABLE`
     * audit row is recorded so the action is reversible from the timeline.</p>
     */
    public Mono<Void> softDeleteTable(String tenantId, String tableName,
                                      String subject, String actorTrack, String requestId) {
        Mono<Void> work = registries.findActive(tenantId, tableName)
                .switchIfEmpty(Mono.error(new VirtualTableNotFoundException(tenantId, tableName)))
                .flatMap(active -> {
                    JsonNode before = parseSchemaSafely(active.getSchemaDefinition());
                    return registries.deactivateAll(tenantId, tableName)
                            .then(audit.record(tenantId, tableName,
                                    ConfigAuditService.Operation.DELETE_TABLE,
                                    null, before, mapper.nullNode(),
                                    subject, actorTrack, requestId));
                })
                .then();
        return tx.transactional(work);
    }

    /**
     * Rolls back a {@code DELETE_TABLE} audit row by re-declaring the schema that was active
     * at the time of the delete.
     *
     * <p>The audit row's {@code beforeValue} is the schema JSON itself (see
     * {@link #softDeleteTable}). We simply call {@link #declareTable} with that payload —
     * which writes a new registry row and a {@code REVISE_TABLE} audit entry, so the action
     * is itself reversible.</p>
     *
     * <p>If a NEW table with the same name has been declared since the delete (someone
     * re-created it manually), {@code declareTable} will revise that one to the restored
     * schema. Entry-history is unaffected — the (now-orphaned) entries from the original
     * table become visible again because the registry row is back.</p>
     */
    public Mono<VirtualTableView> rollbackTableDeletion(String tenantId, String tableName, JsonNode schema,
                                                       String subject, String actorTrack, String requestId) {
        return declare(tenantId, tableName, schema, subject, actorTrack, requestId);
    }

    public Mono<Void> softDelete(String tenantId, String tableName, String recordKey,
                                 String subject, String actorTrack, String requestId) {
        Mono<Void> work = entries.findLatest(tenantId, tableName, recordKey)
                .switchIfEmpty(Mono.error(new VirtualTableNotFoundException(tenantId, tableName + "/" + recordKey)))
                .flatMap(head -> {
                    JsonNode before = parsePayloadSafely(head.getData());
                    return entries.markPreviousLatestStale(tenantId, tableName, recordKey)
                            .then(audit.record(tenantId, tableName,
                                    ConfigAuditService.Operation.DELETE_RECORD,
                                    recordKey, before, mapper.nullNode(),
                                    subject, actorTrack, requestId));
                })
                .then();
        return tx.transactional(work);
    }

    /**
     * Restores the value an audit row's {@code beforeValue} pointed to by re-ingesting it
     * as a new version of the same logical record.
     *
     * <p>The replay goes through the standard ingest path, so JSON Schema validation and the
     * Idempotency Guard both apply. The new audit row is tagged {@code ROLLBACK} rather than
     * {@code INGEST_RECORD} so the timeline can highlight it. Tables-level audit ops
     * ({@code DECLARE_TABLE}/{@code REVISE_TABLE}) are not rollback-able through this path.</p>
     */
    public Mono<EntryView> rollback(String tenantId, String tableName, String recordKey, JsonNode beforeValue,
                                    String subject, String actorTrack, String requestId) {
        if (beforeValue == null || beforeValue.isNull()) {
            return Mono.error(new IllegalArgumentException("Cannot roll back: audit entry has no prior value"));
        }
        Mono<EntryView> work = registries.findActive(tenantId, tableName)
                .switchIfEmpty(Mono.error(new VirtualTableNotFoundException(tenantId, tableName)))
                .flatMap(reg -> {
                    JsonNode schemaNode = parseSchemaSafely(reg.getSchemaDefinition());
                    validator.validateOrThrow(schemaNode, beforeValue);
                    String hash = PayloadFingerprint.sha256(beforeValue);
                    return entries.findDuplicate(tenantId, tableName, recordKey, hash)
                            .flatMap(dup -> audit.record(tenantId, tableName,
                                            ConfigAuditService.Operation.DEDUP_SKIP,
                                            recordKey, beforeValue, beforeValue, subject, actorTrack, requestId)
                                    .then(Mono.<EntryView>error(new IdempotentDuplicateException(recordKey, hash))))
                            .switchIfEmpty(insertRollbackVersion(tenantId, tableName, recordKey, beforeValue, hash,
                                    subject, actorTrack, requestId));
                });
        return tx.transactional(work);
    }

    private Mono<EntryView> insertRollbackVersion(String tenantId, String tableName, String recordKey,
                                                  JsonNode payload, String hash, String subject,
                                                  String actorTrack, String requestId) {
        Mono<JsonNode> beforeMono = entries.findLatest(tenantId, tableName, recordKey)
                .map(e -> parsePayloadSafely(e.getData()))
                .defaultIfEmpty(mapper.nullNode());

        return beforeMono.flatMap(before -> entries.maxVersion(tenantId, tableName, recordKey)
                .defaultIfEmpty(0L)
                .flatMap(prev -> entries.markPreviousLatestStale(tenantId, tableName, recordKey)
                        .thenReturn(prev))
                .flatMap(prev -> {
                    VirtualTableEntry row = new VirtualTableEntry();
                    row.setId(UUID.randomUUID());
                    row.setTenantId(tenantId);
                    row.setTableName(tableName);
                    row.setRecordKey(recordKey);
                    row.setPayloadHash(hash);
                    row.setVersion(prev + 1);
                    row.setLatest(true);
                    row.setData(Json.of(payload.toString()));
                    row.setCreatedAt(Instant.now());
                    row.setCreatedBy(subject);
                    return entries.save(row);
                })
                .flatMap(saved -> audit.record(tenantId, tableName,
                                ConfigAuditService.Operation.ROLLBACK,
                                recordKey, before, payload, subject, actorTrack, requestId)
                        .thenReturn(saved))
                .map(this::toView)
                .doOnNext(v -> log.debug("Rolled back {}/{}/{} to v{}", tenantId, tableName, recordKey, v.version())));
    }

    // -----------------------------------------------------------------------
    // Read / search / history
    // -----------------------------------------------------------------------
    public Mono<EntryView> getLatest(String tenantId, String tableName, String recordKey) {
        return entries.findLatest(tenantId, tableName, recordKey).map(this::toView);
    }

    public Flux<EntryView> history(String tenantId, String tableName, String recordKey) {
        return entries.findHistory(tenantId, tableName, recordKey).map(this::toView);
    }

    public Flux<EntryView> browse(String tenantId, String tableName, int limit, int offset) {
        return entries.findLatestPaged(tenantId, tableName, limit, offset).map(this::toView);
    }

    public Flux<EntryView> search(String tenantId, String tableName, JsonNode criteria, int limit, int offset) {
        String criteriaJson = (criteria == null || criteria.isNull()) ? "{}" : criteria.toString();
        return entries.search(tenantId, tableName, criteriaJson, limit, offset).map(this::toView);
    }

    /**
     * PL/SQL-style multi-condition search compiled by {@link PredicateCompiler}.
     *
     * <p>The list of {@link PredicateCompiler.Condition} rows is turned into a single
     * parameterised {@code WHERE} fragment that's appended to the standard
     * {@code tenant_id / table_name / is_latest=true} guard, then executed against the
     * {@code virtual_table_entry} table via R2DBC's {@link DatabaseClient}.</p>
     */
    public Flux<EntryView> searchAdvanced(String tenantId, String tableName,
                                          List<PredicateCompiler.Condition> conditions,
                                          int limit, int offset) {
        PredicateCompiler.Compiled compiled = PredicateCompiler.compile(conditions);
        String sql = """
                SELECT * FROM virtual_table_entry
                 WHERE tenant_id  = :tenantId
                   AND table_name = :tableName
                   AND is_latest  = true
                   AND (%s)
                 ORDER BY record_key
                 LIMIT :limit OFFSET :offset
                """.formatted(compiled.sql());

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("tenantId", tenantId)
                .bind("tableName", tableName)
                .bind("limit", limit)
                .bind("offset", offset);
        for (Map.Entry<String, Object> e : compiled.params().entrySet()) {
            spec = e.getValue() == null
                    ? spec.bindNull(e.getKey(), String.class)
                    : spec.bind(e.getKey(), e.getValue());
        }
        return spec.map((row, meta) -> {
            VirtualTableEntry entry = new VirtualTableEntry();
            entry.setId(row.get("id", UUID.class));
            entry.setTenantId(row.get("tenant_id", String.class));
            entry.setTableName(row.get("table_name", String.class));
            entry.setRecordKey(row.get("record_key", String.class));
            Long ver = row.get("version", Long.class);
            entry.setVersion(ver == null ? 0L : ver);
            entry.setLatest(Boolean.TRUE.equals(row.get("is_latest", Boolean.class)));
            entry.setPayloadHash(row.get("payload_hash", String.class));
            entry.setData(row.get("data", Json.class));
            Instant createdAt = row.get("created_at", Instant.class);
            if (createdAt != null) entry.setCreatedAt(createdAt);
            entry.setCreatedBy(row.get("created_by", String.class));
            return entry;
        }).all().map(this::toView);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private VirtualTableView toView(VirtualTableRegistry r) {
        return new VirtualTableView(
                r.getId(), r.getTenantId(), r.getTableName(), r.getConfigVersion(),
                r.isActive(), parseSchemaSafely(r.getSchemaDefinition()),
                r.getCreatedAt(), r.getCreatedBy());
    }

    private EntryView toView(VirtualTableEntry e) {
        return new EntryView(
                e.getId(), e.getTenantId(), e.getTableName(), e.getRecordKey(),
                e.getVersion(), e.isLatest(), e.getPayloadHash(),
                parseSchemaSafely(e.getData()), e.getCreatedAt(), e.getCreatedBy());
    }

    private JsonNode parseSchemaSafely(Json j) {
        if (j == null) return mapper.createObjectNode();
        try { return mapper.readTree(j.asString()); }
        catch (Exception ex) { throw new IllegalStateException("Stored JSON is corrupt", ex); }
    }

    private JsonNode parsePayloadSafely(Json j) {
        if (j == null) return mapper.nullNode();
        try { return mapper.readTree(j.asString()); }
        catch (Exception ex) { return mapper.nullNode(); }
    }
}
