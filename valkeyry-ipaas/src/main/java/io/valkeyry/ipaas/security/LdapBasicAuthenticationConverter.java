package io.valkeyry.ipaas.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class LdapBasicAuthenticationConverter implements ServerAuthenticationConverter {
    private static final String PREFIX = "Basic ";
    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(PREFIX)) return Mono.empty();
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(PREFIX.length()).trim()), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) return Mono.empty();
            return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(decoded.substring(0, sep), decoded.substring(sep + 1)));
        } catch (IllegalArgumentException ex) { return Mono.empty(); }
    }
}
