package io.valkeyry.config.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.ConfigAuditService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * Browse the audit ledger.
 *
 * <p>Read-only, gated by {@link TenantAccessGuard}. Available filters:</p>
 * <ul>
 *   <li>{@code /audit} — last N entries across all tables;</li>
 *   <li>{@code ?tableName=…} — narrow to one virtual table;</li>
 *   <li>{@code ?tableName=…&recordKey=…} — full history of a single logical record;</li>
 *   <li>{@code ?actor=alice@acme.io} — every change a given subject performed.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/audit")
@Tag(name = "Audit", description = "Browse the append-only change ledger.")
public class AuditController {

    private final ConfigAuditService audit;
    private final TenantAccessGuard guard;

    public AuditController(ConfigAuditService audit, TenantAccessGuard guard) {
        this.audit = audit;
        this.guard = guard;
    }

    @GetMapping
    @Operation(summary = "Browse audit entries",
               description = "Returns the most-recent N audit rows. Optional query params narrow the result to a single table, a single record's full history, or every change by a given actor.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Entries returned (may be empty)"),
        @ApiResponse(responseCode = "403", description = "Caller has no access to this tenant")
    })
    public Flux<AuditView> browse(@PathVariable String tenantId,
                                  @Parameter(description = "Filter to one table") @RequestParam(required = false) String tableName,
                                  @Parameter(description = "Combined with tableName, returns one record's full history") @RequestParam(required = false) String recordKey,
                                  @Parameter(description = "Filter to one actor (email / subject)") @RequestParam(required = false) String actor,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0")  int offset,
                                  Authentication auth) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        int safeOffset = Math.max(0, offset);
        return guard.check(auth, tenantId).thenMany(dispatch(tenantId, tableName, recordKey, actor, safeLimit, safeOffset));
    }

    private Flux<AuditView> dispatch(String tenantId, String tableName, String recordKey, String actor,
                                     int limit, int offset) {
        if (actor != null && !actor.isBlank())     return audit.findForActor(tenantId, actor, limit, offset);
        if (tableName != null && !tableName.isBlank() && recordKey != null && !recordKey.isBlank())
            return audit.findForRecord(tenantId, tableName, recordKey, limit, offset);
        if (tableName != null && !tableName.isBlank()) return audit.findForTable(tenantId, tableName, limit, offset);
        return audit.findRecent(tenantId, limit, offset);
    }
}
