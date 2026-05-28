package io.valkeyry.ipaas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Valkeyry iPaaS — reactive, multi-tenant messaging middleware bootstrap.
 *
 * <p>Architecture: Spring WebFlux (Netty) + R2DBC Postgres + Valkey (Lettuce) +
 * polymorphic brokers (RabbitMQ/Kafka/ActiveMQ) + dynamic Vault-resolved object
 * storage (S3-compatible / Azure Blob). All processing is non-blocking end-to-end.
 */
@SpringBootApplication
@EnableScheduling
public class IpaasApplication {
    public static void main(String[] args) {
        SpringApplication.run(IpaasApplication.class, args);
    }
}
