package io.valkeyry.ipaas.copilot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * HTTP entrypoint for the Operator Copilot.
 *
 * <ul>
 *   <li><b>POST /api/v1/copilot/chat</b> — non-streaming single-shot reply.</li>
 *   <li><b>POST /api/v1/copilot/stream</b> — Server-Sent Events token stream.</li>
 *   <li><b>GET  /api/v1/copilot/tools</b> — tool catalog.</li>
 *   <li><b>GET  /api/v1/copilot/providers</b> — active LLM providers + default.</li>
 *   <li><b>GET  /api/v1/copilot/session/{id}/history</b> — chat memory dump.</li>
 *   <li><b>POST /api/v1/copilot/session/{id}/reset</b> — clears memory for a session.</li>
 * </ul>
 */
@Tag(name = "Operator Copilot",
     description = "Spring AI ChatClient agent that can inspect AND (with explicit confirmation) operate " +
             "the platform. Default provider is local Ollama; OpenAI / Anthropic activate via api-key.")
@RestController
@RequestMapping("/api/v1/copilot")
@RequiredArgsConstructor
public class OperatorCopilotController {

    private final CopilotChatService service;
    private final IpaasProperties props;

    private ResponseEntity<?> disabledIfOff() {
        if (!props.getAi().getCopilot().isEnabled()) {
            return ResponseEntity.status(404).build();
        }
        return null;
    }

    @Operation(summary = "Single-shot chat. Returns the assistant's full reply when complete.")
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> chat(@RequestBody ChatRequest body) {
        if (disabledIfOff() != null) {
            return Mono.just(ResponseEntity.status(404).body(Map.of("error", "copilot disabled")));
        }
        String sid = body.getSessionId() == null ? "default" : body.getSessionId();
        return service.chat(sid, body.getMessage(), body.getProvider())
                .map(reply -> ResponseEntity.ok(Map.of(
                        "sessionId", sid,
                        "reply", reply,
                        "provider", service.pickProvider(sid, body.getProvider())
                )));
    }

    @Operation(summary = "SSE token stream. Each event carries a content chunk; " +
            "the stream completes when the model is done.")
    @PostMapping(value = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest body) {
        if (!props.getAi().getCopilot().isEnabled()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("copilot disabled").build());
        }
        String sid = body.getSessionId() == null ? "default" : body.getSessionId();
        return service.stream(sid, body.getMessage(), body.getProvider())
                .map(chunk -> ServerSentEvent.<String>builder().event("chunk").data(chunk).build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data("").build()));
    }

    @Operation(summary = "Catalog of read/write tools available to the model.")
    @GetMapping("/tools")
    public ResponseEntity<List<Map<String, Object>>> tools() {
        if (!props.getAi().getCopilot().isEnabled()) return ResponseEntity.status(404).build();
        return ResponseEntity.ok(service.tools());
    }

    @Operation(summary = "Active LLM providers + default.")
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> providers() {
        if (!props.getAi().getCopilot().isEnabled()) return ResponseEntity.status(404).build();
        return ResponseEntity.ok(Map.of(
                "available", service.availableProviders(),
                "default", props.getAi().getCopilot().getDefaultProvider(),
                "confirmDestructive", props.getAi().getCopilot().isConfirmDestructive()
        ));
    }

    @Operation(summary = "Dump the memory window for a session.")
    @GetMapping("/session/{sessionId}/history")
    public ResponseEntity<List<Map<String, String>>> history(@PathVariable String sessionId) {
        if (!props.getAi().getCopilot().isEnabled()) return ResponseEntity.status(404).build();
        return ResponseEntity.ok(service.history(sessionId));
    }

    @Operation(summary = "Clear memory for a session (start a fresh conversation).")
    @PostMapping("/session/{sessionId}/reset")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String sessionId) {
        if (!props.getAi().getCopilot().isEnabled()) return ResponseEntity.status(404).build();
        service.reset(sessionId);
        return ResponseEntity.ok(Map.of("cleared", true, "sessionId", sessionId));
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ChatRequest {
        private String sessionId;     // optional; defaults to "default"
        private String message;       // required
        private String provider;      // optional override (ollama|openai|anthropic)
    }
}
