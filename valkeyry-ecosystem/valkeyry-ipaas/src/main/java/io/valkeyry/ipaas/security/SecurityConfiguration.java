package io.valkeyry.ipaas.security;

import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final DynamicWorkspaceAuthorizationManager workspaceManager;
    private final IpaasProperties props;

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        var commonPermits = new String[] {
                "/actuator/health", "/actuator/info", "/actuator/prometheus",
                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**"
        };
        if (props.getSecurity().isAllowAnonymous()) {
            // Local-dev: skip OIDC entirely. Multi-publish, metrics, DLQ — all open.
            http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsSource()))
                .authorizeExchange(ex -> ex.anyExchange().permitAll());
            return http.build();
        }

        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsSource()))
            .authorizeExchange(ex -> ex
                .pathMatchers(commonPermits).permitAll()
                .pathMatchers("/api/v1/multi-publish").authenticated()   // per-target RBAC done in service
                .pathMatchers("/api/v1/{tenantId}/{projectId}/**").access(workspaceManager::check)
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.getCors().getAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("X-RateLimit-Remaining"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
