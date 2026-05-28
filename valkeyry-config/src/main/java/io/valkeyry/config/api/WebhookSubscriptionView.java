package io.valkeyry.config.api;

import java.time.Instant;
import java.util.UUID;

/** Outbound projection of an {@link io.valkeyry.config.domain.AuditWebhookSubscription}. */
public record WebhookSubscriptionView(
        UUID id,
        String tenantId,
        String url,
        String description,
        boolean enabled,
        boolean hasSecret,
        Instant createdAt,
        String createdBy
) {}
