package io.valkeyry.config.security.local;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the static {@code looksLikeJwt} guard that fixes the
 * "Missing dot delimiter(s)" {@code InvalidBearerTokenException} on misrouted bearers.
 */
class SafeBearerTokenAuthenticationConverterTest {

    @Test
    void rejectsNullOrEmptyTokens() {
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt(null));
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt(""));
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt("    "));
    }

    @Test
    void rejectsTokensWithoutThreeSegments() {
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt("plain-api-key"));
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt("only.one"));
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt("..."));
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt("a..b"));
        assertFalse(SafeBearerTokenAuthenticationConverter.looksLikeJwt("a.b.c.d"));
    }

    @Test
    void acceptsCompactJwsShape() {
        // header.payload.signature (only structural shape — not crypto-valid)
        assertTrue(SafeBearerTokenAuthenticationConverter.looksLikeJwt("aaaa.bbbb.cccc"));
    }
}
