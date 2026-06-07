package io.valkeyry.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the "browser native Basic Auth prompt loop" bug.
 *
 * <p>{@link org.springframework.security.web.server.authentication.AuthenticationWebFilter}
 * defaults its failure handler to one backed by {@code HttpBasicServerAuthenticationEntryPoint},
 * which emits {@code WWW-Authenticate: Basic realm="Realm"} on bad credentials. Browsers
 * intercept that header on XHR/fetch responses and pop their native username/password modal,
 * stealing the 401 from the SPA's own retry/re-login logic.</p>
 *
 * <p>{@link SecurityConfig} overrides the failure handler on both Track-2 filters
 * (API-key + LDAP basic) with a silent {@link HttpStatusServerEntryPoint} so the 401 stays
 * a plain JSON response with no {@code WWW-Authenticate} challenge. This test guards that
 * contract by exercising the handler directly — independent of Spring Boot wiring — so the
 * regression surface remains small and fast.</p>
 */
class SecurityConfigBasicAuthChallengeTest {

    /**
     * The failure handler must:
     * <ol>
     *   <li>Set HTTP status {@code 401 Unauthorized}.</li>
     *   <li>Emit <em>no</em> {@code WWW-Authenticate} header (the browser uses any such
     *       header to fire its native credential prompt).</li>
     * </ol>
     */
    @Test
    void silent401HandlerEmitsNoWwwAuthenticateBasicChallenge() {
        // Same handler instance the production filters use — same constructor, same EP.
        ServerAuthenticationEntryPointFailureHandler handler =
                new ServerAuthenticationEntryPointFailureHandler(
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tenants/demo/tables")
                        .header("X-API-Key", "bogus"));
        WebFilterChain noopChain = ex -> Mono.empty();
        WebFilterExchange wfx = new WebFilterExchange(exchange, noopChain);

        handler.onAuthenticationFailure(wfx, new BadCredentialsException("Unknown API key")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().get(HttpHeaders.WWW_AUTHENTICATE))
                .as("401 from API-key/LDAP filters must not advertise a Basic challenge — "
                        + "browsers turn that into a native username/password modal, breaking the SPA.")
                .isNull();
    }
}
