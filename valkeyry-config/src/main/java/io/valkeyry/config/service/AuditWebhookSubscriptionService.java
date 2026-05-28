package io.valkeyry.config.service;

import io.valkeyry.config.api.CreateWebhookSubscriptionRequest;
import io.valkeyry.config.api.WebhookSubscriptionView;
import io.valkeyry.config.domain.AuditWebhookSubscription;
import io.valkeyry.config.repo.AuditWebhookSubscriptionRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * CRUD over the dynamic {@link AuditWebhookSubscription} table.
 *
 * <p>Pure persistence layer — the publisher reads the same data via the repository when fanning
 * out an audit event.</p>
 */
@Service
public class AuditWebhookSubscriptionService {

    private final AuditWebhookSubscriptionRepository repo;

    public AuditWebhookSubscriptionService(AuditWebhookSubscriptionRepository repo) {
        this.repo = repo;
    }

    public Flux<WebhookSubscriptionView> list(String tenantId) {
        return repo.findByTenant(tenantId).map(this::toView);
    }

    public Mono<WebhookSubscriptionView> create(String tenantId, CreateWebhookSubscriptionRequest req, String createdBy) {
        AuditWebhookSubscription row = new AuditWebhookSubscription();
        row.setId(UUID.randomUUID());
        row.setTenantId(tenantId);
        row.setUrl(req.url());
        row.setDescription(req.description());
        row.setSecret(req.secret() == null || req.secret().isBlank() ? null : req.secret());
        row.setEnabled(req.enabled() == null ? true : req.enabled());
        row.setCreatedAt(Instant.now());
        row.setCreatedBy(createdBy == null ? "<unknown>" : createdBy);
        return repo.save(row).map(this::toView);
    }

    public Mono<Long> delete(String tenantId, UUID id) {
        return repo.deleteByTenantAndId(tenantId, id);
    }

    public Mono<WebhookSubscriptionView> get(String tenantId, UUID id) {
        return repo.findByTenantAndId(tenantId, id).map(this::toView);
    }

    private WebhookSubscriptionView toView(AuditWebhookSubscription r) {
        return new WebhookSubscriptionView(
                r.getId(), r.getTenantId(), r.getUrl(), r.getDescription(),
                r.isEnabled(), r.getSecret() != null && !r.getSecret().isBlank(),
                r.getCreatedAt(), r.getCreatedBy());
    }
}
