package io.valkeyry.config.security.oidc;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Reactive Jwt → AbstractAuthenticationToken converter that emits authorities of the shape
 * {@code SCOPE_tenant:&lt;id&gt;} for every tenant listed in the {@code valkeyry.tenants} claim.
 *
 * <p>This makes the tenant entitlement representation identical to the LDAP / API-key
 * pathways, so {@link io.valkeyry.config.security.TenantAccessGuard} stays single-source.</p>
 */
public class JwtTenantAuthoritiesConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final JwtGrantedAuthoritiesConverter delegate = new JwtGrantedAuthoritiesConverter();
    public JwtTenantAuthoritiesConverter() {
        delegate.setAuthoritiesClaimName("scope");
        delegate.setAuthorityPrefix("SCOPE_");
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>(delegate.convert(jwt));
        Set<String> tenants = readTenants(jwt);
        for (String t : tenants) authorities.add(new SimpleGrantedAuthority("SCOPE_tenant:" + t));
        // Map `valkeyry.role: "writer"` or `roles: ["writer", ...]` into ROLE_VALKEYRY_WRITER.
        if (hasWriterRole(jwt)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_VALKEYRY_WRITER"));
        }
        return Mono.just(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
    }

    private static Set<String> readTenants(Jwt jwt) {
        Object claim = jwt.getClaim("valkeyry.tenants");
        if (claim instanceof List<?> list) {
            Set<String> out = new HashSet<>();
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        if (claim instanceof String s && !s.isBlank()) {
            Set<String> out = new HashSet<>();
            for (String token : s.split("[, ]+")) if (!token.isBlank()) out.add(token);
            return out;
        }
        return Set.of();
    }

    private static boolean hasWriterRole(Jwt jwt) {
        Object role = jwt.getClaim("valkeyry.role");
        if (role instanceof String s && ("writer".equalsIgnoreCase(s) || "admin".equalsIgnoreCase(s))) return true;
        Object roles = jwt.getClaim("roles");
        if (roles instanceof List<?> list) {
            for (Object o : list) {
                String r = String.valueOf(o);
                if ("writer".equalsIgnoreCase(r) || "admin".equalsIgnoreCase(r)
                        || "valkeyry_writer".equalsIgnoreCase(r)) return true;
            }
        }
        return false;
    }
}
