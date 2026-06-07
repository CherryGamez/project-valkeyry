package io.valkeyry.config.security;

import io.valkeyry.config.config.ApiKeyProperties;
import io.valkeyry.config.config.AuthProperties;
import io.valkeyry.config.config.LdapProperties;
import io.valkeyry.config.security.apikey.ApiKeyAuthenticationConverter;
import io.valkeyry.config.security.apikey.ApiKeyAuthenticationManager;
import io.valkeyry.config.security.ldap.LdapBasicAuthenticationConverter;
import io.valkeyry.config.security.ldap.LdapBasicAuthenticationManager;
import io.valkeyry.config.security.local.SafeBearerTokenAuthenticationConverter;
import io.valkeyry.config.security.oidc.JwtTenantAuthoritiesConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;

/**
 * Dual-track security:
 * <ul>
 *   <li>Track 1 (humans/tenants) — Bearer JWT (local HS256 form-login or external OIDC) validated
 *       by {@link io.valkeyry.config.security.local.CompositeReactiveJwtDecoder}. A
 *       {@link SafeBearerTokenAuthenticationConverter} runs first so non-JWT bearers (e.g. a
 *       misrouted API key) don't blow up the Reactor chain with
 *       <em>"Missing dot delimiter(s)"</em>.</li>
 *   <li>Track 2 (headless agents) — HTTP Basic against LDAP <em>or</em> {@code X-API-Key} header.
 *       LDAP-basic is only mounted when {@code valkeyry.auth.ldap-enabled=true}.</li>
 * </ul>
 *
 * <p>Both pipelines emit a normalised authentication carrying {@code SCOPE_tenant:&lt;id&gt;}
 * authorities so {@link TenantAccessGuard} treats them identically. Admin-only endpoints
 * additionally require {@code SCOPE_admin}, mapped from the {@code valkeyry.role=admin} claim.</p>
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@EnableConfigurationProperties({LdapProperties.class, ApiKeyProperties.class, AuthProperties.class})
public class SecurityConfig {

    /**
     * Silent 401 failure handler — returns {@code 401 Unauthorized} <em>without</em> a
     * {@code WWW-Authenticate: Basic} challenge header.
     *
     * <p>The default failure handler installed by {@link AuthenticationWebFilter} is
     * {@code ServerAuthenticationEntryPointFailureHandler(HttpBasicServerAuthenticationEntryPoint)},
     * which emits {@code WWW-Authenticate: Basic realm="Realm"} on bad credentials. Browsers
     * intercept that header on XHR/fetch responses and pop a native username/password modal,
     * stealing the 401 from the JS layer — including the SPA's own retry/re-login logic.</p>
     *
     * <p>Both Track-2 filters (API-key and optional LDAP basic) are explicitly opt-in via headers
     * the JS console sends; when those headers contain bad credentials we want a clean 401 the
     * SPA can handle (clear token, redirect to /login), not a browser-level prompt.</p>
     */
    private static final ServerAuthenticationEntryPointFailureHandler SILENT_401 =
            new ServerAuthenticationEntryPointFailureHandler(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED));

    @Bean
    public SecurityWebFilterChain valkeyrySecurityWebFilterChain(ServerHttpSecurity http,
                                                                 LdapProperties ldapProps,
                                                                 ApiKeyProperties apiKeyProps,
                                                                 AuthProperties authProps,
                                                                 ReactiveJwtDecoder jwtDecoder) {
        AuthenticationWebFilter apiKeyFilter = new AuthenticationWebFilter(new ApiKeyAuthenticationManager(apiKeyProps));
        apiKeyFilter.setServerAuthenticationConverter(new ApiKeyAuthenticationConverter());
        apiKeyFilter.setAuthenticationFailureHandler(SILENT_401);

        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                // Public HTMX shell — every meaningful action behind it still hits an authenticated API.
                .pathMatchers(HttpMethod.GET,
                        "/", "/audit", "/audit/**", "/favicon.ico",
                        "/login", "/login.html",
                        "/admin", "/admin.html",
                        "/tools", "/tools.html",
                        "/assets/**", "/static/**").permitAll()
                // OpenAPI / Swagger UI — public so devs can browse the contract without a token.
                .pathMatchers(HttpMethod.GET,
                        "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                        "/swagger-ui.html", "/swagger-ui/**", "/webjars/**").permitAll()
                // Auth endpoints — login + config are public; /me + /logout need a token.
                .pathMatchers(HttpMethod.GET,  "/api/v1/auth/config").permitAll()
                .pathMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                .pathMatchers("/api/v1/auth/**").authenticated()
                // Admin CRUD — admin role only (enforced again at controller level via @PreAuthorize).
                .pathMatchers("/api/v1/admin/**").hasAuthority("SCOPE_admin")
                // Tools converter — any authenticated user can use it; useful for both readers and writers.
                .pathMatchers("/api/v1/tools/**").authenticated()
                // Write operations require an explicit writer authority on top of tenant entitlement.
                .pathMatchers(HttpMethod.POST,   "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers(HttpMethod.PUT,    "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers(HttpMethod.PATCH,  "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers(HttpMethod.DELETE, "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers("/api/**").authenticated()
                .anyExchange().permitAll())
            .oauth2ResourceServer(o -> o
                .bearerTokenConverter(new SafeBearerTokenAuthenticationConverter())
                .jwt(j -> j.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(new JwtTenantAuthoritiesConverter())))
            .addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION);

        if (authProps.isLdapEnabled()) {
            AuthenticationWebFilter basicFilter = new AuthenticationWebFilter(new LdapBasicAuthenticationManager(ldapProps));
            basicFilter.setServerAuthenticationConverter(new LdapBasicAuthenticationConverter());
            basicFilter.setAuthenticationFailureHandler(SILENT_401);
            http.addFilterAt(basicFilter, SecurityWebFiltersOrder.AUTHENTICATION);
        }

        return http.build();
    }
}
