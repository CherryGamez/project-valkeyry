package io.valkeyry.config.api.admin;

import java.util.List;

/**
 * Response of {@code POST /api/v1/auth/login}.
 *
 * @param token         Bearer JWT (HS256) to set as {@code Authorization: Bearer …}.
 * @param tokenType     Always {@code "Bearer"}.
 * @param expiresInSec  Seconds until expiry.
 * @param username      Resolved subject ({@code sub}) of the token.
 * @param role          {@code reader} | {@code writer} | {@code admin}.
 * @param tenants       Entitled tenant slugs (may include the {@code "*"} wildcard for admins).
 * @param source        {@code LOCAL} | {@code LDAP} | {@code SSO} — where the credential check happened.
 * @param mode          {@code admin} when the user has the {@code admin} role, otherwise {@code console}.
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresInSec,
        String username,
        String role,
        List<String> tenants,
        String source,
        String mode) {}
