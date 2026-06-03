package io.valkeyry.config.security.local;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.test.StepVerifier;

import javax.crypto.spec.SecretKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routing-level proof that the composite decoder dispatches by {@code iss}:
 * <ul>
 *   <li>Tokens minted with the local HS256 secret + {@code iss=valkeyry-local}
 *       must go through the local decoder.</li>
 *   <li>Tokens signed by an RS256 key with any other {@code iss} must go through the
 *       external decoder when SSO is enabled.</li>
 *   <li>External tokens must be rejected when SSO is disabled (external == null).</li>
 *   <li>An external token that crosses into the local-issuer namespace (forged iss claim)
 *       must fail HMAC verification — the local decoder doesn't trust the RSA signature.</li>
 * </ul>
 *
 * <p>No live OIDC issuer is required — we build a {@link NimbusReactiveJwtDecoder} from a
 * freshly-generated RSA key pair, which is exactly the shape Keycloak's JWKS would expose.</p>
 */
class CompositeReactiveJwtDecoderTest {

    private static final String LOCAL_ISS    = "valkeyry-local";
    private static final String EXTERNAL_ISS = "https://keycloak.example/realms/valkeyry";
    private static final byte[] HMAC_SECRET  = "integration-test-secret-must-be-32-bytes-or-more!".getBytes();

    private ReactiveJwtDecoder localDecoder() {
        return NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(HMAC_SECRET, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static String mintLocalHs256(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new MACSigner(HMAC_SECRET));
        return jwt.serialize();
    }

    private static String mintExternalRs256(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.toPrivateKey()));
        return jwt.serialize();
    }

    @Test
    void localIssuerRoutesToLocalDecoder() throws Exception {
        String token = mintLocalHs256(new JWTClaimsSet.Builder()
                .subject("admin").issuer(LOCAL_ISS)
                .claim("valkeyry.role", "admin")
                .claim("valkeyry.tenants", List.of("*"))
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build());

        CompositeReactiveJwtDecoder composite = new CompositeReactiveJwtDecoder(LOCAL_ISS, localDecoder(), null);
        StepVerifier.create(composite.decode(token))
                .assertNext(jwt -> {
                    assertEquals("admin", jwt.getSubject());
                    assertEquals(LOCAL_ISS, jwt.getClaim("iss"));
                    assertEquals("admin",  jwt.getClaim("valkeyry.role"));
                })
                .verifyComplete();
    }

    @Test
    void externalIssuerRoutesToExternalDecoderWhenSsoEnabled() throws Exception {
        // Generate an RSA key (the public half is what Keycloak would expose at /.well-known/openid-configuration → JWKS).
        RSAKey rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
                .keyID("kc-key-1")
                .generate();
        ReactiveJwtDecoder external = NimbusReactiveJwtDecoder
                .withPublicKey((RSAPublicKey) rsa.toPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        String token = mintExternalRs256(new JWTClaimsSet.Builder()
                .subject("alice").issuer(EXTERNAL_ISS)
                .claim("preferred_username", "alice")
                .claim("valkeyry.role", "writer")
                .claim("valkeyry.tenants", List.of("acme"))
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build(), rsa);

        CompositeReactiveJwtDecoder composite = new CompositeReactiveJwtDecoder(LOCAL_ISS, localDecoder(), external);
        StepVerifier.create(composite.decode(token))
                .assertNext(jwt -> {
                    assertEquals("alice", jwt.getSubject());
                    assertEquals(EXTERNAL_ISS, jwt.getClaim("iss"));
                    assertEquals("writer", jwt.getClaim("valkeyry.role"));
                })
                .verifyComplete();
    }

    @Test
    void externalIssuerRejectedWhenSsoDisabled() throws Exception {
        RSAKey rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("kc-key-2").generate();
        String token = mintExternalRs256(new JWTClaimsSet.Builder()
                .subject("alice").issuer(EXTERNAL_ISS)
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build(), rsa);

        CompositeReactiveJwtDecoder composite = new CompositeReactiveJwtDecoder(LOCAL_ISS, localDecoder(), null);
        StepVerifier.create(composite.decode(token))
                .expectError(JwtException.class)
                .verify();
    }

    @Test
    void forgedLocalIssuerOnRs256TokenFailsHmacVerification() throws Exception {
        // Attacker signs with their own RSA key but claims iss=valkeyry-local to trick routing.
        RSAKey rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("evil").generate();
        String token = mintExternalRs256(new JWTClaimsSet.Builder()
                .subject("attacker").issuer(LOCAL_ISS)
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build(), rsa);

        CompositeReactiveJwtDecoder composite = new CompositeReactiveJwtDecoder(LOCAL_ISS, localDecoder(), null);
        StepVerifier.create(composite.decode(token))
                .expectError(JwtException.class)   // local HS256 decoder must reject RSA signature
                .verify();
    }

    @Test
    void malformedTokenIsRejectedEarly() {
        CompositeReactiveJwtDecoder composite = new CompositeReactiveJwtDecoder(LOCAL_ISS, localDecoder(), null);
        StepVerifier.create(composite.decode("not.a.jwt"))
                .expectError(JwtException.class)
                .verify();
    }

    @Test
    void jwkSetExportAndImportRoundTripWorks() throws Exception {
        // Demonstrates the same RSA key set can be serialised as a JWKS doc (what Keycloak serves)
        // and re-imported — this is exactly what NimbusReactiveJwtDecoder.fromIssuerLocation does.
        RSAKey rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("portable").generate();
        JWKSet exported = new JWKSet(rsa.toPublicJWK());
        String json = exported.toString(true);
        JWKSet imported = JWKSet.parse(json);
        assertNotNull(imported.getKeyByKeyId("portable"));
        assertFalse(json.contains("\"d\":"), "Private exponent must never leak in the published JWKS");
    }

    @Test
    void localTokenWithoutIssuerClaimIsRejected() throws Exception {
        // CompositeReactiveJwtDecoder relies on iss to route. A token missing it is suspicious.
        String token = mintLocalHs256(new JWTClaimsSet.Builder()
                .subject("noiss")
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build());
        // No iss → not equal to localIssuer → no external → JwtException.
        CompositeReactiveJwtDecoder composite = new CompositeReactiveJwtDecoder(LOCAL_ISS, localDecoder(), null);
        StepVerifier.create(composite.decode(token))
                .expectError(JwtException.class)
                .verify();
    }
}
