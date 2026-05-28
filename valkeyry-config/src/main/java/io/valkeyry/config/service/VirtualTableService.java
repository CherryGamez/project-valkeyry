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
import io.valkeyry.config.validation.JsonSchemaValidatorService;
import io.valkeyry.config.validation.PayloadFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
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

    public VirtualTableService(VirtualTableRegistryRepository registries,
                               VirtualTableEntryRepository entries,
                               JsonSchemaValidatorService validator,
                               ObjectMapper mapper,
                               TransactionalOperator tx,
                               ConfigAuditService audit) {
        this.registries = registries;
        this.entries = entries;
        this.validator = validator;
        this.mapper = mapper;
        this.tx = tx;
        this.audit = audit;
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
                .switchIfEmpty(Mono.error(new VirtualTableNotFoundException(tenantId, tableName)))
                .flatMap(reg -> {
                    JsonNode schemaNode = parseSchemaSafely(reg.getSchemaDefinition());
                    validator.validateOrThrow(schemaNode, payload);
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
