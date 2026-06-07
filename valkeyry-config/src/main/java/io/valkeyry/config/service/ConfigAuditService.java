package io.valkeyry.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import io.valkeyry.config.api.AuditView;
import io.valkeyry.config.domain.ConfigAuditEntry;
import io.valkeyry.config.repo.ConfigAuditRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit ledger for every config-changing call.
 *
 * <p>Each {@link #record(String, String, Operation, String, JsonNode, JsonNode, String, String, String)}
 * invocation is non-blocking and idempotent — failures here NEVER fail the original operation
 * (auditing should not block business correctness). Use {@link #findRecent(String, int, int)}
 * and friends from the {@code AuditController} to browse history.</p>
 */
@Service
public class ConfigAuditService {

    public enum Operation { DECLARE_TABLE, REVISE_TABLE, DELETE_TABLE, INGEST_RECORD, DEDUP_SKIP, DELETE_RECORD, ROLLBACK }

    private final ConfigAuditRepository repo;
    private final ObjectMapper mapper;
    private final AuditWebhookPublisher webhook;

    public ConfigAuditService(ConfigAuditRepository repo, ObjectMapper mapper, AuditWebhookPublisher webhook) {
        this.repo = repo;
        this.mapper = mapper;
        this.webhook = webhook;
    }

    public Mono<ConfigAuditEntry> record(String tenantId, String tableName, Operation op,
                                         String recordKey, JsonNode before, JsonNode after,
                                         String actor, String actorTrack, String requestId) {
        ConfigAuditEntry e = new ConfigAuditEntry();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenantId);
        e.setTableName(tableName);
        e.setOperation(op.name());
        e.setRecordKey(recordKey);
        e.setBeforeValue(before == null || before.isNull() ? null : Json.of(before.toString()));
        e.setAfterValue(Json.of((after == null ? mapper.nullNode() : after).toString()));
        e.setActor(actor == null ? "<unknown>" : actor);
        e.setActorTrack(actorTrack == null ? "ANONYMOUS" : actorTrack);
        e.setChangedAt(Instant.now());
        e.setRequestId(requestId);
        return repo.save(e)
                // Fan-out asynchronously after successful persistence. Webhook failure must
                // NOT undo the audit insert, hence onErrorResume → empty. Subscription on
                // a separate publisher chain decouples webhook latency from the caller's reply.
                .doOnSuccess(saved -> webhook.publish(saved)
                        .onErrorResume(ex -> Mono.empty())
                        .subscribe())
                // Likewise, auditing failures must never cascade into the business operation.
                .onErrorResume(ex -> Mono.empty());
    }

    public Flux<AuditView> findRecent(String tenantId, int limit, int offset) {
        return repo.findByTenant(tenantId, limit, offset).map(this::toView);
    }

    public Flux<AuditView> findForTable(String tenantId, String tableName, int limit, int offset) {
        return repo.findByTable(tenantId, tableName, limit, offset).map(this::toView);
    }

    public Flux<AuditView> findForRecord(String tenantId, String tableName, String recordKey, int limit, int offset) {
        return repo.findByRecord(tenantId, tableName, recordKey, limit, offset).map(this::toView);
    }

    public Flux<AuditView> findForActor(String tenantId, String actor, int limit, int offset) {
        return repo.findByActor(tenantId, actor, limit, offset).map(this::toView);
    }

    private AuditView toView(ConfigAuditEntry e) {
        return new AuditView(
                e.getId(), e.getTenantId(), e.getTableName(), e.getOperation(),
                e.getRecordKey(), parse(e.getBeforeValue()), parse(e.getAfterValue()),
                e.getActor(), e.getActorTrack(), e.getChangedAt(), e.getRequestId());
    }

    private JsonNode parse(Json j) {
        if (j == null) return null;
        try { return mapper.readTree(j.asString()); }
        catch (Exception ex) { return mapper.createObjectNode().put("_parse_error", ex.getMessage()); }
    }
}
