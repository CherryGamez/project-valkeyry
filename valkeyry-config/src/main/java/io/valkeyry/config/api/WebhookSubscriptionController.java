package io.valkeyry.config.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Audit webhooks", description = "Register HTTPS endpoints that receive every audit event with an HMAC-SHA256 signature.")
public class WebhookSubscriptionController {

    private final AuditWebhookSubscriptionService service;
    private final TenantAccessGuard guard;

    public WebhookSubscriptionController(AuditWebhookSubscriptionService service, TenantAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    @Operation(summary = "List webhook subscriptions for this tenant")
    public Flux<WebhookSubscriptionView> list(@PathVariable String tenantId, Authentication auth) {
        return guard.check(auth, tenantId).thenMany(service.list(tenantId));
    }

    @PostMapping
    @Operation(summary = "Register a new webhook target",
               description = "Body: `{ url, description?, secret? }`. The `secret` overrides the global HMAC key for this subscription. Duplicate URLs return 409.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "409", description = "Duplicate URL already registered for this tenant")
    })
    public Mono<ResponseEntity<WebhookSubscriptionView>> create(@PathVariable String tenantId,
                                                                @Valid @RequestBody CreateWebhookSubscriptionRequest req,
                                                                Authentication auth) {
        return guard.check(auth, tenantId)
                .then(service.create(tenantId, req, auth.getName()))
                .map(v -> ResponseEntity.status(HttpStatus.CREATED).body(v));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a webhook subscription")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Subscription deleted"),
        @ApiResponse(responseCode = "404", description = "Subscription id not found in this tenant")
    })
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
