package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.error.IdempotentDuplicateException;
import io.valkeyry.config.security.AuthTrack;
import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.VirtualTableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * REST API for the headless schema registry.
 *
 * <p>Every route lives under {@code /api/v1/tenants/{tenantId}/…} and is gated by the
 * {@link TenantAccessGuard}. Both Track-1 (OIDC) and Track-2 (LDAP/API-key) callers transit
 * through this controller — the difference is purely in how {@code Authentication} was built.
 * Write operations additionally require the {@code ROLE_VALKEYRY_WRITER} authority enforced at
 * the security-chain level.</p>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@Tag(name = "Virtual tables & entries",
     description = "Declare JSON-Schema-backed virtual tables and ingest/read/delete records under them.")
public class VirtualTableController {

    private final VirtualTableService service;
    private final TenantAccessGuard guard;
    private final ObjectMapper mapper;

    public VirtualTableController(VirtualTableService service, TenantAccessGuard guard, ObjectMapper mapper) {
        this.service = service;
        this.guard = guard;
        this.mapper = mapper;
    }

    // ---------------- Tables ----------------

    @GetMapping("/tables")
    @Operation(summary = "List all virtual tables for a tenant",
               description = "Returns the currently-active version of every table declared under the tenant.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tables returned (may be empty)"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Caller has no access to this tenant")
    })
    public Flux<VirtualTableView> listTables(
            @Parameter(description = "Tenant identifier (e.g. demo-tenant)") @PathVariable String tenantId,
            Authentication auth) {
        return guard.check(auth, tenantId).thenMany(service.listTables(tenantId));
    }

    @PostMapping("/tables")
    @Operation(summary = "Declare or revise a virtual table",
               description = "First call creates the table. Subsequent calls with the same name revise the schema as a new version. The body's `schema` field is any Draft 2020-12 JSON-Schema — see the UI's Schema Builder for the supported widget set (dropdown/checkbox/multi-choice).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Table declared/revised"),
        @ApiResponse(responseCode = "400", description = "Schema is malformed JSON or missing required keys"),
        @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_VALKEYRY_WRITER")
    })
    public Mono<ResponseEntity<VirtualTableView>> declare(@PathVariable String tenantId,
                                                          @Valid @RequestBody DeclareVirtualTableRequest req,
                                                          Authentication auth,
                                                          ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        return guard.check(auth, tenantId)
                .then(service.declare(tenantId, req.tableName(), req.schema(), auth.getName(), track, requestId))
                .map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
    }

    @GetMapping("/tables/{name}")
    @Operation(summary = "Fetch a single virtual table by name",
               description = "Returns the active schema + metadata for the named table.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Table exists"),
        @ApiResponse(responseCode = "404", description = "Table not found in this tenant")
    })
    public Mono<VirtualTableView> getTable(@PathVariable String tenantId,
                                           @PathVariable("name") String tableName,
                                           Authentication auth) {
        return guard.check(auth, tenantId).then(service.getActive(tenantId, tableName));
    }

    // ---------------- Entries ----------------

    @PostMapping("/tables/{name}/entries")
    @Operation(summary = "Ingest a single entry",
               description = "Body shape: `{ \"recordKey\": \"<id>\", \"data\": { … } }`. The data is validated against the table's active schema. Identical payloads are dedup'd via SHA-256 (returns 409 idempotency-skip).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Entry stored as new version"),
        @ApiResponse(responseCode = "409", description = "Idempotency skip — identical payload already on record"),
        @ApiResponse(responseCode = "422", description = "Data does not satisfy the schema"),
        @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_VALKEYRY_WRITER")
    })
    public Mono<ResponseEntity<EntryView>> ingest(@PathVariable String tenantId,
                                                  @PathVariable("name") String tableName,
                                                  @Valid @RequestBody IngestRecordRequest req,
                                                  Authentication auth,
                                                  ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        return guard.check(auth, tenantId)
                .then(service.ingest(tenantId, tableName, req.recordKey(), req.data(), auth.getName(), track, requestId))
                .map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view));
    }

    @PostMapping("/tables/{name}/entries:batch")
    @Operation(summary = "Ingest a list of entries in one call",
               description = "Body is an array of `{ recordKey, data }` objects. Each row is validated independently; duplicates count as `duplicates`, valid rows as `inserted`. Returns a summary plus the inserted views.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Batch processed — see body for breakdown"),
        @ApiResponse(responseCode = "422", description = "At least one row failed schema validation")
    })
    public Mono<ResponseEntity<BatchIngestResponse>> ingestBatch(@PathVariable String tenantId,
                                                                 @PathVariable("name") String tableName,
                                                                 @Valid @RequestBody BatchIngestRequest req,
                                                                 Authentication auth,
                                                                 ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        return guard.check(auth, tenantId)
                .thenMany(Flux.fromIterable(req.entries()))
                .concatMap(entry -> service.ingest(tenantId, tableName, entry.recordKey(), entry.data(),
                                auth.getName(), track, requestId)
                        .map(view -> new BatchResult(view, false))
                        .onErrorResume(IdempotentDuplicateException.class,
                                ex -> Mono.just(new BatchResult(null, true))))
                .collectList()
                .map(results -> {
                    int inserted = 0; int duplicates = 0;
                    List<EntryView> views = new ArrayList<>();
                    for (BatchResult r : results) {
                        if (r.duplicate) duplicates++;
                        else { inserted++; views.add(r.view); }
                    }
                    return ResponseEntity.status(HttpStatus.CREATED).body(
                            new BatchIngestResponse(req.entries().size(), inserted, duplicates, views));
                });
    }

    private record BatchResult(EntryView view, boolean duplicate) {}

    @GetMapping("/tables/{name}/entries/{recordKey}")
    @Operation(summary = "Fetch the latest version of an entry")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Entry found"),
        @ApiResponse(responseCode = "404", description = "No entry exists for that record key")
    })
    public Mono<EntryView> latest(@PathVariable String tenantId,
                                  @PathVariable("name") String tableName,
                                  @PathVariable String recordKey,
                                  Authentication auth) {
        return guard.check(auth, tenantId).then(service.getLatest(tenantId, tableName, recordKey));
    }

    @DeleteMapping("/tables/{name}/entries/{recordKey}")
    @Operation(summary = "Soft-delete an entry",
               description = "Marks the record deleted but keeps full history in the audit ledger — recoverable via rollback.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Entry soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Entry not found")
    })
    public Mono<ResponseEntity<Void>> deleteEntry(@PathVariable String tenantId,
                                                  @PathVariable("name") String tableName,
                                                  @PathVariable String recordKey,
                                                  Authentication auth,
                                                  ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        return guard.check(auth, tenantId)
                .then(service.softDelete(tenantId, tableName, recordKey, auth.getName(), track, requestId))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/tables/{name}/entries/{recordKey}/history")
    @Operation(summary = "Full version history of a single entry")
    public Flux<EntryView> history(@PathVariable String tenantId,
                                   @PathVariable("name") String tableName,
                                   @PathVariable String recordKey,
                                   Authentication auth) {
        return guard.check(auth, tenantId).thenMany(service.history(tenantId, tableName, recordKey));
    }

    @GetMapping("/tables/{name}/entries")
    @Operation(summary = "List entries in a table (paged, latest version per record)")
    public Flux<EntryView> browse(@PathVariable String tenantId,
                                  @PathVariable("name") String tableName,
                                  @Parameter(description = "Page size, 1-1000") @RequestParam(defaultValue = "50")  int limit,
                                  @Parameter(description = "Number of rows to skip")  @RequestParam(defaultValue = "0")   int offset,
                                  Authentication auth) {
        return guard.check(auth, tenantId)
                .thenMany(service.browse(tenantId, tableName, sane(limit, 1000), Math.max(0, offset)));
    }

    @PostMapping("/tables/{name}/search")
    @Operation(summary = "Filter entries by JSON-path / JSONB criteria",
               description = "Body is a free-form JSON object describing the predicate (e.g. `{ \"equals\": { \"role\": \"admin\" } }`).")
    public Flux<EntryView> search(@PathVariable String tenantId,
                                  @PathVariable("name") String tableName,
                                  @RequestBody(required = false) JsonNode criteria,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0")  int offset,
                                  Authentication auth) {
        JsonNode body = (criteria == null) ? mapper.createObjectNode() : criteria;
        return guard.check(auth, tenantId)
                .thenMany(service.search(tenantId, tableName, body, sane(limit, 1000), Math.max(0, offset)));
    }

    private static int sane(int v, int max) {
        if (v <= 0) return 50;
        return Math.min(v, max);
    }
}
