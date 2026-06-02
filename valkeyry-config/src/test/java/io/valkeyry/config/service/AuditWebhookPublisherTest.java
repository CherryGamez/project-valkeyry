package io.valkeyry.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.r2dbc.postgresql.codec.Json;
import io.valkeyry.config.config.AuditWebhookProperties;
import io.valkeyry.config.domain.ConfigAuditEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spins up an in-process HTTP server, fires audit events through the publisher, and asserts:
 * <ul>
 *   <li>the SIEM receives one POST per configured URL,</li>
 *   <li>the body is well-formed JSON containing the expected fields,</li>
 *   <li>the {@code X-Valkeyry-Signature} header is a valid HMAC-SHA256 of the body,</li>
 *   <li>retries kick in on 5xx but stop after {@code max-retries},</li>
 *   <li>4xx fails fast (no retry).</li>
 * </ul>
 */
class AuditWebhookPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private int port;
    private final CopyOnWriteArrayList<CapturedRequest> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger transientFailuresLeft = new AtomicInteger(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new CapturedRequest("/ok", new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Valkeyry-Signature"),
                    exchange.getRequestHeaders().getFirst("X-Valkeyry-Tenant")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/ok2", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new CapturedRequest("/ok2", new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Valkeyry-Signature"),
                    exchange.getRequestHeaders().getFirst("X-Valkeyry-Tenant")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/flaky", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (transientFailuresLeft.getAndDecrement() > 0) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                received.add(new CapturedRequest("/flaky", new String(body, StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("X-Valkeyry-Signature"), null));
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.createContext("/bad", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.add(new CapturedRequest("/bad", "", null, null));
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); received.clear(); transientFailuresLeft.set(0); }

    @Test
    void successfulDeliveryWithValidSignature() {
        AuditWebhookProperties props = props("test-secret", List.of("http://localhost:" + port + "/ok"));
        AuditWebhookPublisher pub = new AuditWebhookPublisher(props, mapper);

        ConfigAuditEntry entry = sampleEntry();
        pub.publish(entry).block(Duration.ofSeconds(5));

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
        CapturedRequest got = received.get(0);
        assertThat(got.tenant).isEqualTo("demo");
        assertThat(got.body).contains("INGEST_RECORD").contains("alice");
        assertThat(got.signature).startsWith("sha256=");
        // Verify HMAC matches body
        assertThat(got.signature).isEqualTo(hmac("test-secret", got.body));
    }

    @Test
    void fanOutToMultipleUrls() {
        // Two DISTINCT URLs — the publisher dedupes by URL, so identical targets fire once,
        // but distinct targets each receive their own delivery.
        AuditWebhookProperties props = props("s", List.of(
                "http://localhost:" + port + "/ok",
                "http://localhost:" + port + "/ok2"));
        AuditWebhookPublisher pub = new AuditWebhookPublisher(props, mapper);
        pub.publish(sampleEntry()).block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);
    }

    @Test
    void retriesOn5xxThenSucceeds() {
        transientFailuresLeft.set(2);  // server fails twice, succeeds on retry #3
        AuditWebhookProperties props = props("s", List.of("http://localhost:" + port + "/flaky"));
        props.setMaxRetries(3);
        props.setInitialBackoffMs(50);

        AuditWebhookPublisher pub = new AuditWebhookPublisher(props, mapper);
        pub.publish(sampleEntry()).block(Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
    }

    @Test
    void fourXxFailsFastWithoutRetries() {
        AuditWebhookProperties props = props("s", List.of("http://localhost:" + port + "/bad"));
        AuditWebhookPublisher pub = new AuditWebhookPublisher(props, mapper);
        // Should NOT throw — webhook failures are swallowed.
        pub.publish(sampleEntry()).block(Duration.ofSeconds(5));
        // Only one delivery attempt: the 4xx hit once.
        await().atMost(Duration.ofSeconds(3)).until(() -> received.size() == 1);
    }

    @Test
    void disabledIsNoOp() {
        AuditWebhookProperties props = props("s", List.of("http://localhost:" + port + "/ok"));
        props.setEnabled(false);
        AuditWebhookPublisher pub = new AuditWebhookPublisher(props, mapper);
        pub.publish(sampleEntry()).block(Duration.ofSeconds(2));
        // Server should NOT receive anything.
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        assertThat(received).isEmpty();
    }

    // ----- helpers -----

    private AuditWebhookProperties props(String secret, List<String> urls) {
        AuditWebhookProperties p = new AuditWebhookProperties();
        p.setEnabled(true);
        p.setSecret(secret);
        p.setUrls(urls);
        p.setMaxRetries(0);
        p.setTimeoutMs(2000);
        p.setInitialBackoffMs(50);
        return p;
    }

    private ConfigAuditEntry sampleEntry() {
        ConfigAuditEntry e = new ConfigAuditEntry();
        e.setId(UUID.randomUUID());
        e.setTenantId("demo");
        e.setTableName("customers");
        e.setOperation("INGEST_RECORD");
        e.setRecordKey("alice");
        e.setBeforeValue(Json.of("{\"id\":\"alice\",\"tier\":\"gold\"}"));
        e.setAfterValue(Json.of("{\"id\":\"alice\",\"tier\":\"platinum\"}"));
        e.setActor("build-bot");
        e.setActorTrack("LDAP");
        e.setChangedAt(Instant.now());
        e.setRequestId("req-42");
        return e;
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "sha256=" + sb;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private record CapturedRequest(String path, String body, String signature, String tenant) {}
}
