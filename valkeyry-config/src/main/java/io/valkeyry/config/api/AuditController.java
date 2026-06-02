package io.valkeyry.config.api;

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
public class AuditController {

    private final ConfigAuditService audit;
    private final TenantAccessGuard guard;

    public AuditController(ConfigAuditService audit, TenantAccessGuard guard) {
        this.audit = audit;
        this.guard = guard;
    }

    @GetMapping
    public Flux<AuditView> browse(@PathVariable String tenantId,
                                  @RequestParam(required = false) String tableName,
                                  @RequestParam(required = false) String recordKey,
                                  @RequestParam(required = false) String actor,
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
