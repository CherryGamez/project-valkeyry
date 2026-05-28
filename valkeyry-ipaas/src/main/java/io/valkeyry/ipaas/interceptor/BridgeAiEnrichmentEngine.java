package io.valkeyry.ipaas.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine wrapping the legacy Python ai-bridge sidecar. Calls
 * <code>POST {bridge-url}/enrich</code> or <code>POST {bridge-url}/decide</code>
 * and returns the parsed JSON. All errors are swallowed and converted to
 * {@link Mono#empty()} so the interceptor can fall through.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BridgeAiEnrichmentEngine implements AiEnrichmentEngine {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final IpaasProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private volatile WebClient client;

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            c = webClientBuilder.baseUrl(props.getAi().getBridgeUrl()).build();
            client = c;
        }
        return c;
    }

    @Override public String id() { return "BRIDGE"; }

    @Override
    public Mono<Map<String, Object>> enrich(Map<String, Object> payload,
                                            String provider, String model, String systemPrompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        body.put("provider", provider);
        body.put("model", model);
        body.put("system_prompt", systemPrompt);

        return client().post().uri("/enrich").bodyValue(body).retrieve()
                .bodyToMono(JSON_MAP)
                .timeout(Duration.ofSeconds(props.getAi().getTimeoutSeconds()))
                .map(resp -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> enriched = (Map<String, Object>) resp.get("enriched");
                    return enriched;
                })
                .onErrorResume(ex -> {
                    log.warn("BRIDGE enrich failed: {}", ex.toString());
                    return Mono.empty();
                })
                .flatMap(m -> m == null ? Mono.empty() : Mono.just(m));
    }

    @Override
    public Mono<DecisionResult> decide(Map<String, Object> payload, List<String> options,
                                       String provider, String model, String systemPrompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        body.put("options", options);
        body.put("provider", provider);
        body.put("model", model);
        body.put("system_prompt", systemPrompt);

        return client().post().uri("/decide").bodyValue(body).retrieve()
                .bodyToMono(JSON_MAP)
                .timeout(Duration.ofSeconds(props.getAi().getTimeoutSeconds()))
                .map(resp -> {
                    Object decision = resp.get("decision");
                    if (decision == null) return null;
                    Double conf = null;
                    Object c = resp.get("confidence");
                    if (c instanceof Number n) conf = n.doubleValue();
                    return new DecisionResult(decision.toString(), conf);
                })
                .onErrorResume(ex -> {
                    log.warn("BRIDGE decide failed: {}", ex.toString());
                    return Mono.empty();
                })
                .flatMap(d -> d == null ? Mono.empty() : Mono.just(d));
    }

    // ObjectMapper retained to keep DI signature consistent across both engines.
    @SuppressWarnings("unused")
    private ObjectMapper mapper() { return objectMapper; }
}
