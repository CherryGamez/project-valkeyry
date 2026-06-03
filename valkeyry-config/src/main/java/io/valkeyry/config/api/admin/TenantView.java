package io.valkeyry.config.api.admin;

import java.time.Instant;

/** Read view of an {@link io.valkeyry.config.domain.admin.AdminTenant}. */
public record TenantView(
        String id,
        String name,
        String description,
        boolean enabled,
        Instant createdAt,
        String createdBy) {}
