package io.valkeyry.ipaas.api;

import io.valkeyry.ipaas.domain.Message;
import io.valkeyry.ipaas.routing.TenantRouter;
import io.valkeyry.ipaas.security.TenantAccessGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/messages")
public class PublishController {

    private final TenantRouter router;
    private final TenantAccessGuard guard;

    public PublishController(TenantRouter router, TenantAccessGuard guard) {
        this.router = router;
        this.guard = guard;
    }

    @PostMapping
    public Mono<ResponseEntity<PublishResponse>> publish(@PathVariable String tenantId,
                                                         @Valid @RequestBody PublishRequest req,
                                                         Authentication auth) {
        Message msg = new Message(
                UUID.randomUUID(),
                tenantId,
                req.destination(),
                req.mode(),
                req.payload(),
                null,
                req.headers(),
                Instant.now());
        return guard.check(auth, tenantId)
                .then(router.route(msg, req.broker()))
                .map(PublishResponse::from)
                .map(r -> ResponseEntity.status(HttpStatus.ACCEPTED).body(r));
    }
}
