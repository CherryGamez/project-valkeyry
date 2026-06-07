package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.error.VirtualTableNotFoundException;
import io.valkeyry.config.repo.ConfigAuditRepository;
import io.valkeyry.config.security.AuthTrack;
import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.VirtualTableService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Push-button rollback for any record-level audit entry.
 *
 * <p>POST {@code /api/v1/tenants/{tenantId}/audit/{auditId}/rollback}:</p>
 * <ol>
 *   <li>Loads the audit row (must belong to {@code tenantId});</li>
 *   <li>Reads its {@code beforeValue} as the desired restored state;</li>
 *   <li>Re-ingests that payload through the normal versioning flow, but tagged
 *       with {@link io.valkeyry.config.service.ConfigAuditService.Operation#ROLLBACK}.</li>
 * </ol>
 *
 * <p>Only record-level operations ({@code INGEST_RECORD}, {@code DELETE_RECORD}, {@code ROLLBACK})
 * are rollback-able — table-schema events are out of scope.</p>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/audit")
@Tag(name = "Audit rollback", description = "Restore an entry to a prior value referenced by an audit row.")
public class AuditRollbackController {

    private final ConfigAuditRepository auditRepo;
    private final VirtualTableService tables;
    private final TenantAccessGuard guard;
    private final ObjectMapper mapper;

    public AuditRollbackController(ConfigAuditRepository auditRepo,
                                   VirtualTableService tables,
                                   TenantAccessGuard guard,
                                   ObjectMapper mapper) {
        this.auditRepo = auditRepo;
        this.tables = tables;
        this.guard = guard;
        this.mapper = mapper;
    }

    @PostMapping("/{auditId}/rollback")
    @Operation(summary = "Rollback an entry or a deleted table to a prior audit state",
               description = "Re-applies the `beforeValue` of the named audit row. For record-level ops "
                           + "({@code INGEST_RECORD}, {@code DELETE_RECORD}, {@code ROLLBACK}) the value is "
                           + "re-ingested as a new version of the original record. For {@code DELETE_TABLE} "
                           + "the value (a schema snapshot) is re-declared — the registry row comes back and "
                           + "orphaned entries become visible again. {@code DECLARE_TABLE} / {@code REVISE_TABLE} "
                           + "events are not yet rollback-able through this path.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Rollback applied — a new version (or restored table) was written"),
        @ApiResponse(responseCode = "400", description = "Audit row is not rollbackable (declare/revise event, or no prior value)"),
        @ApiResponse(responseCode = "404", description = "Audit row not found in this tenant"),
        @ApiResponse(responseCode = "409", description = "Idempotency-skip — record already at that state")
    })
    public Mono<ResponseEntity<?>> rollback(@PathVariable String tenantId,
                                            @PathVariable UUID auditId,
                                            Authentication auth,
                                            ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        return guard.check(auth, tenantId)
                .then(auditRepo.findByTenantAndId(tenantId, auditId))
                .switchIfEmpty(Mono.error(new VirtualTableNotFoundException(tenantId, "audit/" + auditId)))
                .flatMap(entry -> {
                    String op = entry.getOperation();
                    JsonNode before = parse(entry.getBeforeValue());
                    if (before == null || before.isNull()) {
                        return Mono.error(new IllegalArgumentException(
                                "Cannot roll back: audit entry " + auditId + " has no prior value"));
                    }
                    // Table-level: re-declare the schema from beforeValue. No recordKey involved.
                    if ("DELETE_TABLE".equals(op)) {
                        return tables.rollbackTableDeletion(tenantId, entry.getTableName(), before,
                                        auth.getName(), track, requestId)
                                .<ResponseEntity<?>>map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
                    }
                    // Record-level: re-ingest the prior payload.
                    if (entry.getRecordKey() == null || entry.getRecordKey().isBlank()) {
                        return Mono.error(new IllegalArgumentException(
                                "Cannot roll back table-level audit op " + op + " — only DELETE_TABLE and record entries are restorable"));
                    }
                    return tables.rollback(tenantId, entry.getTableName(), entry.getRecordKey(),
                                    before, auth.getName(), track, requestId)
                            .<ResponseEntity<?>>map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
                });
    }

    private JsonNode parse(Json j) {
        if (j == null) return null;
        try { return mapper.readTree(j.asString()); }
        catch (Exception ex) { return null; }
    }
}
