package io.valkeyry.config.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of an {@link io.valkeyry.config.domain.admin.AppUser}. */
public record UserView(
        UUID id,
        String username,
        String displayName,
        String email,
        String role,
        String source,
        boolean enabled,
        List<String> tenants,
        Instant createdAt,
        String createdBy) {}
