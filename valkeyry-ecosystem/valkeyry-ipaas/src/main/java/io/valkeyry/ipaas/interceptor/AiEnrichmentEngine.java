package io.valkeyry.ipaas.interceptor;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Pluggable engine that performs the actual LLM call for the
 * {@link AiEnrichmentInterceptor}. Implementations:
 *
 * <ul>
 *   <li>{@link BridgeAiEnrichmentEngine} — legacy Python ai-bridge sidecar
 *       (Emergent Universal LLM Key path).</li>
 *   <li>{@link SpringAiEnrichmentEngine} — in-process Spring AI ChatClient
 *       (Ollama default; OpenAI / Anthropic / Gemini opt-in).</li>
 * </ul>
 *
 * <p>Both implementations MUST NEVER throw — failures are converted into
 * {@link Mono#empty()} so the interceptor can pass the original message through
 * untouched.
 */
public interface AiEnrichmentEngine {

    /** Stable identifier used for header `x-ai-engine` and ChatModelRegistry lookup. */
    String id();

    /**
     * Returns an enriched JSON payload, or {@link Mono#empty()} on failure.
     * The interceptor will replace the message payload only on a non-empty result.
     */
    Mono<Map<String, Object>> enrich(Map<String, Object> payload,
                                     String provider, String model, String systemPrompt);

    /**
     * Returns a routing decision from {@code options}, e.g. "primary" / "secondary".
     * Empty Mono signals no decision (interceptor leaves headers untouched).
     */
    Mono<DecisionResult> decide(Map<String, Object> payload, List<String> options,
                                String provider, String model, String systemPrompt);

    /** Carrier for DECIDE-mode output. */
    record DecisionResult(String decision, Double confidence) {}
}
