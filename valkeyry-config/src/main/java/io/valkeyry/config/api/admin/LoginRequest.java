package io.valkeyry.config.api.admin;

import jakarta.validation.constraints.NotBlank;

/** Form-login payload for {@code POST /api/v1/auth/login}. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
