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
import io.valkeyry.config.service.query.PredicateCompiler;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

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

    // ────────────────────────────────────────────────────────────────────────
    //  PL/SQL-style multi-condition query  (POST /query2)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Advanced multi-condition WHERE-builder.
     *
     * <p>Each row in {@link AdvancedQueryRequest#conditions()} carries its own connector
     * ({@code AND}/{@code OR}) that joins it to the row before; the first row's connector
     * is ignored. Supported ops are listed on {@link PredicateCompiler}.</p>
     *
     * <pre>
     * POST /api/v1/tenants/{tenantId}/query2
     * {
     *   "table": "users",
     *   "conditions": [
     *     { "field": "data.email",  "op": "endsWith",  "value": "@acme.io" },
     *     { "field": "data.role",   "op": "in",        "value": ["admin","writer"], "connector": "AND" },
     *     { "field": "data.score",  "op": "between",   "value": 10, "value2": 100,  "connector": "OR" }
     *   ],
     *   "fields": "recordKey,data.email",
     *   "limit":  100,
     *   "offset": 0
     * }
     * </pre>
     */
    @PostMapping("/query2")
    @Operation(summary = "Run a multi-condition PL/SQL-style query",
               description = "Accepts a list of conditions joined by per-row AND/OR connectors. Supports equals/notEquals, contains, startsWith/endsWith, regex, in/notIn, gt/gte/lt/lte/between, isNull/isNotNull, before/after/onDate/betweenDates.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Matching rows (may be empty)"),
        @ApiResponse(responseCode = "400", description = "Unsupported op, invalid field name, or malformed value")
    })
    public Flux<JsonNode> queryAdvanced(@PathVariable String tenantId,
                                        @RequestBody AdvancedQueryRequest req,
                                        Authentication auth) {
        if (req.table() == null || req.table().isBlank()) {
            return Flux.error(new IllegalArgumentException("'table' is required"));
        }
        int limit  = req.limit()  == null || req.limit()  <= 0 ? 1000 : req.limit();
        int offset = req.offset() == null || req.offset() <  0 ?    0 : req.offset();

        List<PredicateCompiler.Condition> conditions = new ArrayList<>();
        if (req.conditions() != null) {
            for (AdvancedCondition c : req.conditions()) {
                if (c == null) continue;
                conditions.add(new PredicateCompiler.Condition(
                        c.field(), c.op(), c.value(), c.value2(), c.connector()));
            }
        }
        return guard.check(auth, tenantId)
                .thenMany(service.searchAdvanced(tenantId, req.table(), conditions, limit, offset))
                .map(v -> FieldProjection.apply(mapper.valueToTree(v), req.fields(), mapper));
    }

    /** Body shape for {@link #queryAdvanced}. */
    public record AdvancedQueryRequest(String table,
                                       List<AdvancedCondition> conditions,
                                       String fields,
                                       Integer limit,
                                       Integer offset) {}

    /** One condition row in {@link AdvancedQueryRequest#conditions()}.
     *
     *  <ul>
     *    <li>{@code field}: {@code recordKey} or {@code data.&lt;col&gt;} (dotted paths OK).</li>
     *    <li>{@code op}: see {@link PredicateCompiler} JavaDoc.</li>
     *    <li>{@code value}: primary value (string/number/array/null).</li>
     *    <li>{@code value2}: required by {@code between} and {@code betweenDates}.</li>
     *    <li>{@code connector}: {@code "AND"} (default) or {@code "OR"}. Ignored on the first row.</li>
     *  </ul>
     */
    public record AdvancedCondition(String field, String op,
                                    JsonNode value, JsonNode value2,
                                    String connector) {}
}
