package io.valkeyry.config.security.local;

import io.valkeyry.config.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.SignedJWT;

import java.util.List;
import java.text.ParseException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link LocalJwtService} mints a syntactically valid compact JWS that other
 * components (composite decoder, authorities converter) can read.
 */
class LocalJwtServiceTest {

    private LocalJwtService svc;

    @BeforeEach
    void setUp() {
        AuthProperties p = new AuthProperties();
        p.setJwtSecret("test-secret-must-be-32-bytes-or-longer-for-hmac-sha256!");
        p.setJwtIssuer("valkeyry-test");
        p.setJwtTtlSeconds(300);
        svc = new LocalJwtService(p);
    }

    @Test
    void mintProducesThreeSegmentCompactJws() throws ParseException {
        String token = svc.mint("alice", "writer", List.of("acme", "demo"), "LOCAL");
        assertNotNull(token);
        assertEquals(2, token.chars().filter(c -> c == '.').count(),
                "JWT must have exactly two '.' separators");
        SignedJWT parsed = SignedJWT.parse(token);
        assertEquals("alice", parsed.getJWTClaimsSet().getSubject());
        assertEquals("valkeyry-test", parsed.getJWTClaimsSet().getIssuer());
        assertEquals("writer", parsed.getJWTClaimsSet().getStringClaim("valkeyry.role"));
        assertEquals(List.of("acme", "demo"), parsed.getJWTClaimsSet().getStringListClaim("valkeyry.tenants"));
        assertEquals("LOCAL", parsed.getJWTClaimsSet().getStringClaim("valkeyry.source"));
    }

    @Test
    void mintDefaultsRoleAndSourceWhenNull() throws ParseException {
        String token = svc.mint("anon", null, null, null);
        SignedJWT parsed = SignedJWT.parse(token);
        assertEquals("reader", parsed.getJWTClaimsSet().getStringClaim("valkeyry.role"));
        assertEquals("local",  parsed.getJWTClaimsSet().getStringClaim("valkeyry.source"));
        assertTrue(parsed.getJWTClaimsSet().getStringListClaim("valkeyry.tenants").isEmpty());
    }
}
