package io.valkeyry.config.api;

import io.valkeyry.config.error.IdempotentDuplicateException;
import io.valkeyry.config.error.SchemaValidationException;
import io.valkeyry.config.error.TenantAccessDeniedException;
import io.valkeyry.config.error.VirtualTableNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotentDuplicateException.class)
    public ResponseEntity<ProblemDetail> dup(IdempotentDuplicateException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:idempotency-guard"));
        p.setProperty("payloadHash", ex.payloadHash());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
    }

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<ProblemDetail> schema(SchemaValidationException ex) {
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
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        p.setType(URI.create("urn:valkeyry:error:bad-request"));
        p.setProperty("fields", fields);
        return ResponseEntity.badRequest().body(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> illegalArg(IllegalArgumentException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        return ResponseEntity.badRequest().body(p);
    }
}
