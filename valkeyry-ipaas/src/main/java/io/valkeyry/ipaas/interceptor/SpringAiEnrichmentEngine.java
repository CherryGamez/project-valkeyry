package io.valkeyry.ipaas.interceptor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.config.SpringAiConfig.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * In-process Spring AI ChatClient engine. Resolves the {@link ChatModel} from the
 * {@link ChatModelRegistry} via the provider name (per-call override possible),
 * builds a prompt that constrains the LLM to return strict JSON, and parses the
 * response back into a {@code Map<String,Object>}.
 *
 * <p>All errors — model unavailable, parse failure, timeout — are converted to
 * {@link Mono#empty()} so the interceptor falls through to the original payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiEnrichmentEngine implements AiEnrichmentEngine {

    private static final TypeReference<Map<String, Object>> JSON_MAP_REF = new TypeReference<>() {};

    private final ChatModelRegistry registry;
    private final IpaasProperties props;
    private final ObjectMapper objectMapper;

    @Override public String id() { return "SPRING_AI"; }

    @Override
    public Mono<Map<String, Object>> enrich(Map<String, Object> payload,
                                            String provider, String model, String systemPrompt) {
        return callJson(provider, model, systemPrompt,
                "Enrich this JSON payload. Return ONLY a single valid JSON object " +
                        "with the same keys plus any additional inferred fields. " +
                        "No prose, no markdown fences.\n\nPayload:\n" + serialize(payload));
    }

    @Override
    public Mono<DecisionResult> decide(Map<String, Object> payload, List<String> options,
                                       String provider, String model, String systemPrompt) {
        String optsCsv = String.join(",", options);
        String userPrompt = "Pick exactly ONE routing option from this list: [" + optsCsv + "].\n" +
                "Return ONLY a JSON object: {\"decision\":\"<one option>\",\"confidence\":<0..1>}.\n" +
                "No prose.\n\nPayload:\n" + serialize(payload);
        return callJson(provider, model, systemPrompt, userPrompt)
                .flatMap(json -> {
                    Object dec = json.get("decision");
                    if (dec == null) return Mono.empty();
                    Double conf = null;
                    Object c = json.get("confidence");
                    if (c instanceof Number n) conf = n.doubleValue();
                    return Mono.just(new DecisionResult(dec.toString(), conf));
                });
    }

    private Mono<Map<String, Object>> callJson(String provider, String model,
                                               String systemPrompt, String userPrompt) {
        ChatModel chosen = registry.resolveOrDefault(provider);
        if (chosen == null) {
            log.warn("SPRING_AI engine: no ChatModel available (asked for '{}').", provider);
            return Mono.empty();
        }
        ChatClient client = ChatClient.builder(chosen).build();
        return Mono.fromCallable(() -> {
                    String reply = client.prompt()
                            .system(systemPrompt == null || systemPrompt.isBlank()
                                    ? "You are a JSON-only assistant. Return only valid JSON."
                                    : systemPrompt)
                            .user(userPrompt)
                            // model name is taken from the auto-config; the per-call override
                            // happens upstream via ChatModel selection (per-provider model).
                            .call().content();
                    return parseJson(reply);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(props.getAi().getTimeoutSeconds()))
                .onErrorResume(ex -> {
                    log.warn("SPRING_AI engine call failed (provider={}): {}", provider, ex.toString());
                    return Mono.empty();
                })
                .flatMap(m -> m == null ? Mono.empty() : Mono.just(m));
    }

    /** Extract the first balanced {...} block from a possibly noisy LLM reply. */
    Map<String, Object> parseJson(String reply) {
        if (reply == null) return null;
        String trimmed = reply.trim();
        // Strip ```json fences if present.
        if (trimmed.startsWith("```")) {
            int firstBrace = trimmed.indexOf('{');
            int lastBrace  = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                trimmed = trimmed.substring(firstBrace, lastBrace + 1);
            }
        }
        try {
            return objectMapper.readValue(trimmed, JSON_MAP_REF);
        } catch (Exception e) {
            // Try a slightly more lenient extraction: first {..} balanced block.
            int start = trimmed.indexOf('{');
            int end   = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readValue(trimmed.substring(start, end + 1), JSON_MAP_REF);
                } catch (Exception ignored) { /* fall through */ }
            }
            return null;
        }
    }

    private String serialize(Map<String, Object> m) {
        try { return objectMapper.writeValueAsString(m); }
        catch (Exception e) { return String.valueOf(m); }
    }
}
