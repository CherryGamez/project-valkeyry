package io.valkeyry.ipaas.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Multi-provider Spring AI configuration.
 *
 * <p>Each Spring AI starter (Ollama / OpenAI / Anthropic) registers its own
 * {@link ChatModel} bean when its corresponding api-key is present (Ollama is
 * always present — no key needed). This config builds a {@link ChatModelRegistry}
 * that resolves a model by its short name (e.g. "ollama", "openai", "anthropic")
 * with a deterministic fallback chain ending at Ollama, so the Copilot and the
 * AI interceptor keep working even if no third-party key is configured.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SpringAiConfig {

    private final ObjectProvider<ChatModel> chatModelProvider;

    @Bean
    public ChatModelRegistry chatModelRegistry() {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        chatModelProvider.stream().forEach(m -> {
            String name = providerOf(m.getClass().getSimpleName());
            // Earliest-registered wins on collisions — Ollama is intentionally listed last.
            models.putIfAbsent(name, m);
            log.info("Spring AI ChatModel registered: provider={} impl={}", name, m.getClass().getSimpleName());
        });
        if (models.isEmpty()) {
            log.warn("No Spring AI ChatModel beans available — Copilot and SpringAiEnrichmentEngine will be inert.");
        }
        return new ChatModelRegistry(models);
    }

    @Bean
    public ChatClient.Builder defaultChatClientBuilder(ChatModelRegistry registry, IpaasProperties props) {
        ChatModel pick = registry.resolveOrDefault(props.getAi().getSpring().getProvider());
        if (pick == null) {
            // Provide an inert builder so app boots; chat calls will fail fast.
            log.warn("No ChatModel available; ChatClient.Builder will throw on use.");
            return ChatClient.builder(new NullChatModel());
        }
        return ChatClient.builder(pick);
    }

    /**
     * Conversation memory used by the Operator Copilot. Per-session windowed memory,
     * default window 20 messages.
     */
    @Bean
    public ChatMemory copilotChatMemory(IpaasProperties props) {
        int window = Math.max(2, props.getAi().getCopilot().getMemoryWindow());
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(window)
                .build();
    }

    private static String providerOf(String simpleName) {
        String n = simpleName.toLowerCase();
        if (n.contains("ollama"))    return "ollama";
        if (n.contains("anthropic")) return "anthropic";
        if (n.contains("openai"))    return "openai";
        if (n.contains("vertex") || n.contains("gemini")) return "gemini";
        return n;
    }

    /**
     * Registry that exposes available providers + a {@code resolveOrDefault} chain.
     * Lookup order: requested provider → ollama → first registered.
     */
    public static final class ChatModelRegistry {
        private final Map<String, ChatModel> byProvider;
        public ChatModelRegistry(Map<String, ChatModel> byProvider) { this.byProvider = byProvider; }
        public Map<String, ChatModel> all() { return byProvider; }
        public boolean has(String provider) { return provider != null && byProvider.containsKey(provider); }
        public Optional<ChatModel> get(String provider) {
            return provider == null ? Optional.empty() : Optional.ofNullable(byProvider.get(provider));
        }
        public ChatModel resolveOrDefault(String preferred) {
            ChatModel m = byProvider.get(preferred);
            if (m != null) return m;
            m = byProvider.get("ollama");
            if (m != null) return m;
            return byProvider.values().stream().findFirst().orElse(null);
        }
    }

    /** Inert ChatModel that throws on use — only instantiated when zero providers are wired. */
    private static final class NullChatModel implements ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new IllegalStateException(
                    "No Spring AI ChatModel is configured. Start Ollama or set OPENAI_API_KEY/ANTHROPIC_API_KEY.");
        }
    }
}
