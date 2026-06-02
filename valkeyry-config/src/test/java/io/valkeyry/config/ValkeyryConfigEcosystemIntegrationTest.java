package io.valkeyry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.plugin.core.PluginContext;
import io.valkeyry.plugin.core.PluginEngine;
import io.valkeyry.plugin.core.PluginLog;
import io.valkeyry.plugin.core.PluginResult;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h2>End-to-end ecosystem integration test</h2>
 *
 * <p>Spins up only Postgres + an in-process OIDC issuer (mock-oauth2-server). LDAP is faked via
 * the {@code valkeyry.api-keys.table} bridge so that the plugin's Track-2 (headless) flow is
 * exercised without requiring a real OpenLDAP container — which keeps the test runnable on
 * machines that don't have one preloaded. A full LDAP variant of this test lives in
 * {@code LdapIntegrationTest} (skipped if the {@code testcontainers.openldap.enabled=true}
 * property is missing).</p>
 *
 * <p>Verifies the canonical flow:</p>
 * <ol>
 *   <li>plugin (Track-2 API-Key) declares a virtual table + batch-ingests 3 records;</li>
 *   <li>repeating the plugin run produces 3 duplicates via the Idempotency Guard;</li>
 *   <li>a human OIDC bearer token can read both the latest record and the version trail;</li>
 *   <li>a wrong-tenant OIDC token is rejected.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@EnabledIf("io.valkeyry.config.ValkeyryConfigEcosystemIntegrationTest#isDockerAvailable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ValkeyryConfigEcosystemIntegrationTest {

    /**
     * Gate the entire test class on a working Docker daemon. Returning {@code false} causes
     * JUnit to <em>skip</em> the class instead of failing it, which keeps {@code mvn verify}
     * green on developer machines / CI workers that don't have Docker (e.g. sandboxes,
     * lightweight self-hosted runners). Devs with Docker get the full integration coverage.
     */
    static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable ignored) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("valkeyry_config")
            .withUsername("valkeyry")
            .withPassword("valkeyry");

    private static MockOAuth2Server OAUTH;

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @BeforeAll
    static void startOidc() throws IOException {
        OAUTH = new MockOAuth2Server();
        OAUTH.start();
    }

    @AfterAll
    static void stopOidc() throws IOException {
        if (OAUTH != null) OAUTH.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry reg) {
        reg.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/valkeyry_config");
        reg.add("spring.r2dbc.username", POSTGRES::getUsername);
        reg.add("spring.r2dbc.password", POSTGRES::getPassword);
        reg.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        reg.add("spring.flyway.user", POSTGRES::getUsername);
        reg.add("spring.flyway.password", POSTGRES::getPassword);
        reg.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> OAUTH.issuerUrl("default").toString());
        reg.add("valkeyry.api-keys.table",
                () -> "plugin-test-key:demo-tenant");
    }

    @Test
    @Order(1)
    void pluginIngestsThenDedupsThenOidcReads(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // ----- 1. lay out a sample project for the plugin -----
        Path schemaDir = Files.createDirectories(dir.resolve("schemas"));
        Files.writeString(schemaDir.resolve("customers.schema.json"), """
                { "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "required":["id","tier"],
                  "properties":{"id":{"type":"string"},"tier":{"type":"string"}}
                }""");
        Path entries = Files.createDirectories(dir.resolve("data/customers"));
        Files.writeString(entries.resolve("alice.json"), "{\"id\":\"alice\",\"tier\":\"gold\"}");
        Files.writeString(entries.resolve("bob.json"),   "{\"id\":\"bob\",\"tier\":\"silver\"}");
        Files.writeString(entries.resolve("carol.json"), "{\"id\":\"carol\",\"tier\":\"platinum\"}");

        Path manifest = dir.resolve("valkeyry-config.yaml");
        Files.writeString(manifest, """
                endpoint: http://localhost:%d
                tenant: demo-tenant
                auth:
                  type: api-key
                  apiKey: plugin-test-key
                tables:
                  - name: customers
                    schema: schemas/customers.schema.json
                    entries: data/customers/*.json
                """.formatted(port));

        PluginLog log = silentLog();

        // First run — 3 inserts
        PluginResult run1 = PluginEngine.run(new PluginContext(manifest, dir, log));
        assertThat(run1.submitted()).isEqualTo(3);
        assertThat(run1.inserted()).isEqualTo(3);
        assertThat(run1.duplicates()).isZero();

        // Second run — Idempotency Guard kicks in
        PluginResult run2 = PluginEngine.run(new PluginContext(manifest, dir, log));
        assertThat(run2.submitted()).isEqualTo(3);
        assertThat(run2.inserted()).isZero();
        assertThat(run2.duplicates()).isEqualTo(3);

        // Mutate alice → bumps version
        Files.writeString(entries.resolve("alice.json"), "{\"id\":\"alice\",\"tier\":\"platinum\"}");
        PluginResult run3 = PluginEngine.run(new PluginContext(manifest, dir, log));
        assertThat(run3.inserted()).isEqualTo(1);
        assertThat(run3.duplicates()).isEqualTo(2);

        // ----- 2. OIDC reads -----
        String validToken = OAUTH.issueToken("default", "carol@acme.io",
                new no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback(
                        "default",
                        "carol",
                        "JWT",
                        java.util.List.of("valkeyry-config"),
                        java.util.Map.of("valkeyry.tenants", java.util.List.of("demo-tenant")),
                        3600L
                )).serialize();

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> latest = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables/customers/entries/alice"))
                .header("Authorization", "Bearer " + validToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(latest.statusCode()).isEqualTo(200);
        assertThat(latest.body()).contains("platinum").contains("\"version\":2");

        HttpResponse<String> history = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables/customers/entries/alice/history"))
                .header("Authorization", "Bearer " + validToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).contains("\"version\":2").contains("\"version\":1");

        // Wrong-tenant token must be refused
        String wrongToken = OAUTH.issueToken("default", "eve@acme.io",
                new no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback(
                        "default", "eve", "JWT",
                        java.util.List.of("valkeyry-config"),
                        java.util.Map.of("valkeyry.tenants", java.util.List.of("other-tenant")),
                        3600L)).serialize();
        HttpResponse<String> forbidden = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables/customers/entries/alice"))
                .header("Authorization", "Bearer " + wrongToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(403);

        // ----- 3. Audit trail must show full history for alice -----
        HttpResponse<String> aliceAudit = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port +
                "/api/v1/tenants/demo-tenant/audit?tableName=customers&recordKey=alice"))
                .header("Authorization", "Bearer " + validToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(aliceAudit.statusCode()).isEqualTo(200);
        assertThat(aliceAudit.body())
                .contains("INGEST_RECORD")
                .contains("DEDUP_SKIP")            // run2 produced 3 dedup_skip events for alice/bob/carol
                .contains("\"actorTrack\":\"API_KEY\"")
                .contains("platinum");             // after-value of the version-2 ingest

        // Table-level audit: declare + revise are present.
        HttpResponse<String> tableAudit = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port +
                "/api/v1/tenants/demo-tenant/audit?tableName=customers"))
                .header("Authorization", "Bearer " + validToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tableAudit.statusCode()).isEqualTo(200);
        assertThat(tableAudit.body()).contains("DECLARE_TABLE");
    }

    @Test
    @Order(2)
    void apiKeyBatchEndpointReturnsProperSummary() throws Exception {
        // Direct hit at the batch endpoint to make sure Track-2 (headless) path is
        // independently verifiable.
        String body = mapper.writeValueAsString(java.util.Map.of(
                "entries", java.util.List.of(
                        java.util.Map.of("recordKey", "dave-" + UUID.randomUUID(),
                                "data", java.util.Map.of("id", "dave", "tier", "bronze")))));
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables/customers/entries:batch"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "plugin-test-key")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.body()).contains("\"inserted\":1");
    }

    @Test
    @Order(3)
    void anonymousIsRejected() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void oidcWithoutWriterRoleCannotWrite() throws Exception {
        // Same tenant entitlement as the happy path, but no `valkeyry.role: writer` claim.
        String readerToken = OAUTH.issueToken("default", "reader@acme.io",
                new no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback(
                        "default", "reader", "JWT",
                        java.util.List.of("valkeyry-config"),
                        java.util.Map.of("valkeyry.tenants", java.util.List.of("demo-tenant")),
                        3600L)).serialize();

        HttpRequest write = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables"))
                .header("Authorization", "Bearer " + readerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"tableName":"customers","schema":{"type":"object"}}"""))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(write, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(403);

        // But reading remains permitted.
        HttpResponse<String> read = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables"))
                .header("Authorization", "Bearer " + readerToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(5)
    void oidcWithWriterRoleCanWriteAndAuditCapturesActor() throws Exception {
        String writerToken = OAUTH.issueToken("default", "writer@acme.io",
                new no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback(
                        "default", "writer-svc", "JWT",
                        java.util.List.of("valkeyry-config"),
                        java.util.Map.of(
                                "valkeyry.tenants", java.util.List.of("demo-tenant"),
                                "valkeyry.role",    "writer"),
                        3600L)).serialize();

        // Revise the customers schema (a no-op schema change) — should succeed and audit-log REVISE_TABLE.
        HttpResponse<String> resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/tenants/demo-tenant/tables"))
                .header("Authorization", "Bearer " + writerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"tableName":"customers","schema":{"$schema":"https://json-schema.org/draft/2020-12/schema",
                        "type":"object","required":["id","tier","email"],
                        "properties":{"id":{"type":"string"},"tier":{"type":"string"},"email":{"type":"string"}}}}"""))
                .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);

        HttpResponse<String> audit = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port +
                        "/api/v1/tenants/demo-tenant/audit?actor=writer"))
                .header("Authorization", "Bearer " + writerToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(audit.body())
                .contains("REVISE_TABLE")
                .contains("\"actorTrack\":\"OIDC\"")
                .contains("\"actor\":\"writer\"");   // jwt subject
    }

    private static PluginLog silentLog() {
        return new PluginLog() {
            @Override public void info(String msg) { System.out.println("[plugin] " + msg); }
            @Override public void warn(String msg) { System.out.println("[plugin][WARN] " + msg); }
            @Override public void error(String msg, Throwable t) { System.err.println("[plugin][ERR] " + msg); }
        };
    }

    @SuppressWarnings("unused")
    private static String basicAuth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }
}
