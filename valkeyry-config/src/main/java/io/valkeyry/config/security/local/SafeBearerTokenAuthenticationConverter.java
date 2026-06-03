package io.valkeyry.config.security.local;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Wraps Spring's stock {@link ServerBearerTokenAuthenticationConverter} but discards bearer
 * values that are obviously <strong>not</strong> JWT-shaped (i.e. don't carry the mandatory two
 * '.' separators of a {@code header.payload.signature} compact serialization).
 *
 * <p>This fixes the production-confusing error:
 * <pre>{@code
 *   InvalidBearerTokenException: An error occurred while attempting to decode the Jwt:
 *   Invalid JWT serialization: Missing dot delimiter(s)
 * }</pre>
 * which surfaces when a non-JWT token (e.g. a misrouted API key, or a stale plain-string
 * bearer from an old client) hits {@code JwtReactiveAuthenticationManager}. By short-circuiting
 * the convert step we return {@link Mono#empty()} so the request falls through to the
 * subsequent {@link io.valkeyry.config.security.apikey.ApiKeyAuthenticationConverter} and
 * {@link io.valkeyry.config.security.ldap.LdapBasicAuthenticationConverter} filters — which
 * either succeed or yield a clean 401.</p>
 */
public class SafeBearerTokenAuthenticationConverter implements ServerAuthenticationConverter {

    private final ServerBearerTokenAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        // Cheap pre-check before delegating, to avoid the delegate even constructing an exception.
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null) return Mono.empty();
        String trimmed = header.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return Mono.empty();
        String token = trimmed.substring(7).trim();
        if (!looksLikeJwt(token)) return Mono.empty();
        return delegate.convert(exchange);
    }

    /** A compact JWS has exactly two '.' separators and three non-empty base64url segments. */
    static boolean looksLikeJwt(String token) {
        if (token == null || token.isEmpty()) return false;
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) return false;
        int secondDot = token.indexOf('.', firstDot + 1);
        if (secondDot <= firstDot + 1) return false;
        // No third dot.
        if (token.indexOf('.', secondDot + 1) >= 0) return false;
        // Tail segment must be non-empty (otherwise it's still malformed JWS).
        return secondDot + 1 < token.length();
    }
}
