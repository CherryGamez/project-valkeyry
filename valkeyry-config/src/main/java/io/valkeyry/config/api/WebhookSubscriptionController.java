package io.valkeyry.config.api;

import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.AuditWebhookSubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Manage SIEM / audit webhook fan-out targets per tenant.
 *
 * <p>Static URLs declared via {@code valkeyry.audit.webhook.urls} remain in effect; this REST
 * surface only manages the <strong>dynamic</strong> subscriptions persisted in the
 * {@code audit_webhook_subscription} table. The {@link io.valkeyry.config.service.AuditWebhookPublisher}
 * unions both sources on every fan-out.</p>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/webhooks")
public class WebhookSubscriptionController {

    private final AuditWebhookSubscriptionService service;
    private final TenantAccessGuard guard;

    public WebhookSubscriptionController(AuditWebhookSubscriptionService service, TenantAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public Flux<WebhookSubscriptionView> list(@PathVariable String tenantId, Authentication auth) {
        return guard.check(auth, tenantId).thenMany(service.list(tenantId));
    }

    @PostMapping
    public Mono<ResponseEntity<WebhookSubscriptionView>> create(@PathVariable String tenantId,
                                                                @Valid @RequestBody CreateWebhookSubscriptionRequest req,
                                                                Authentication auth) {
        return guard.check(auth, tenantId)
                .then(service.create(tenantId, req, auth.getName()))
                .map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> remove(@PathVariable String tenantId,
                                             @PathVariable UUID id,
                                             Authentication auth) {
        return guard.check(auth, tenantId)
                .then(service.delete(tenantId, id))
                .map(deleted -> deleted != null && deleted > 0
                        ? ResponseEntity.noContent().<Void>build()
                        : ResponseEntity.notFound().<Void>build());
    }
}
