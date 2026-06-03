package io.valkeyry.config.security.local;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Routes incoming JWTs to either the {@code local HS256} verifier (form-login tokens minted by
 * {@link LocalJwtService}) or, when SSO is enabled, the external OIDC verifier.
 *
 * <p>Routing rule: peek at the unverified {@code iss} claim. If it matches the configured
 * {@code valkeyry.auth.jwt-issuer}, use the local decoder; otherwise hand off to the external
 * decoder. If no external decoder is configured (SSO disabled), non-local tokens are rejected.</p>
 */
public class CompositeReactiveJwtDecoder implements ReactiveJwtDecoder {

    private final String localIssuer;
    private final ReactiveJwtDecoder local;
    private final ReactiveJwtDecoder external; // nullable

    public CompositeReactiveJwtDecoder(String localIssuer, ReactiveJwtDecoder local, ReactiveJwtDecoder external) {
        this.localIssuer = localIssuer;
        this.local = local;
        this.external = external;
    }

    @Override
    public Mono<Jwt> decode(String token) throws JwtException {
        String iss;
        try {
            iss = (String) JWTParser.parse(token).getJWTClaimsSet().getClaim("iss");
        } catch (Exception e) {
            return Mono.error(new JwtException("Malformed JWT (cannot read issuer)", e));
        }
        if (localIssuer != null && localIssuer.equals(iss)) {
            return local.decode(token);
        }
        if (external != null) {
            return external.decode(token);
        }
        return Mono.error(new JwtException("External issuer rejected (SSO disabled): " + iss));
    }
}
