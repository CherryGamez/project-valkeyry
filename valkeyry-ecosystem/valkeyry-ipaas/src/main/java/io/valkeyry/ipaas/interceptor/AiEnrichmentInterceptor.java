package io.valkeyry.ipaas.interceptor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes message-pipeline AI calls to a pluggable {@link AiEnrichmentEngine}.
 *
 * <p>Two engines are wired by default:
 * <ul>
 *   <li><b>SPRING_AI</b> — in-process Spring AI ChatClient (Ollama default; OpenAI / Anthropic opt-in).</li>
 *   <li><b>BRIDGE</b> — legacy Python ai-bridge sidecar (Emergent Universal LLM key).</li>
 * </ul>
 *
 * <p>Engine selection per {@code ipaas.ai.engine} (default: <code>SPRING_AI</code>),
 * or per-message via header <code>x-ai-engine</code>.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>ENRICH</b> — replace the JSON payload with the LLM-augmented version.</li>
 *   <li><b>DECIDE</b> — inject {@code x-ai-decision} header so the downstream
 *       routing / topology executor can branch on it.</li>
 *   <li><b>OFF</b> — pass-through.</li>
 * </ul>
 *
 * <p>Per-message override headers: {@code x-ai-engine}, {@code x-ai-provider},
 * {@code x-ai-model}, {@code x-ai-options}, {@code x-ai-mode}.
 *
 * <p>Failures are non-blocking and never break the main Flux — the original
 * message is returned untouched on error, timeout, or unparseable LLM reply.
 */
@Slf4j
@Component
public class AiEnrichmentInterceptor implements MessageInterceptor {

    private final IpaasProperties props;
    private final ObjectMapper objectMapper;
    private final Map<String, AiEnrichmentEngine> engines;

    public AiEnrichmentInterceptor(IpaasProperties props,
                                   ObjectMapper objectMapper,
                                   List<AiEnrichmentEngine> engineBeans) {
        this.props = props;
        this.objectMapper = objectMapper;
        Map<String, AiEnrichmentEngine> m = new HashMap<>();
        for (AiEnrichmentEngine e : engineBeans) m.put(e.id(), e);
        this.engines = Map.copyOf(m);
        log.info("AiEnrichmentInterceptor wired with engines: {}", engines.keySet());
    }

    @Override
    public Mono<ReactiveBrokerClient.IncomingMessage> intercept(ReactiveBrokerClient.IncomingMessage msg) {
        IpaasProperties.Ai ai = props.getAi();
        if (!ai.isEnabled()) return Mono.just(msg);

        String mode = headerOrDefault(msg, "x-ai-mode", ai.getMode()).toUpperCase();
        if ("OFF".equals(mode)) return Mono.just(msg);

        if (msg.getPayload() == null || msg.getPayload().length > ai.getMaxPayloadBytes()) {
            log.debug("AI interceptor skipped (payload > {} bytes)", ai.getMaxPayloadBytes());
            return Mono.just(msg);
        }
        Map<String, Object> payloadJson = parseJson(msg.getPayload());
        if (payloadJson == null) return Mono.just(msg);

        String engineId = headerOrDefault(msg, "x-ai-engine", ai.getEngine()).toUpperCase();
        AiEnrichmentEngine engine = engines.get(engineId);
        if (engine == null) {
            log.warn("Unknown AI engine '{}'. Available: {}. Passing through.", engineId, engines.keySet());
            return Mono.just(msg);
        }

        String provider = headerOrDefault(msg, "x-ai-provider",
                "SPRING_AI".equals(engineId) ? ai.getSpring().getProvider() : ai.getProvider());
        String model    = headerOrDefault(msg, "x-ai-model",
                "SPRING_AI".equals(engineId) ? resolveSpringModel(ai, provider) : ai.getModel());

        return switch (mode) {
            case "ENRICH" -> doEnrich(msg, payloadJson, engine, engineId, provider, model);
            case "DECIDE" -> doDecide(msg, payloadJson, engine, engineId, provider, model);
            default -> {
                log.warn("Unknown ipaas.ai.mode '{}' — passing through.", mode);
                yield Mono.just(msg);
            }
        };
    }

    private String resolveSpringModel(IpaasProperties.Ai ai, String provider) {
        if (provider == null) return ai.getSpring().getOllamaModel();
        return switch (provider.toLowerCase()) {
            case "openai"    -> ai.getSpring().getOpenaiModel();
            case "anthropic" -> ai.getSpring().getAnthropicModel();
            default          -> ai.getSpring().getOllamaModel();
        };
    }

    private Mono<ReactiveBrokerClient.IncomingMessage> doEnrich(
            ReactiveBrokerClient.IncomingMessage msg, Map<String, Object> payloadJson,
            AiEnrichmentEngine engine, String engineId, String provider, String model) {

        return engine.enrich(payloadJson, provider, model, props.getAi().getSystemPrompt())
                .map(enriched -> {
                    try {
                        msg.setPayload(objectMapper.writeValueAsBytes(enriched));
                        Map<String, String> hdrs = new HashMap<>(
                                msg.getHeaders() == null ? Map.of() : msg.getHeaders());
                        hdrs.put("x-ai-enriched", "true");
                        hdrs.put("x-ai-engine", engineId);
                        hdrs.put("x-ai-provider", provider);
                        hdrs.put("x-ai-model", model);
                        msg.setHeaders(hdrs);
                    } catch (Exception e) {
                        log.warn("AI enrich serialize failed, passing original: {}", e.toString());
                    }
                    return msg;
                })
                .defaultIfEmpty(msg);
    }

    private Mono<ReactiveBrokerClient.IncomingMessage> doDecide(
            ReactiveBrokerClient.IncomingMessage msg, Map<String, Object> payloadJson,
            AiEnrichmentEngine engine, String engineId, String provider, String model) {

        String optsHeader = headerOrDefault(msg, "x-ai-options", props.getAi().getRoutingOptions());
        List<String> options = List.of(optsHeader.split(","));

        return engine.decide(payloadJson, options, provider, model, props.getAi().getSystemPrompt())
                .map(decision -> {
                    Map<String, String> hdrs = new HashMap<>(
                            msg.getHeaders() == null ? Map.of() : msg.getHeaders());
                    hdrs.put("x-ai-decision", decision.decision());
                    if (decision.confidence() != null)
                        hdrs.put("x-ai-confidence", String.valueOf(decision.confidence()));
                    hdrs.put("x-ai-engine", engineId);
                    msg.setHeaders(hdrs);
                    return msg;
                })
                .defaultIfEmpty(msg);
    }

    private String headerOrDefault(ReactiveBrokerClient.IncomingMessage msg, String key, String fallback) {
        if (msg.getHeaders() == null) return fallback;
        String v = msg.getHeaders().get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private Map<String, Object> parseJson(byte[] payload) {
        try {
            return objectMapper.readValue(new String(payload, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
