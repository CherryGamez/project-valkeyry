package io.valkeyry.ipaas.api;

import io.valkeyry.ipaas.error.TenantAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> tenant(TenantAccessDeniedException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:tenant-access-denied"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(p);
    }

    /**
     * Thrown by {@link io.valkeyry.ipaas.broker.BrokerClientFactory#get(String)} when the caller
     * specifies a broker type that no {@link io.valkeyry.ipaas.broker.ReactiveBrokerClient} bean
     * advertises in its {@code type()}. We map this single boundary into a 400 so the consumer
     * sees a clean Problem+JSON instead of a 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        p.setType(URI.create("urn:valkeyry:error:bad-request"));
        return ResponseEntity.badRequest().body(p);
    }
}
