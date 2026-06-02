package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.VirtualTableService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unified PL/SQL-style query endpoint.
 *
 * <p>One single shape for "find me rows under this tenant" — no matter which
 * table, which column, which operator:</p>
 *
 * <pre>
 * POST /api/v1/tenants/{tenantId}/query
 * Content-Type: application/json
 *
 * {
 *   "table":  "users",
 *   "field":  "recordKey",          // or "data.&lt;col&gt;"
 *   "op":     "equals",             // equals | contains | startsWith
 *   "value":  "alice@acme.io",
 *   "fields": "recordKey,data.role" // optional projection (CSV)
 * }
 * </pre>
 *
 * <p>This is a thin façade over the existing {@code VirtualTableService}:</p>
 * <ul>
 *   <li><b>recordKey · equals</b> → {@code service.getLatest(tenant, table, key)} (PK O(1) lookup)</li>
 *   <li><b>data.&lt;col&gt; · &lt;op&gt;</b> → translated into the existing
 *       {@code service.search(tenant, table, criteria)} body</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@Tag(name = "Unified query",
     description = "PL/SQL-style WHERE-clause search across any field (including recordKey).")
public class UnifiedQueryController {

    private final VirtualTableService service;
    private final TenantAccessGuard guard;
    private final ObjectMapper mapper;

    public UnifiedQueryController(VirtualTableService service, TenantAccessGuard guard, ObjectMapper mapper) {
        this.service = service;
        this.guard = guard;
        this.mapper = mapper;
    }

    @PostMapping("/query")
    @Operation(summary = "Run a PL/SQL-style query",
               description = "Body: `{ table, field, op, value, fields? }`. `field=recordKey` triggers an O(1) lookup; `field=data.<col>` falls back to the JSONB search engine.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Matching rows (may be empty)"),
        @ApiResponse(responseCode = "400", description = "`table` missing or `op` not supported")
    })
    public Flux<JsonNode> query(@PathVariable String tenantId,
                                @RequestBody QueryRequest req,
                                Authentication auth) {
        if (req.table() == null || req.table().isBlank()) {
            return Flux.error(new IllegalArgumentException("'table' is required"));
        }
        String field = (req.field() == null) ? "" : req.field().trim();
        String op    = (req.op() == null || req.op().isBlank()) ? "equals" : req.op().trim();
        JsonNode value = req.value();

        // No predicate → list with projection.
        if (field.isEmpty() || value == null || value.isNull()) {
            return guard.check(auth, tenantId)
                    .thenMany(service.browse(tenantId, req.table(), 1000, 0))
                    .map(v -> FieldProjection.apply(mapper.valueToTree(v), req.fields(), mapper));
        }

        // Fast path — recordKey equals → PK lookup
        if ("recordKey".equals(field) && "equals".equals(op)) {
            return guard.check(auth, tenantId)
                    .then(service.getLatest(tenantId, req.table(), value.asText()))
                    .map(v -> FieldProjection.apply(mapper.valueToTree(v), req.fields(), mapper))
                    .flux()
                    .onErrorResume(e -> Flux.empty());
        }

        // Generic path — build a /search body the service already understands.
        ObjectNode criteria = mapper.createObjectNode();
        ObjectNode opNode   = mapper.createObjectNode();
        String column = field.startsWith("data.") ? field.substring(5) : field;
        opNode.set(column, value);
        criteria.set(op, opNode);

        return guard.check(auth, tenantId)
                .thenMany(service.search(tenantId, req.table(), criteria, 1000, 0))
                .map(v -> FieldProjection.apply(mapper.valueToTree(v), req.fields(), mapper));
    }

    /** Body shape for {@link #query}. {@code value} stays as a raw {@link JsonNode} so callers
     *  can match against booleans, integers, arrays — not just strings. */
    public record QueryRequest(String table, String field, String op,
                               JsonNode value, String fields) {}
}
