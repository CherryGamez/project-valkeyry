package io.valkeyry.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Entry point for the Valkeyry Config (Headless Schema Registry) service.
 *
 * <p>Responsibilities</p>
 * <ul>
 *   <li>Expose a non-blocking WebFlux API to declare virtual tables, ingest records,
 *       search with dynamic criteria and browse version history.</li>
 *   <li>Store everything in a hybrid EAV-JSONB layout in PostgreSQL via R2DBC —
 *       no runtime DDL is ever executed.</li>
 *   <li>Enforce a bifurcated dual-track authentication pipeline:
 *       OIDC JWT for humans + LDAP/Basic/X-API-Key for headless agents.</li>
 * </ul>
 */
@SpringBootApplication
@EnableR2dbcRepositories
public class ValkeyryConfigApplication {
    public static void main(String[] args) {
        SpringApplication.run(ValkeyryConfigApplication.class, args);
    }
}
