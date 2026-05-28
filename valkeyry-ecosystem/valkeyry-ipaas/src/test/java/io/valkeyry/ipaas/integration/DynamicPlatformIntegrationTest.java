package io.valkeyry.ipaas.integration;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.domain.QueueAsset;
import io.valkeyry.ipaas.queue.QueueManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.util.UUID;

/**
 * End-to-end integration test orchestrating real Postgres, Valkey 8.0, and RabbitMQ
 * via Testcontainers. Requires Docker on the host.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
            "spring.cloud.vault.token=dev-root-token"
        })
@AutoConfigureWebTestClient
class DynamicPlatformIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ipaas").withUsername("ipaas").withPassword("ipaas");

    @Container
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.0"))
                    .withExposedPorts(6379);

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/ipaas");
        r.add("spring.r2dbc.username", POSTGRES::getUsername);
        r.add("spring.r2dbc.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);

        r.add("spring.data.redis.host", VALKEY::getHost);
        r.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));

        r.add("ipaas.brokers.rabbitmq.host", RABBIT::getHost);
        r.add("ipaas.brokers.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("ipaas.brokers.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("ipaas.brokers.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired QueueManagementService queueService;
    @Autowired BrokerClientFactory brokerFactory;

    @Test
    @DisplayName("Service catalog declares an asset; lazy publish creates it if missing.")
    void declareCatalog_thenLazyProvision() {
        String tenantId  = "acme-corp";
        String projectId = "payments-prod";

        StepVerifier.create(queueService.declareFromCatalog(
                        tenantId, projectId, "orders", "RABBITMQ", "QUEUE", 600))
                .assertNext(a -> {
                    assert a.getProvisioningMode().equals("CATALOG");
                    assert a.getBrokerType().equals("RABBITMQ");
                })
                .verifyComplete();

        StepVerifier.create(queueService.resolveOrLazyProvision(tenantId, projectId, "events-new"))
                .assertNext((QueueAsset a) -> {
                    assert a.getProvisioningMode().equals("LAZY_PROVISIONED");
                })
                .verifyComplete();
    }
}
