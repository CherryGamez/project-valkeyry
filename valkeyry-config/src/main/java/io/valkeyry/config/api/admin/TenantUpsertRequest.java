package io.valkeyry.config.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body for {@code POST /api/v1/admin/tenants} and {@code PUT /api/v1/admin/tenants/{id}}. */
public record TenantUpsertRequest(
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9_\\-]{1,128}", message = "Tenant id may only contain letters, digits, '-' and '_' (max 128 chars)")
        String id,
        @NotBlank String name,
        String description,
        Boolean enabled) {}
