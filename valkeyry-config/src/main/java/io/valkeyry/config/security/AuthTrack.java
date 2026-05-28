package io.valkeyry.config.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Identifies which Track the {@link Authentication} came from for audit logging. */
public enum AuthTrack {
    OIDC, LDAP, API_KEY, ANONYMOUS;

    public static AuthTrack of(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ANONYMOUS;
        if (auth instanceof JwtAuthenticationToken) return OIDC;
        boolean apiKey = auth.getAuthorities().stream().anyMatch(a -> "ROLE_API_KEY".equals(a.getAuthority()));
        if (apiKey) return API_KEY;
        boolean ldap = auth.getAuthorities().stream().anyMatch(a -> "ROLE_LDAP_AGENT".equals(a.getAuthority()));
        if (ldap) return LDAP;
        return ANONYMOUS;
    }
}
