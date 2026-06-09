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

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void batchIngestReportsPerRowFailures207() {
        // Row 0 inserts cleanly, row 1 hits a schema violation, row 2 is a duplicate, row 3
        // explodes with an unexpected NullPointerException. The controller must return all
        // four outcomes in a single 207 response so the caller can fix exactly the bad
        // rows without re-uploading the good ones.
        EntryView okView = new EntryView(UUID.randomUUID(), "demo", "customers", "row-0",
                1L, true, "abc", mapper.createObjectNode(), Instant.now(), "test");

        when(service.ingest(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("row-0"),
                any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(okView));
        when(service.ingest(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("row-1"),
                any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new SchemaValidationException(List.of("$.age: must be >= 0"))));
        when(service.ingest(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("row-2"),
                any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new IdempotentDuplicateException("row-2", "deadbeef")));
        when(service.ingest(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("row-3"),
                any(JsonNode.class), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new NullPointerException("boom")));

        client.post().uri("/api/v1/tenants/demo/tables/customers/entries:batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"entries":[
                          {"recordKey":"row-0","data":{"v":0}},
                          {"recordKey":"row-1","data":{"age":-1}},
                          {"recordKey":"row-2","data":{"v":2}},
                          {"recordKey":"row-3","data":{"v":3}}
                        ]}""")
                .exchange()
                .expectStatus().isEqualTo(207)
                .expectBody()
                .jsonPath("$.submitted").isEqualTo(4)
                .jsonPath("$.inserted").isEqualTo(1)
                .jsonPath("$.duplicates").isEqualTo(1)
                .jsonPath("$.failed").isEqualTo(2)
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].index").isEqualTo(1)
                .jsonPath("$.errors[0].recordKey").isEqualTo("row-1")
                .jsonPath("$.errors[0].errorType").isEqualTo("schema-violation")
                .jsonPath("$.errors[0].violations[0]").isEqualTo("$.age: must be >= 0")
                .jsonPath("$.errors[1].index").isEqualTo(3)
                .jsonPath("$.errors[1].recordKey").isEqualTo("row-3")
                .jsonPath("$.errors[1].errorType").isEqualTo("internal")
                .jsonPath("$.errors[1].traceId").exists();
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void batchIngestReturns422WhenEveryRowFails() {
        when(service.ingest(anyString(), anyString(), anyString(), any(JsonNode.class),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new SchemaValidationException(List.of("required field 'name'"))));
        client.post().uri("/api/v1/tenants/demo/tables/customers/entries:batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"entries":[
                          {"recordKey":"r0","data":{}},
                          {"recordKey":"r1","data":{}}
                        ]}""")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.failed").isEqualTo(2)
                .jsonPath("$.inserted").isEqualTo(0)
                .jsonPath("$.errors[0].errorType").isEqualTo("schema-violation")
                .jsonPath("$.errors[1].errorType").isEqualTo("schema-violation");
    }

    @Test
    @WithMockUser(authorities = {"ROLE_VALKEYRY_WRITER"})
    void batchIngestReturns201WhenAllRowsSucceed() {
        EntryView okView = new EntryView(UUID.randomUUID(), "demo", "customers", "x",
                1L, true, "abc", mapper.createObjectNode(), Instant.now(), "test");
        when(service.ingest(anyString(), anyString(), anyString(), any(JsonNode.class),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(okView));
        client.post().uri("/api/v1/tenants/demo/tables/customers/entries:batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"entries":[
                          {"recordKey":"a","data":{}},
                          {"recordKey":"b","data":{}}
                        ]}""")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.submitted").isEqualTo(2)
                .jsonPath("$.inserted").isEqualTo(2)
                .jsonPath("$.failed").isEqualTo(0);
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
