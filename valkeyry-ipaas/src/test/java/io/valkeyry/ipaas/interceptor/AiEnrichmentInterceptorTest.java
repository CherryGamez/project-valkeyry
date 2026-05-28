package io.valkeyry.ipaas.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the engine-agnostic {@link AiEnrichmentInterceptor}.
 *
 * <p>We force engine=BRIDGE so the test can drive the {@link MockWebServer}
 * deterministically. The SPRING_AI engine has its own test class with mocked
 * ChatClient stubs (see {@code SpringAiEnrichmentEngineTest}).
 */
class AiEnrichmentInterceptorTest {

    private MockWebServer server;
    private AiEnrichmentInterceptor interceptor;
    private IpaasProperties props;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        props = new IpaasProperties();
        props.getAi().setEnabled(true);
        props.getAi().setEngine("BRIDGE");
        props.getAi().setBridgeUrl(server.url("/").toString());

        ObjectMapper om = new ObjectMapper();
        BridgeAiEnrichmentEngine bridge = new BridgeAiEnrichmentEngine(props, WebClient.builder(), om);
        interceptor = new AiEnrichmentInterceptor(props, om, List.of(bridge));
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void disabled_passesThroughUnchanged() {
        props.getAi().setEnabled(false);
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"a\":1}".getBytes()).headers(new HashMap<>()).build();
        StepVerifier.create(interceptor.intercept(msg))
                .expectNextMatches(out -> new String(out.getPayload(), StandardCharsets.UTF_8).equals("{\"a\":1}"))
                .verifyComplete();
    }

    @Test
    void enrich_replacesPayloadAndAddsHeaders() {
        props.getAi().setMode("ENRICH");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"enriched\":{\"a\":1,\"ai_priority\":\"HIGH\"},\"provider\":\"openai\",\"model\":\"gpt-4.1-mini\"}"));
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"a\":1}".getBytes()).headers(new HashMap<>()).build();
        StepVerifier.create(interceptor.intercept(msg))
                .assertNext(out -> {
                    String body = new String(out.getPayload(), StandardCharsets.UTF_8);
                    assertThat(body).contains("ai_priority").contains("HIGH");
                    assertThat(out.getHeaders()).containsEntry("x-ai-enriched", "true");
                    assertThat(out.getHeaders()).containsEntry("x-ai-engine", "BRIDGE");
                })
                .verifyComplete();
    }

    @Test
    void decide_injectsDecisionHeader() {
        props.getAi().setMode("DECIDE");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"decision\":\"secondary\",\"confidence\":0.87}"));
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"orderTotal\":4500}".getBytes()).headers(new HashMap<>()).build();
        StepVerifier.create(interceptor.intercept(msg))
                .assertNext(out -> {
                    assertThat(out.getHeaders()).containsEntry("x-ai-decision", "secondary");
                    assertThat(out.getHeaders()).containsEntry("x-ai-confidence", "0.87");
                    assertThat(out.getHeaders()).containsEntry("x-ai-engine", "BRIDGE");
                })
                .verifyComplete();
    }

    @Test
    void bridgeError_passesThroughOriginal() {
        props.getAi().setMode("ENRICH");
        server.enqueue(new MockResponse().setResponseCode(500));
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"a\":1}".getBytes()).headers(new HashMap<>()).build();
        StepVerifier.create(interceptor.intercept(msg))
                .expectNextMatches(out -> new String(out.getPayload()).equals("{\"a\":1}"))
                .verifyComplete();
    }

    @Test
    void nonJsonPayload_skipsAi() {
        props.getAi().setMode("ENRICH");
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("plain-text-blob".getBytes()).headers(new HashMap<>()).build();
        StepVerifier.create(interceptor.intercept(msg))
                .expectNextMatches(out -> new String(out.getPayload()).equals("plain-text-blob"))
                .verifyComplete();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void headerOverridesProviderAndModel() {
        props.getAi().setMode("ENRICH");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"enriched\":{\"k\":\"v\"}}"));
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("x-ai-provider", "anthropic");
        hdrs.put("x-ai-model", "claude-sonnet-4-5-20250929");
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"a\":1}".getBytes()).headers(hdrs).build();
        StepVerifier.create(interceptor.intercept(msg))
                .assertNext(out -> assertThat(out.getHeaders()).containsEntry("x-ai-provider", "anthropic"))
                .verifyComplete();
    }

    @Test
    void unknownEngineHeader_passesThrough() {
        props.getAi().setMode("ENRICH");
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("x-ai-engine", "DOES_NOT_EXIST");
        var msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"a\":1}".getBytes()).headers(hdrs).build();
        StepVerifier.create(interceptor.intercept(msg))
                .expectNextMatches(out -> new String(out.getPayload()).equals("{\"a\":1}"))
                .verifyComplete();
        assertThat(server.getRequestCount()).isZero();
    }
}
