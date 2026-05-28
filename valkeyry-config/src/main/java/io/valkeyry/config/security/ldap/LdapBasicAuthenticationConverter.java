package io.valkeyry.config.security.ldap;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Extracts an HTTP Basic credentials pair and prepares an unauthenticated
 * {@link UsernamePasswordAuthenticationToken} for the LDAP manager to validate.
 */
public class LdapBasicAuthenticationConverter implements ServerAuthenticationConverter {

    private static final String PREFIX = "Basic ";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(PREFIX)) return Mono.empty();
        try {
            String b64 = header.substring(PREFIX.length()).trim();
            String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) return Mono.empty();
            String user = decoded.substring(0, sep);
            String pass = decoded.substring(sep + 1);
            return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(user, pass));
        } catch (IllegalArgumentException ex) {
            return Mono.empty();
        }
    }
}
