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
    @Operation(summary = "Rollback an entry to a prior audit state",
               description = "Re-ingests the `beforeValue` of the named audit row as a new version of the original record. Only record-level audit entries (with a non-null `recordKey` and `beforeValue`) are rollbackable.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Rollback applied — a new version was written"),
        @ApiResponse(responseCode = "400", description = "Audit row is not rollbackable (table-level or has no prior value)"),
        @ApiResponse(responseCode = "404", description = "Audit row not found in this tenant"),
        @ApiResponse(responseCode = "409", description = "Idempotency-skip — record already at that state")
    })
    public Mono<ResponseEntity<EntryView>> rollback(@PathVariable String tenantId,
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
                    if (entry.getRecordKey() == null || entry.getRecordKey().isBlank()) {
                        return Mono.error(new IllegalArgumentException(
                                "Cannot roll back table-level audit op " + op + " — only record entries are restorable"));
                    }
                    JsonNode before = parse(entry.getBeforeValue());
                    if (before == null || before.isNull()) {
                        return Mono.error(new IllegalArgumentException(
                                "Cannot roll back: audit entry " + auditId + " has no prior value"));
                    }
                    return tables.rollback(tenantId, entry.getTableName(), entry.getRecordKey(),
                            before, auth.getName(), track, requestId);
                })
                .map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
    }

    private JsonNode parse(Json j) {
        if (j == null) return null;
        try { return mapper.readTree(j.asString()); }
        catch (Exception ex) { return null; }
    }
}
