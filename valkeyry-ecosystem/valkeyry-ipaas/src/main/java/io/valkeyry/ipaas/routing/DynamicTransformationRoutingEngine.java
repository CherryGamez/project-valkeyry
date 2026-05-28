package io.valkeyry.ipaas.routing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reconstructs JSON payloads via JsonPath mappings, then dispatches
 * over WebClient wrapped with Resilience4j retry + circuit-breaker.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicTransformationRoutingEngine {

    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ObjectMapper objectMapper;

    /** Apply field mappings: {targetField -> jsonPath} against the raw JSON message. */
    public Map<String, Object> transform(String rawJsonPayload, Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            try { return objectMapper.readValue(rawJsonPayload, new TypeReference<>() {}); }
            catch (Exception e) { return Map.of("raw", rawJsonPayload); }
        }
        Object document = JsonPath.parse(rawJsonPayload).json();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            try {
                Object value = JsonPath.read(document, e.getValue());
                result.put(e.getKey(), value);
            } catch (Exception ex) {
                result.put(e.getKey(), null);
                log.debug("JsonPath miss for {} -> {}", e.getKey(), e.getValue());
            }
        }
        return result;
    }

    public Mono<Integer> dispatch(String targetUrl, Map<String, Object> body, Map<String, String> headers) {
        var spec = webClient.post().uri(targetUrl);
        if (headers != null) headers.forEach(spec::header);
        return spec.bodyValue(body == null ? new HashMap<>() : body)
                .exchangeToMono(resp -> Mono.just(resp.statusCode().value()))
                .transformDeferred(RetryOperator.of(retryRegistry.retry("outboundWebhook")))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreakerRegistry.circuitBreaker("outboundWebhook")));
    }
}
