package io.valkeyry.config.config;

import io.valkeyry.config.domain.admin.AppUser;
import io.valkeyry.config.repo.AppUserRepository;
import io.valkeyry.config.security.local.LocalJwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.UUID;

import io.valkeyry.config.security.local.CompositeReactiveJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationContext;

/**
 * Wires the local-auth subsystem at startup:
 * <ul>
 *   <li>{@link PasswordEncoder} bean (BCrypt) used everywhere passwords are touched.</li>
 *   <li>{@link ReactiveJwtDecoder} — {@link CompositeReactiveJwtDecoder} that always validates
 *       local HS256 tokens minted by {@link LocalJwtService} and additionally validates external
 *       OIDC tokens when {@code valkeyry.auth.sso-enabled=true} and {@code VALKEYRY_OIDC_ISSUER}
 *       is set.</li>
 *   <li>One-shot seeder that ensures the {@code admin} {@link AppUser} row exists in the database
 *       so admins can log in even after the env-var bypass is disabled in production.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthBootstrapConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveJwtDecoder valkeyryJwtDecoder(AuthProperties props,
                                                 LocalJwtService localJwtService,
                                                 @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String externalIssuer) {
        SecretKey key = new SecretKeySpec(localJwtService.secretKey(), "HmacSHA256");
        ReactiveJwtDecoder local = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();

        ReactiveJwtDecoder external = null;
        if (props.isSsoEnabled() && externalIssuer != null && !externalIssuer.isBlank()) {
            try {
                external = ReactiveJwtDecoders.fromIssuerLocation(externalIssuer);
                log.info("OIDC resource-server enabled, issuer={}", externalIssuer);
            } catch (RuntimeException e) {
                log.warn("OIDC issuer {} unreachable — SSO disabled at runtime: {}", externalIssuer, e.getMessage());
            }
        } else {
            log.info("OIDC resource-server disabled (sso-enabled={}, issuer-uri='{}')",
                    props.isSsoEnabled(), externalIssuer);
        }
        return new CompositeReactiveJwtDecoder(props.getJwtIssuer(), local, external);
    }

    /**
     * Seed (or refresh) the built-in admin user once the application is fully wired and Flyway
     * has finished. Idempotent: only inserts when no row matches the configured username, and
     * never overwrites an existing password (operators may have already rotated it via the API).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedBuiltinAdmin(ApplicationReadyEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        AuthProperties props = ctx.getBean(AuthProperties.class);
        AppUserRepository repo = ctx.getBean(AppUserRepository.class);
        PasswordEncoder encoder = ctx.getBean(PasswordEncoder.class);
        String username = props.getAdminUsername();
        repo.findByUsername(username)
                .switchIfEmpty(reactor.core.publisher.Mono.defer(() -> {
                    AppUser admin = new AppUser();
                    admin.setId(UUID.randomUUID());
                    admin.setUsername(username);
                    admin.setDisplayName("Built-in administrator");
                    admin.setPasswordHash(encoder.encode(props.getAdminPassword()));
                    admin.setRole("admin");
                    admin.setSource("LOCAL");
                    admin.setEnabled(true);
                    admin.setCreatedAt(Instant.now());
                    admin.setCreatedBy("bootstrap");
                    return repo.save(admin).doOnSuccess(u ->
                            log.info("Seeded built-in admin user '{}' (id={})", u.getUsername(), u.getId()));
                }))
                .doOnError(e -> log.warn("Could not seed built-in admin '{}': {}", username, e.toString()))
                .onErrorComplete()
                .subscribe();
    }
}
