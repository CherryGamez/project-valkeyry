package io.valkeyry.ipaas.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JwtTenantAuthoritiesConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {
    private final JwtGrantedAuthoritiesConverter delegate = new JwtGrantedAuthoritiesConverter();
    public JwtTenantAuthoritiesConverter() {
        delegate.setAuthoritiesClaimName("scope");
        delegate.setAuthorityPrefix("SCOPE_");
    }
    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>(delegate.convert(jwt));
        for (String t : tenants(jwt)) authorities.add(new SimpleGrantedAuthority("SCOPE_tenant:" + t));
        return Mono.just(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
    }
    private static Set<String> tenants(Jwt jwt) {
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
}
