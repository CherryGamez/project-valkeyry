package io.valkeyry.config.security.apikey;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Lifts {@code X-API-Key: …} into an unauthenticated {@link UsernamePasswordAuthenticationToken}. */
public class ApiKeyAuthenticationConverter implements ServerAuthenticationConverter {

    public static final String HEADER = "X-API-Key";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String key = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (key == null || key.isBlank()) return Mono.empty();
        return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated("api-key", key));
    }
}
