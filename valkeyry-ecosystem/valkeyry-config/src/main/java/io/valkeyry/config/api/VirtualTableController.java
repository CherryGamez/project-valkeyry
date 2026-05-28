package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public Flux<VirtualTableView> listTables(@PathVariable String tenantId, Authentication auth) {
        return guard.check(auth, tenantId).thenMany(service.listTables(tenantId));
    }

    @PostMapping("/tables")
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
    public Mono<VirtualTableView> getTable(@PathVariable String tenantId,
                                           @PathVariable("name") String tableName,
                                           Authentication auth) {
        return guard.check(auth, tenantId).then(service.getActive(tenantId, tableName));
    }

    // ---------------- Entries ----------------

    @PostMapping("/tables/{name}/entries")
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
    public Mono<EntryView> latest(@PathVariable String tenantId,
                                  @PathVariable("name") String tableName,
                                  @PathVariable String recordKey,
                                  Authentication auth) {
        return guard.check(auth, tenantId).then(service.getLatest(tenantId, tableName, recordKey));
    }

    @GetMapping("/tables/{name}/entries/{recordKey}/history")
    public Flux<EntryView> history(@PathVariable String tenantId,
                                   @PathVariable("name") String tableName,
                                   @PathVariable String recordKey,
                                   Authentication auth) {
        return guard.check(auth, tenantId).thenMany(service.history(tenantId, tableName, recordKey));
    }

    @GetMapping("/tables/{name}/entries")
    public Flux<EntryView> browse(@PathVariable String tenantId,
                                  @PathVariable("name") String tableName,
                                  @RequestParam(defaultValue = "50")  int limit,
                                  @RequestParam(defaultValue = "0")   int offset,
                                  Authentication auth) {
        return guard.check(auth, tenantId)
                .thenMany(service.browse(tenantId, tableName, sane(limit, 1000), Math.max(0, offset)));
    }

    @PostMapping("/tables/{name}/search")
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
