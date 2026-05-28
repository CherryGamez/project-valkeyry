package io.valkeyry.ipaas.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ipaas")
public class IpaasProperties {
    private String processingMode = "QUEUE";          // QUEUE | STREAMING
    private String defaultBroker = "RABBITMQ";        // RABBITMQ | KAFKA | ACTIVEMQ
    private RateLimit rateLimit = new RateLimit();
    private Idempotency idempotency = new Idempotency();
    private Retention retention = new Retention();
    private Webhook webhook = new Webhook();
    private Ai ai = new Ai();
    private Security security = new Security();
    private Cors cors = new Cors();
    private Vault vault = new Vault();
    private Map<String, Map<String, String>> brokers = Map.of();

    @Data public static class RateLimit {
        private int capacity = 100;
        private int refillTokens = 100;
        private int refillPeriodSeconds = 60;
    }
    @Data public static class Idempotency {
        private long ttlSeconds = 86400;
    }
    @Data public static class Retention {
        private String cron = "0 0 0 * * *";
        private int defaultDays = 30;
    }
    @Data public static class Webhook {
        private String adminAlertUrl = "http://localhost:9999/admin/alerts";
    }
    @Data public static class Ai {
        /** Master switch — when false, interceptor is a no-op pass-through. */
        private boolean enabled = false;
        /**
         * Which engine performs ENRICH/DECIDE calls:
         *   - SPRING_AI : in-process Spring AI ChatClient (Ollama default, multi-provider)
         *   - BRIDGE    : legacy Python ai-bridge sidecar (Emergent Universal LLM key)
         *
         * Per-message override via header `x-ai-engine`.
         */
        private String engine = "SPRING_AI";
        /** ENRICH = mutate payload; DECIDE = inject routing header; OFF = no-op. */
        private String mode = "ENRICH";
        /** Reactive sidecar that wraps the Emergent universal LLM key. Used only when engine=BRIDGE. */
        private String bridgeUrl = "http://localhost:8090";
        /** Default provider for the legacy BRIDGE engine: openai | anthropic | gemini. */
        private String provider = "openai";
        /** Default model for the legacy BRIDGE engine. Overridable per-message header `x-ai-model`. */
        private String model = "gpt-4.1-mini";
        private String systemPrompt = "You are a JSON enrichment assistant. Return ONLY a JSON object.";
        /** Comma-separated routing options used in DECIDE mode (also overridable via header `x-ai-options`). */
        private String routingOptions = "primary,secondary,dlq";
        /** Cap on body size we send to the LLM (defensive). */
        private int maxPayloadBytes = 32_000;
        private int timeoutSeconds = 20;
        /** Spring AI ChatClient configuration (engine=SPRING_AI). */
        private SpringAi spring = new SpringAi();
        /** Operator Copilot configuration. */
        private Copilot copilot = new Copilot();
    }

    /**
     * Multi-provider Spring AI ChatClient configuration. Ollama is the default;
     * OpenAI/Anthropic/Gemini activate only when their `*.api-key` property is set.
     */
    @Data public static class SpringAi {
        /** ollama | openai | anthropic. Override per-message via `x-ai-provider` header. */
        private String provider = "ollama";
        /** Per-provider default model — overridable per-message via `x-ai-model` header. */
        private String ollamaModel    = "qwen2.5:7b";
        private String openaiModel    = "gpt-4o-mini";
        private String anthropicModel = "claude-3-5-sonnet-latest";
    }

    /**
     * Operator Copilot configuration — a chat agent that can introspect AND
     * (with explicit confirm=true) mutate the platform via Spring AI tool-calling.
     */
    @Data public static class Copilot {
        /** Master switch. When false, /api/v1/copilot/** returns 404. */
        private boolean enabled = true;
        /** Default chat model — falls back to ipaas.ai.spring.* when blank. */
        private String defaultProvider = "ollama";
        /** Conversation memory window (number of messages kept per session). */
        private int memoryWindow = 20;
        /** Maximum tool-call iterations per turn (safety cap). */
        private int maxToolCalls = 8;
        /**
         * When true, all WRITE tools (retry/deploy/declare) require an explicit
         * `confirm=true` parameter; otherwise the tool returns a "needs confirmation" stub
         * so the model has to ask the user. Recommended ON for production.
         */
        private boolean confirmDestructive = true;
    }
    @Data public static class Security {
        /** Local-dev escape hatch: when true, skips OIDC + RBAC. Never enable in production. */
        private boolean allowAnonymous = false;
    }
    @Data public static class Cors {
        private java.util.List<String> allowedOrigins = java.util.List.of("http://localhost:3000");
    }
    @Data public static class Vault {
        /** When true, schedule periodic token renewal via /auth/token/renew-self. */
        private boolean renewalEnabled = true;
        /** Renewal interval (ms). Default = 30 minutes; well under default 1h token TTL. */
        private long renewalIntervalMs = 1_800_000L;
        /** Requested increment (s) per renewal. */
        private long renewalIncrementSeconds = 3600L;
    }
}
