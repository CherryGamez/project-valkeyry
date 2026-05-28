package io.valkeyry.ipaas.copilot;

import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.config.SpringAiConfig.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a multi-turn chat against the configured Spring AI ChatModel with
 * the {@link CopilotToolset} attached. Maintains a per-session memory window
 * (in-memory; bounded by {@code ipaas.ai.copilot.memory-window}).
 *
 * <p>Streaming chat uses Spring AI's {@code .stream()} adapter so the React UI
 * sees tokens in real time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotChatService {

    private final ChatModelRegistry registry;
    private final CopilotToolset toolset;
    private final ChatMemory memory;
    private final IpaasProperties props;

    /** Session id -> active provider override (sticky for the session). */
    private final ConcurrentHashMap<String, String> sessionProviders = new ConcurrentHashMap<>();

    /**
     * Streaming chat. Returns a {@link Flux} of token-chunks (Server-Sent Event payloads).
     * Persists user + assistant messages into the chat memory at end-of-stream.
     */
    public Flux<String> stream(String sessionId, String userMessage, String providerOverride) {
        if (!props.getAi().getCopilot().isEnabled()) {
            return Flux.just("Operator Copilot is disabled (ipaas.ai.copilot.enabled=false).");
        }
        String provider = pickProvider(sessionId, providerOverride);
        ChatModel model = registry.resolveOrDefault(provider);
        if (model == null) {
            return Flux.just("No ChatModel available. Start Ollama (ollama serve) or set OPENAI_API_KEY / ANTHROPIC_API_KEY.");
        }

        ChatClient client = ChatClient.builder(model)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(toolset)
                .build();

        // Replay memory history into the prompt so the model has context across turns.
        List<Message> history = memory.get(sessionId);

        StringBuilder collected = new StringBuilder();
        return client.prompt()
                .messages(history)
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(collected::append)
                .doOnComplete(() -> {
                    // Persist this turn into memory; we add UserMessage first, then assistant.
                    memory.add(sessionId, List.of(
                            new UserMessage(userMessage),
                            new AssistantMessage(collected.toString())
                    ));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Non-streaming chat (single-shot) — used for unit tests + simpler clients. */
    public Mono<String> chat(String sessionId, String userMessage, String providerOverride) {
        if (!props.getAi().getCopilot().isEnabled()) {
            return Mono.just("Operator Copilot is disabled.");
        }
        return stream(sessionId, userMessage, providerOverride)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString);
    }

    public List<Map<String, Object>> tools() {
        // Reflective discovery would be heavier; keep this hard-coded mirror in sync with CopilotToolset.
        return List.of(
                Map.of("name", "listQueues",       "category", "read",  "description", "List all queue assets for a tenant/project."),
                Map.of("name", "listTopologies",   "category", "read",  "description", "List declared topologies for a tenant/project."),
                Map.of("name", "peekDlq",          "category", "read",  "description", "Non-destructively peek N messages in a DLQ."),
                Map.of("name", "queueDepth",       "category", "read",  "description", "Current depth of a Rabbit queue."),
                Map.of("name", "metricsSnapshot",  "category", "read",  "description", "All queues + depths for a workspace."),
                Map.of("name", "aiProviders",      "category", "read",  "description", "List wired LLM providers."),
                Map.of("name", "retryDlqMessages", "category", "write", "description", "Bulk-retry DLQ messages (confirm=true required)."),
                Map.of("name", "declareQueue",     "category", "write", "description", "Service-catalog declare a queue (confirm=true required)."),
                Map.of("name", "publishMessage",   "category", "write", "description", "Publish a payload (confirm=true required).")
        );
    }

    public List<Map<String, String>> history(String sessionId) {
        List<Message> msgs = memory.get(sessionId);
        List<Map<String, String>> out = new ArrayList<>();
        for (Message m : msgs) {
            String role = m.getMessageType() == MessageType.USER ? "user"
                    : m.getMessageType() == MessageType.ASSISTANT ? "assistant"
                    : m.getMessageType().getValue();
            out.add(Map.of("role", role, "content", m.getText()));
        }
        return out;
    }

    public void reset(String sessionId) {
        memory.clear(sessionId);
        sessionProviders.remove(sessionId);
    }

    public List<String> availableProviders() {
        return new ArrayList<>(registry.all().keySet());
    }

    public String pickProvider(String sessionId, String override) {
        if (override != null && !override.isBlank() && registry.has(override)) {
            sessionProviders.put(sessionId, override);
            return override;
        }
        String sticky = sessionProviders.get(sessionId);
        if (sticky != null && registry.has(sticky)) return sticky;
        return props.getAi().getCopilot().getDefaultProvider();
    }

    private static final String SYSTEM_PROMPT = """
            You are the Valkeyry iPaaS Operator Copilot. You assist platform engineers operating a
            multi-tenant reactive messaging middleware (RabbitMQ / Kafka / ActiveMQ).

            CAPABILITIES
            • Inspect the live system via READ tools (listQueues, listTopologies, peekDlq, queueDepth,
              metricsSnapshot, aiProviders).
            • Take WRITE actions via tools that require confirm=true (retryDlqMessages, declareQueue,
              publishMessage). On a first write attempt, the tool will return a confirmation prompt;
              SURFACE it to the human verbatim and wait for explicit approval before re-calling with
              confirm=true.

            STYLE
            • Concise, technical, no marketing fluff.
            • When citing data from a tool, format tables in Markdown.
            • Always show tool name + key parameters you used so the engineer can reproduce.
            • If you don't know the tenant/project, ASK before guessing. Common slugs in dev are
              acme-corp / payments-prod and globex-eu / billing-dev.
            • If a destructive action is requested, ALWAYS explain blast radius first, then ask.
            """;
}
