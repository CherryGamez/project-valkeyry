package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.config.error.IdempotentDuplicateException;
import io.valkeyry.config.error.SchemaValidationException;
import io.valkeyry.config.error.VirtualTableNotFoundException;
import io.valkeyry.config.security.AuthTrack;
import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.VirtualTableService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    private static final Logger log = LoggerFactory.getLogger(VirtualTableController.class);

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

    @DeleteMapping("/tables/{name}")
    @Operation(summary = "Soft-delete an entire virtual table",
               description = "Deactivates every version of the named table. Entries are not physically removed — history stays queryable via the audit ledger and the action is reversible from the audit timeline. Requires `ROLE_VALKEYRY_WRITER` (or admin).")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Table soft-deleted"),
        @ApiResponse(responseCode = "404", description = "No active table by that name"),
        @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_VALKEYRY_WRITER")
    })
    public Mono<ResponseEntity<Void>> deleteTable(@PathVariable String tenantId,
                                                  @PathVariable("name") String tableName,
                                                  Authentication auth,
                                                  ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        return guard.check(auth, tenantId)
                .then(service.softDeleteTable(tenantId, tableName, auth.getName(), track, requestId))
                .thenReturn(ResponseEntity.noContent().<Void>build());
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
               description = """
                       Body is an array of `{ recordKey, data }` objects. Each row is validated
                       and persisted **independently** — one bad row does not abort the batch.

                       The response is a structured breakdown:
                       * `submitted` — number of rows the caller sent.
                       * `inserted` / `duplicates` / `failed` — partitioned outcome counts.
                       * `results[]` — inserted rows (one `EntryView` each).
                       * `errors[]` — one entry per failed row, carrying `index` (0-based
                         position in the request), `recordKey`, `errorType`, `httpStatus`,
                         `message` and (for schema failures) the list of `violations`. Use
                         `index` to map the failure back to the source spreadsheet/JSON file.

                       Status code: **201** when every row was accepted (inserted + duplicates),
                       **207 Multi-Status** when some rows failed but at least one succeeded,
                       **422 Unprocessable Entity** when every row failed.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Batch fully processed — see body for breakdown"),
        @ApiResponse(responseCode = "207", description = "Partial success — at least one row failed"),
        @ApiResponse(responseCode = "422", description = "Every row failed validation")
    })
    public Mono<ResponseEntity<BatchIngestResponse>> ingestBatch(@PathVariable String tenantId,
                                                                 @PathVariable("name") String tableName,
                                                                 @Valid @RequestBody BatchIngestRequest req,
                                                                 Authentication auth,
                                                                 ServerWebExchange exchange) {
        String track = AuthTrack.of(auth).name();
        String requestId = exchange.getRequest().getId();
        int total = req.entries().size();
        log.info("Bulk ingest start: tenant={} table={} entries={} requestId={} actor={}",
                tenantId, tableName, total, requestId, auth.getName());

        return guard.check(auth, tenantId)
                .thenMany(Flux.fromIterable(req.entries())
                        .index() // -> Tuple2<Long, IngestRecordRequest>
                        .concatMap(tuple -> {
                            int idx = tuple.getT1().intValue();
                            IngestRecordRequest entry = tuple.getT2();
                            return ingestOne(tenantId, tableName, idx, entry, auth, track, requestId);
                        }))
                .collectList()
                .map(results -> buildResponse(tenantId, tableName, total, results, requestId));
    }

    /**
     * Ingest one row and turn ANY outcome into a {@link BatchRowOutcome} — never propagates
     * the error upstream. This is what lets a single malformed row coexist with hundreds of
     * good rows in the same batch without aborting the whole upload.
     *
     * <p>Each failure path is logged at {@code WARN} (or {@code ERROR} for unexpected
     * exceptions) with the full context — {@code tenantId / tableName / index / recordKey}
     * pinned into the MDC so a developer can grep for the exact line.</p>
     */
    private Mono<BatchRowOutcome> ingestOne(String tenantId, String tableName, int idx,
                                            IngestRecordRequest entry, Authentication auth,
                                            String track, String requestId) {
        // Defensive null / blank guard — bean validation runs at the request level, but
        // individual entries inside the list can still slip through with blank values when
        // the caller streams from a CSV/XLSX.
        if (entry == null) {
            log.warn("Bulk ingest row[{}] rejected: entry is null (tenant={} table={} requestId={})",
                    idx, tenantId, tableName, requestId);
            return Mono.just(BatchRowOutcome.ofFailed(BatchEntryError.invalidData(idx, null,
                    "entry is null")));
        }
        String recordKey = entry.recordKey();
        if (recordKey == null || recordKey.isBlank()) {
            log.warn("Bulk ingest row[{}] rejected: blank recordKey (tenant={} table={} requestId={})",
                    idx, tenantId, tableName, requestId);
            return Mono.just(BatchRowOutcome.ofFailed(BatchEntryError.invalidRecordKey(idx, recordKey,
                    "recordKey is blank — every row needs a non-empty key")));
        }
        if (entry.data() == null || entry.data().isNull()) {
            log.warn("Bulk ingest row[{}] rejected: null data (tenant={} table={} recordKey={} requestId={})",
                    idx, tenantId, tableName, recordKey, requestId);
            return Mono.just(BatchRowOutcome.ofFailed(BatchEntryError.invalidData(idx, recordKey,
                    "data field is null — supply the row payload as a JSON object")));
        }

        return service.ingest(tenantId, tableName, recordKey, entry.data(),
                        auth.getName(), track, requestId)
                .map(BatchRowOutcome::ofInserted)
                .onErrorResume(IdempotentDuplicateException.class, ex -> {
                    log.debug("Bulk ingest row[{}] is duplicate (tenant={} table={} recordKey={} hash={})",
                            idx, tenantId, tableName, recordKey, ex.payloadHash());
                    return Mono.just(BatchRowOutcome.ofDuplicate());
                })
                .onErrorResume(SchemaValidationException.class, ex -> {
                    log.warn("Bulk ingest row[{}] schema-violation (tenant={} table={} recordKey={}): {}",
                            idx, tenantId, tableName, recordKey, ex.violations());
                    return Mono.just(BatchRowOutcome.ofFailed(BatchEntryError.schemaViolation(
                            idx, recordKey,
                            "Schema validation failed for row " + idx
                                    + " (recordKey=" + recordKey + ")",
                            ex.violations())));
                })
                .onErrorResume(VirtualTableNotFoundException.class, ex -> {
                    // Fail the entire batch — every row would hit the same error. Bubble up
                    // so the global handler returns a single 404 instead of N copies.
                    log.warn("Bulk ingest aborted at row[{}]: table not found (tenant={} table={})",
                            idx, tenantId, tableName);
                    return Mono.error(ex);
                })
                .onErrorResume(IllegalArgumentException.class, ex -> {
                    log.warn("Bulk ingest row[{}] invalid-argument (tenant={} table={} recordKey={}): {}",
                            idx, tenantId, tableName, recordKey, ex.getMessage());
                    return Mono.just(BatchRowOutcome.ofFailed(BatchEntryError.invalidData(
                            idx, recordKey, ex.getMessage())));
                })
                .onErrorResume(ResponseStatusException.class, ex -> {
                    log.warn("Bulk ingest row[{}] rejected by downstream (tenant={} table={} recordKey={} status={}): {}",
                            idx, tenantId, tableName, recordKey, ex.getStatusCode().value(), ex.getReason(), ex);
                    int status = ex.getStatusCode().value();
                    String type = status == 404 ? "table-not-found"
                            : status == 422 ? "schema-violation"
                            : status >= 500 ? "internal"
                            : "invalid-data";
                    return Mono.just(BatchRowOutcome.ofFailed(new BatchEntryError(
                            idx, recordKey, "failed", type, status,
                            ex.getReason() == null ? ex.getMessage() : ex.getReason(),
                            List.of(), null)));
                })
                .onErrorResume(Throwable.class, ex -> {
                    String traceId = UUID.randomUUID().toString();
                    // Pin the trace id into the MDC so the same id appears in the stack-trace
                    // line below — that's the anchor the caller will copy into a `grep`.
                    MDC.put("traceId", traceId);
                    try {
                        log.error("Bulk ingest row[{}] unexpected failure traceId={} (tenant={} table={} recordKey={} requestId={})",
                                idx, traceId, tenantId, tableName, recordKey, requestId, ex);
                    } finally {
                        MDC.remove("traceId");
                    }
                    return Mono.just(BatchRowOutcome.ofFailed(BatchEntryError.internal(
                            idx, recordKey,
                            ex.getClass().getSimpleName() + ": "
                                    + (ex.getMessage() == null ? "<no detail>" : ex.getMessage())
                                    + " — search server log for traceId=" + traceId,
                            traceId)));
                });
    }

    /** Internal three-way outcome from {@link #ingestOne}. */
    private record BatchRowOutcome(EntryView view, boolean duplicate, BatchEntryError error) {
        static BatchRowOutcome ofInserted(EntryView view)         { return new BatchRowOutcome(view, false, null); }
        static BatchRowOutcome ofDuplicate()                      { return new BatchRowOutcome(null, true,  null); }
        static BatchRowOutcome ofFailed(BatchEntryError e)        { return new BatchRowOutcome(null, false, e);    }
    }

    private ResponseEntity<BatchIngestResponse> buildResponse(String tenantId, String tableName,
                                                              int submitted, List<BatchRowOutcome> rows,
                                                              String requestId) {
        int inserted = 0, duplicates = 0, failed = 0;
        List<EntryView> views = new ArrayList<>();
        List<BatchEntryError> errors = new ArrayList<>();
        for (BatchRowOutcome r : rows) {
            if (r.error != null)       { failed++;     errors.add(r.error); }
            else if (r.duplicate)      { duplicates++; }
            else                       { inserted++;   views.add(r.view); }
        }
        HttpStatus status;
        if (failed == 0)              status = HttpStatus.CREATED;
        else if (inserted + duplicates == 0) status = HttpStatus.UNPROCESSABLE_ENTITY;
        else                          status = HttpStatus.MULTI_STATUS;

        if (failed == 0) {
            log.info("Bulk ingest done OK: tenant={} table={} submitted={} inserted={} duplicates={} requestId={}",
                    tenantId, tableName, submitted, inserted, duplicates, requestId);
        } else {
            log.warn("Bulk ingest done with failures: tenant={} table={} submitted={} inserted={} duplicates={} failed={} requestId={}",
                    tenantId, tableName, submitted, inserted, duplicates, failed, requestId);
        }
        return ResponseEntity.status(status).body(
                new BatchIngestResponse(submitted, inserted, duplicates, failed, views, errors));
    }

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
    @Operation(summary = "List entries in a table (paged, latest version per record)",
               description = "Use the `fields=` query param to project only the columns you need — e.g. `?fields=recordKey,data.role`. Default is every field.")
    public Flux<JsonNode> browse(@PathVariable String tenantId,
                                 @PathVariable("name") String tableName,
                                 @Parameter(description = "Page size, 1-1000") @RequestParam(defaultValue = "50")  int limit,
                                 @Parameter(description = "Number of rows to skip")  @RequestParam(defaultValue = "0")   int offset,
                                 @Parameter(description = "CSV field whitelist — use dotted `data.<key>` for nested keys") @RequestParam(required = false) String fields,
                                 Authentication auth) {
        return guard.check(auth, tenantId)
                .thenMany(service.browse(tenantId, tableName, sane(limit, 1000), Math.max(0, offset)))
                .map(v -> FieldProjection.apply(mapper.valueToTree(v), fields, mapper));
    }

    @PostMapping("/tables/{name}/search")
    @Operation(summary = "Filter entries by JSON-path / JSONB criteria",
               description = "Body is a free-form JSON object describing the predicate (e.g. `{ \"equals\": { \"role\": \"admin\" } }`). Pair with `?fields=` to project only specific columns.")
    public Flux<JsonNode> search(@PathVariable String tenantId,
                                 @PathVariable("name") String tableName,
                                 @RequestBody(required = false) JsonNode criteria,
                                 @RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(defaultValue = "0")  int offset,
                                 @RequestParam(required = false) String fields,
                                 Authentication auth) {
        JsonNode body = (criteria == null) ? mapper.createObjectNode() : criteria;
        return guard.check(auth, tenantId)
                .thenMany(service.search(tenantId, tableName, body, sane(limit, 1000), Math.max(0, offset)))
                .map(v -> FieldProjection.apply(mapper.valueToTree(v), fields, mapper));
    }

    private static int sane(int v, int max) {
        if (v <= 0) return 50;
        return Math.min(v, max);
    }
}
