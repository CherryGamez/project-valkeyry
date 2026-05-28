package io.valkeyry.ipaas.security;

import io.valkeyry.ipaas.error.TenantAccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Identical guard contract as in valkeyry-config — both flows surface tenants as {@code SCOPE_tenant:…}. */
@Component
public class TenantAccessGuard {

    public Mono<Void> check(Authentication auth, String tenantId) {
        if (auth == null || !auth.isAuthenticated()) {
            return Mono.error(new TenantAccessDeniedException(tenantId, "<anonymous>"));
        }
        Collection<String> entitled = entitlements(auth);
        if (entitled.contains("*") || entitled.contains(tenantId)) return Mono.empty();
        return Mono.error(new TenantAccessDeniedException(tenantId, Objects.toString(auth.getName(), "<unknown>")));
    }

    private static Collection<String> entitlements(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_tenant:"))
                .map(a -> a.substring("SCOPE_tenant:".length()))
                .toList();
    }
}
