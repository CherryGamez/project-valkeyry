package io.valkeyry.config.security.local;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.valkeyry.config.config.AuthProperties;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;

/**
 * Mints local HS256 JWTs for form-login and local-admin bypass.
 *
 * <p>Tokens follow the same claim shape the rest of the codebase already understands
 * ({@code valkeyry.tenants}, {@code valkeyry.role}, {@code roles}, {@code scope}), so
 * {@link io.valkeyry.config.security.oidc.JwtTenantAuthoritiesConverter} keeps working
 * unchanged.</p>
 */
@Component
public class LocalJwtService {

    private final AuthProperties props;

    public LocalJwtService(AuthProperties props) { this.props = props; }

    /**
     * Mint an HS256 token.
     *
     * @param subject    {@code sub} — username
     * @param role       {@code valkeyry.role} — "reader" | "writer" | "admin"
     * @param tenants    {@code valkeyry.tenants} claim — set of tenant slugs the user can access
     * @param source     {@code valkeyry.source} — "local" | "ldap" | "sso" — auditable origin tag
     */
    public String mint(String subject, String role, Collection<String> tenants, String source) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(props.getJwtIssuer())
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(props.getJwtTtlSeconds())))
                    .claim("valkeyry.role", role == null ? "reader" : role)
                    .claim("valkeyry.tenants", tenants == null ? java.util.List.of() : java.util.List.copyOf(tenants))
                    .claim("valkeyry.source", source == null ? "local" : source);
            // "roles" mirror to keep external resource-server style consumers happy.
            claims.claim("roles", role == null ? java.util.List.of("reader") : java.util.List.of(role));
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secretKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to mint local JWT", e);
        }
    }

    /** Exposed for the composite decoder so it can reuse the same secret. */
    public byte[] secretKey() {
        byte[] raw = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            // Pad to 32 bytes so Nimbus accepts the key for HS256 in dev defaults.
            byte[] padded = new byte[32];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            return padded;
        }
        return raw;
    }

    @SuppressWarnings("unused")
    private SecretKeySpec keySpec() { return new SecretKeySpec(secretKey(), "HmacSHA256"); }
}
