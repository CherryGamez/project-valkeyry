package io.valkeyry.config.api.admin;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body for {@code POST /api/v1/admin/users}.
 *
 * <p>{@code password} is only required when {@code source = "LOCAL"}; it's ignored for
 * SSO/LDAP-tracked rows (those exist purely to attach tenant entitlements).</p>
 */
public record UserUpsertRequest(
        @NotBlank String username,
        String displayName,
        String email,
        /** {@code reader} | {@code writer} | {@code admin}. */
        String role,
        /** {@code LOCAL} | {@code LDAP} | {@code SSO}. */
        String source,
        String password,
        Boolean enabled,
        List<String> tenants) {}
