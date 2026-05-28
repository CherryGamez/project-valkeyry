package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.config.error.IdempotentDuplicateException;
import io.valkeyry.config.error.SchemaValidationException;
import io.valkeyry.config.error.VirtualTableNotFoundException;
import io.valkeyry.config.security.TenantAccessGuard;
import io.valkeyry.config.service.VirtualTableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebFlux slice test for {@link VirtualTableController}.
 *
 * <p>Mocks the service + tenant guard so the controller, validation, exception mapping, and
 * security-disabled wiring can be exercised quickly without a database.</p>
 */
@WebFluxTest(controllers = VirtualTableController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration.class,
                org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class
        })
@org.springframework.test.context.ContextConfiguration(classes = VirtualTableControllerSliceTest.TestApp.class)
@Import({VirtualTableControllerSliceTest.MockBeans.class, ApiExceptionHandler.class})
class VirtualTableControllerSliceTest {

    @Autowired WebTestClient client;
    @Autowired VirtualTableService service;
    @Autowired TenantAccessGuard guard;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void wireGuard() {
        // Pass-through guard for every test.
        when(guard.check(any(), anyString())).thenReturn(Mono.empty());
        // Spring Security defaults CSRF ON for non-GET. Mutate the client so every
        // state-changing call from this test class carries a valid CSRF token.
        client = client.mutateWith(
                org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void declareReturns201AndBody() {
        VirtualTableView view = new VirtualTableView(UUID.randomUUID(), "demo", "customers",
                1L, true, mapper.createObjectNode(), Instant.now(), "test");
        when(service.declare(anyString(), anyString(), any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(view));
        client.post().uri("/api/v1/tenants/demo/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"tableName":"customers","schema":{"type":"object"}}""")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.tableName").isEqualTo("customers");
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void invalidPayloadIsRejected400() {
        client.post().uri("/api/v1/tenants/demo/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"tableName":"","schema":{"type":"object"}}""")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void duplicateIngestMapsTo409() {
        when(service.ingest(anyString(), anyString(), anyString(), any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new IdempotentDuplicateException("alice", "abc123")));
        client.post().uri("/api/v1/tenants/demo/tables/customers/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"recordKey":"alice","data":{"id":"alice"}}""")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.payloadHash").isEqualTo("abc123");
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void schemaViolationMapsTo422() {
        when(service.ingest(anyString(), anyString(), anyString(), any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new SchemaValidationException(List.of("'id' is required"))));
        client.post().uri("/api/v1/tenants/demo/tables/customers/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"recordKey":"alice","data":{}}""")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.violations[0]").isEqualTo("'id' is required");
    }

    @Test
    @WithMockUser
    void notFoundMapsTo404() {
        when(service.getActive(anyString(), anyString()))
                .thenReturn(Mono.error(new VirtualTableNotFoundException("demo", "missing")));
        client.get().uri("/api/v1/tenants/demo/tables/missing")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockUser
    void listTablesReturnsArray() {
        VirtualTableView v = new VirtualTableView(UUID.randomUUID(), "demo", "customers",
                1L, true, mapper.createObjectNode(), Instant.now(), "test");
        when(service.listTables("demo")).thenReturn(Flux.just(v));
        client.get().uri("/api/v1/tenants/demo/tables")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].tableName").isEqualTo("customers");
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = io.valkeyry.config.api.VirtualTableController.class,
            useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = { io.valkeyry.config.api.VirtualTableController.class }))
    static class TestApp { }

    @TestConfiguration
    static class MockBeans {
        @Bean VirtualTableService service() { return mock(VirtualTableService.class); }
        @Bean TenantAccessGuard guard() { return mock(TenantAccessGuard.class); }
    }
}
