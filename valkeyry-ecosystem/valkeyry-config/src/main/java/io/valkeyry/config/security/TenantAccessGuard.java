package io.valkeyry.config.security;

import io.valkeyry.config.error.TenantAccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Enforces that the authenticated principal is entitled to the {@code {tenantId}} in the URL.
 *
 * <p>For OIDC tokens the entitlement set comes from the {@code valkeyry.tenants} claim
 * (string array). For LDAP / API-key principals the entitlement comes from {@code SCOPE_tenant:&lt;id&gt;}
 * authorities granted at bind time.</p>
 *
 * <p>The wildcard {@code *} grants access to every tenant — reserve it for admin service accounts.</p>
 */
@Component
public class TenantAccessGuard {

    public Mono<Void> check(Authentication auth, String tenantId) {
        if (auth == null || !auth.isAuthenticated()) {
            return Mono.error(new TenantAccessDeniedException(tenantId, "<anonymous>"));
        }
        Collection<String> entitled = extractEntitlements(auth);
        if (entitled.contains("*") || entitled.contains(tenantId)) {
            return Mono.empty();
        }
        return Mono.error(new TenantAccessDeniedException(tenantId, Objects.toString(auth.getName(), "<unknown>")));
    }

    private Collection<String> extractEntitlements(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            Object claim = jwt.getClaim("valkeyry.tenants");
            if (claim instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
            if (claim instanceof String s) {
                return List.of(s.split("[, ]+"));
            }
        }
        // Authority-based fallback (LDAP / API-key)
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_tenant:"))
                .map(a -> a.substring("SCOPE_tenant:".length()))
                .toList();
    }
}
