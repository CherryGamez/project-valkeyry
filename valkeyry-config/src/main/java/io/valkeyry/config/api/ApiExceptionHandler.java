package io.valkeyry.config.api;

import io.valkeyry.config.error.IdempotentDuplicateException;
import io.valkeyry.config.error.SchemaValidationException;
import io.valkeyry.config.error.TenantAccessDeniedException;
import io.valkeyry.config.error.VirtualTableNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.codec.DecodingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Centralised translation of exceptions into RFC-7807 {@link ProblemDetail} responses.
 *
 * <p>Every handler logs the failure with full stack-trace context (class.method file:line)
 * routed through SLF4J — so a developer can {@code grep} the server log for any
 * {@code traceId} the response returns and land on the exact source line that threw.</p>
 *
 * <p>The {@code traceId} property on a 5xx response matches the {@code traceId} MDC key
 * embedded in the corresponding log line by the catch-all handler at the bottom of this
 * file.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IdempotentDuplicateException.class)
    public ResponseEntity<ProblemDetail> dup(IdempotentDuplicateException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:idempotency-guard"));
        p.setProperty("payloadHash", ex.payloadHash());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
    }

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<ProblemDetail> schema(SchemaValidationException ex) {
        // Logged at WARN — schema misses are a caller-side error, not a server fault, but
        // we still want them visible so a developer can correlate a 422 in the wild with
        // the exact set of violations the validator produced.
        log.warn("Schema validation rejected payload — violations={}", ex.violations());
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:schema-violation"));
        p.setProperty("violations", ex.violations());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(p);
    }

    @ExceptionHandler(VirtualTableNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(VirtualTableNotFoundException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:virtual-table-not-found"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(p);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> tenant(TenantAccessDeniedException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:tenant-access-denied"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(p);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> validation(WebExchangeBindException ex) {
        Map<String, List<String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(f -> f.getField(),
                        Collectors.mapping(f -> String.valueOf(f.getDefaultMessage()), Collectors.toList())));
        log.warn("Request body failed bean-validation — fields={}", fields);
        // Build a one-line per-field summary so the response `detail` (which is all that
        // most CLI tools print) already names the offending field — e.g.
        //   "Request validation failed: entries[3].recordKey: must not be blank; entries[7].data: must not be null"
        String summary = fields.entrySet().stream()
                .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("; "));
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                summary.isBlank() ? "Request validation failed" : "Request validation failed — " + summary);
        p.setType(URI.create("urn:valkeyry:error:bad-request"));
        p.setProperty("fields", fields);
        return ResponseEntity.badRequest().body(p);
    }

    /**
     * Surfaces the underlying JSON-parse failure verbatim (line / column number for a
     * malformed JSON body, type-mismatch message for a wrong field type, …). The default
     * Spring response is just "Failed to read HTTP message" which is useless to the caller.
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> badInput(ServerWebInputException ex) {
        String reason = ex.getReason() == null ? "Malformed request" : ex.getReason();
        Throwable cause = ex.getMostSpecificCause();
        String detail = reason + (cause != null && cause.getMessage() != null
                ? " — " + cause.getMessage() : "");
        log.warn("Malformed request body: {}", detail);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        p.setType(URI.create("urn:valkeyry:error:malformed-request"));
        if (cause instanceof DecodingException de && de.getMessage() != null) {
            p.setProperty("parseError", de.getMessage());
        }
        return ResponseEntity.badRequest().body(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> illegalArg(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        return ResponseEntity.badRequest().body(p);
    }

    /**
     * Maps any Spring-translated UNIQUE-constraint violation to {@code 409 Conflict} with a
     * stable Problem+JSON type — typically fired when a tenant tries to register the same
     * webhook URL twice, or when concurrent ingests of the same record key race past the
     * idempotency guard at the DB layer.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> duplicateKey(DuplicateKeyException ex) {
        log.warn("Duplicate key violation: {}", rootCauseMessage(ex));
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Resource already exists: " + rootCauseMessage(ex));
        p.setType(URI.create("urn:valkeyry:error:duplicate-resource"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException ex) {
        String detail = ex.getReason() == null ? ex.getMessage() : ex.getReason();
        if (ex.getStatusCode().is5xxServerError()) {
            String traceId = traceId();
            log.error("Server error from controller traceId={}: {}", traceId, detail, ex);
            ProblemDetail p = ProblemDetail.forStatusAndDetail(
                    HttpStatus.valueOf(ex.getStatusCode().value()),
                    detail);
            p.setType(URI.create("urn:valkeyry:error:server"));
            p.setProperty("traceId", traceId);
            return ResponseEntity.status(ex.getStatusCode()).body(p);
        }
        log.warn("Request rejected ({}): {}", ex.getStatusCode().value(), detail);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(ex.getStatusCode().value()), detail);
        return ResponseEntity.status(ex.getStatusCode()).body(p);
    }

    /**
     * Last-line-of-defence handler. Logs the full stack trace with a generated {@code traceId}
     * and returns the same id in the {@link ProblemDetail} body so support / dev can grep
     * for the exact failure in the log file. Without this handler, a {@code NullPointerException}
     * from deep inside a reactive pipeline lands in the response body as a one-line "500" with
     * no context whatsoever — that's the experience we're eliminating.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> unexpected(Throwable ex) {
        String traceId = traceId();
        MDC.put("traceId", traceId);
        try {
            log.error("Unhandled exception traceId={} — {}", traceId, ex.getClass().getName(), ex);
        } finally {
            MDC.remove("traceId");
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getClass().getSimpleName() + ": " +
                        (ex.getMessage() == null ? "<no message>" : ex.getMessage()));
        p.setType(URI.create("urn:valkeyry:error:server"));
        p.setProperty("traceId", traceId);
        p.setProperty("hint", "Search the server log for traceId=" + traceId
                + " to find the exact stack-trace line.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(p);
    }

    private static String traceId() {
        String existing = MDC.get("traceId");
        return existing != null ? existing : UUID.randomUUID().toString();
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? t.getMessage() : cur.getMessage();
    }
}
