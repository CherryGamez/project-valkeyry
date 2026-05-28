package io.valkeyry.ipaas.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicTransformationRoutingEngineTest {

    private DynamicTransformationRoutingEngine engine;
    private MockWebServer mockWebServer;

    @BeforeEach
    void setup() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        WebClient webClient = WebClient.builder().build();
        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.ofDefaults();
        cbReg.circuitBreaker("outboundWebhook");
        RetryRegistry retryReg = RetryRegistry.ofDefaults();
        retryReg.retry("outboundWebhook");
        engine = new DynamicTransformationRoutingEngine(webClient, cbReg, retryReg, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception { mockWebServer.shutdown(); }

    @Test
    void transform_mapsJsonPathFieldsCorrectly() {
        String raw = "{\"user\":{\"id\":42,\"email\":\"a@b.com\"},\"ts\":1700000000}";
        Map<String, String> mappings = Map.of(
                "userId", "$.user.id",
                "userEmail", "$.user.email",
                "timestamp", "$.ts");
        Map<String, Object> result = engine.transform(raw, mappings);
        assertThat(result).containsEntry("userId", 42)
                .containsEntry("userEmail", "a@b.com")
                .containsEntry("timestamp", 1700000000);
    }

    @Test
    void transform_missingFieldYieldsNull() {
        String raw = "{\"a\":1}";
        Map<String, Object> result = engine.transform(raw, Map.of("missing", "$.does.not.exist"));
        assertThat(result).containsEntry("missing", null);
    }

    @Test
    void dispatch_returns200OnSuccess() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        String url = mockWebServer.url("/hook").toString();
        StepVerifier.create(engine.dispatch(url, Map.of("k", "v"), Map.of()))
                .expectNext(200)
                .verifyComplete();
    }

    @Test
    void dispatch_returns500OnDownstreamError() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        String url = mockWebServer.url("/hook").toString();
        // Retry will replay; final emission is the last 500
        StepVerifier.create(engine.dispatch(url, Map.of("k", "v"), Map.of()))
                .expectNext(500)
                .verifyComplete();
    }
}
