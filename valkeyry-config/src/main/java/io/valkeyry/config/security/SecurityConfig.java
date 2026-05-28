package io.valkeyry.config.security;

import io.valkeyry.config.config.ApiKeyProperties;
import io.valkeyry.config.config.LdapProperties;
import io.valkeyry.config.security.apikey.ApiKeyAuthenticationConverter;
import io.valkeyry.config.security.apikey.ApiKeyAuthenticationManager;
import io.valkeyry.config.security.ldap.LdapBasicAuthenticationConverter;
import io.valkeyry.config.security.ldap.LdapBasicAuthenticationManager;
import io.valkeyry.config.security.oidc.JwtTenantAuthoritiesConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;

/**
 * Dual-track security:
 * <ul>
 *   <li>Track 1 (humans/tenants) — OIDC Bearer JWT validated against {@code spring.security.oauth2.resourceserver}.</li>
 *   <li>Track 2 (headless agents) — HTTP Basic against LDAP <em>or</em> {@code X-API-Key} header.</li>
 * </ul>
 *
 * <p>Both pipelines emit a normalised authentication carrying {@code SCOPE_tenant:&lt;id&gt;}
 * authorities so {@link TenantAccessGuard} treats them identically. The OIDC chain runs first;
 * if no Bearer header is present the Basic/API-key filters take over.</p>
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({LdapProperties.class, ApiKeyProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain valkeyrySecurityWebFilterChain(ServerHttpSecurity http,
                                                                 LdapProperties ldapProps,
                                                                 ApiKeyProperties apiKeyProps) {
        AuthenticationWebFilter basicFilter = new AuthenticationWebFilter(new LdapBasicAuthenticationManager(ldapProps));
        basicFilter.setServerAuthenticationConverter(new LdapBasicAuthenticationConverter());

        AuthenticationWebFilter apiKeyFilter = new AuthenticationWebFilter(new ApiKeyAuthenticationManager(apiKeyProps));
        apiKeyFilter.setServerAuthenticationConverter(new ApiKeyAuthenticationConverter());

        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                // Static audit console (the API calls it makes are still authenticated)
                .pathMatchers(HttpMethod.GET, "/", "/audit", "/audit/**", "/favicon.ico").permitAll()
                // Write operations require an explicit writer authority on top of tenant entitlement.
                .pathMatchers(HttpMethod.POST,   "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers(HttpMethod.PUT,    "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers(HttpMethod.PATCH,  "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers(HttpMethod.DELETE, "/api/v1/tenants/**").hasAuthority("ROLE_VALKEYRY_WRITER")
                .pathMatchers("/api/**").authenticated()
                .anyExchange().permitAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new JwtTenantAuthoritiesConverter())))
            .addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .addFilterAt(basicFilter, SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }
}
