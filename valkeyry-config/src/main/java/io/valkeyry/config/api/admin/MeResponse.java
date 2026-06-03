package io.valkeyry.config.api.admin;

import java.util.List;

/** {@code GET /api/v1/auth/me} — current authentication snapshot. */
public record MeResponse(
        String username,
        String role,
        List<String> tenants,
        String source,
        boolean isWriter,
        boolean isAdmin) {}
