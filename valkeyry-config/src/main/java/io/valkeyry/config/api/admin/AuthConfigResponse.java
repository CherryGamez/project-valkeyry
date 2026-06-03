package io.valkeyry.config.api.admin;

/**
 * Public, unauthenticated {@code GET /api/v1/auth/config} response.
 * Tells the login page which auth tracks are wired so the UI can show/hide buttons.
 *
 * @param ssoEnabled  When true, the "Login with WebSSO" button is shown.
 * @param ldapEnabled When true, the LDAP login form is shown.
 * @param ssoAuthUrl  Optional OIDC authorization-endpoint URL ({@code null} when SSO is off).
 */
public record AuthConfigResponse(boolean ssoEnabled, boolean ldapEnabled, String ssoAuthUrl) {}
