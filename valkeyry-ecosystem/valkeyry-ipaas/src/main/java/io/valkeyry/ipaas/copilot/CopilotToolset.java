package io.valkeyry.ipaas.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.RabbitBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.dlq.DlqManagementService;
import io.valkeyry.ipaas.publish.MultiTenantPublishService;
import io.valkeyry.ipaas.queue.QueueManagementService;
import io.valkeyry.ipaas.repository.QueueAssetRepository;
import io.valkeyry.ipaas.repository.TopologyConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operator-Copilot toolset. Each {@link Tool}-annotated method is exposed to the
 * LLM via Spring AI function calling. The model can chain these tools to answer
 * questions like "how many DLQ messages does acme-corp/payments-prod have?"
 * or perform actions like "retry every DLQ message older than an hour".
 *
 * <p><b>Safety:</b> read tools are unrestricted; <b>write tools</b> require an
 * explicit <code>confirm=true</code> argument (when
 * <code>ipaas.ai.copilot.confirm-destructive=true</code>). If <code>confirm</code>
 * is false the tool returns a stub asking the model to surface a confirmation
 * prompt to the human — nothing mutates.
 *
 * <p>All reactive backends are bridged via {@code .block(timeout)} since the
 * Spring AI tool-execution pipeline is synchronous.
 */
@Slf4j
@Component
public class CopilotToolset {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    private final QueueAssetRepository queueRepo;
    private final TopologyConfigRepository topologyRepo;
    private final DlqManagementService dlqService;
    private final QueueManagementService queueService;
    private final MultiTenantPublishService publishService;
    private final BrokerClientFactory brokerFactory;
    private final IpaasProperties props;
    private final ObjectMapper objectMapper;
    private final Counter toolInvocations;

    /** Cumulative tool-call counter — also surfaced via Micrometer ({@code valkeyry.copilot.tool.invocations}). */
    public final AtomicLong invocationCount = new AtomicLong();

    public CopilotToolset(QueueAssetRepository queueRepo,
                          TopologyConfigRepository topologyRepo,
                          DlqManagementService dlqService,
                          QueueManagementService queueService,
                          MultiTenantPublishService publishService,
                          BrokerClientFactory brokerFactory,
                          IpaasProperties props,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.queueRepo = queueRepo;
        this.topologyRepo = topologyRepo;
        this.dlqService = dlqService;
        this.queueService = queueService;
        this.publishService = publishService;
        this.brokerFactory = brokerFactory;
        this.props = props;
        this.objectMapper = objectMapper;
        this.toolInvocations = Counter.builder("valkeyry.copilot.tool.invocations")
                .description("Number of Operator-Copilot tool calls invoked by the LLM.")
                .register(meterRegistry);
    }

    /** Per-tool entry: counts both the local AtomicLong (legacy) AND the Micrometer counter. */
    private void track() {
        track();
        toolInvocations.increment();
    }

    // ============================================================
    //                          READ TOOLS
    // ============================================================

    @Tool(description = "List all queue assets (destinations) provisioned for the given tenant/project. " +
            "Returns destinationName, brokerType, processingMode, provisioningMode for each.")
    public List<Map<String, Object>> listQueues(
            @ToolParam(description = "Workspace tenant slug, e.g. acme-corp") String tenantId,
            @ToolParam(description = "Workspace project slug, e.g. payments-prod") String projectId) {
        track();
        List<Map<String, Object>> out = new ArrayList<>();
        queueRepo.findAllByTenantIdAndProjectId(tenantId, projectId)
                .map(a -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("destinationName", a.getDestinationName());
                    m.put("brokerType", a.getBrokerType());
                    m.put("processingMode", a.getProcessingMode());
                    m.put("provisioningMode", a.getProvisioningMode());
                    return m;
                })
                .doOnNext(out::add)
                .blockLast(TOOL_TIMEOUT);
        return out;
    }

    @Tool(description = "List declared topology configurations (1->N / N->1 / N->N) for a tenant/project.")
    public List<Map<String, Object>> listTopologies(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId) {
        track();
        List<Map<String, Object>> out = new ArrayList<>();
        topologyRepo.findAllByTenantIdAndProjectId(tenantId, projectId)
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", t.getTopologyName());
                    m.put("type", t.getTopologyType());
                    m.put("enabled", t.getEnabled());
                    return m;
                })
                .doOnNext(out::add)
                .blockLast(TOOL_TIMEOUT);
        return out;
    }

    @Tool(description = "Peek the first N messages of a DLQ (dead-letter queue) without consuming them.")
    public List<Map<String, Object>> peekDlq(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId,
            @ToolParam(description = "Destination name whose .dlq we want to inspect") String destination,
            @ToolParam(description = "Maximum messages to return (1..200)") int limit) {
        track();
        int n = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        dlqService.browse(tenantId, projectId, destination, n)
                .doOnNext(m -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("messageId", m.getMessageId());
                    row.put("headers", m.getHeaders());
                    String body = m.getPayload();
                    row.put("payloadPreview",
                            body == null ? "" : body.substring(0, Math.min(400, body.length())));
                    out.add(row);
                })
                .blockLast(TOOL_TIMEOUT);
        return out;
    }

    @Tool(description = "Current depth (message count) of a RabbitMQ queue. Returns -1 for non-Rabbit brokers " +
            "or when the broker is unreachable.")
    public long queueDepth(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId,
            @ToolParam(description = "Destination name (without .dlq suffix)") String destination) {
        track();
        return queueRepo.findByTenantIdAndProjectIdAndDestinationName(tenantId, projectId, destination)
                .flatMap(asset -> {
                    var broker = brokerFactory.get(asset.getBrokerType());
                    if (broker instanceof RabbitBrokerClient rbc) {
                        return rbc.queueDepth(tenantId, projectId, destination).onErrorReturn(-1L);
                    }
                    return reactor.core.publisher.Mono.just(-1L);
                })
                .blockOptional(TOOL_TIMEOUT)
                .orElse(-1L);
    }

    @Tool(description = "Snapshot of all queues + depth + DLQ depth for a tenant/project. Use this for " +
            "overview-style answers (\"how is acme-corp doing?\").")
    public List<Map<String, Object>> metricsSnapshot(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId) {
        track();
        List<Map<String, Object>> snapshot = new ArrayList<>();
        queueRepo.findAllByTenantIdAndProjectId(tenantId, projectId)
                .doOnNext(asset -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("destination", asset.getDestinationName());
                    row.put("brokerType", asset.getBrokerType());
                    long depth = -1;
                    var broker = brokerFactory.get(asset.getBrokerType());
                    if (broker instanceof RabbitBrokerClient rbc) {
                        depth = rbc.queueDepth(tenantId, projectId, asset.getDestinationName())
                                .onErrorReturn(-1L).block(TOOL_TIMEOUT);
                    }
                    row.put("depth", depth);
                    snapshot.add(row);
                })
                .blockLast(TOOL_TIMEOUT);
        return snapshot;
    }

    @Tool(description = "List every Spring AI provider currently wired (e.g. ollama, openai, anthropic) and " +
            "which one is selected as the platform default.")
    public Map<String, Object> aiProviders() {
        track();
        Map<String, Object> out = new HashMap<>();
        out.put("defaultProvider", props.getAi().getSpring().getProvider());
        out.put("defaultModel", switch (props.getAi().getSpring().getProvider()) {
            case "openai"    -> props.getAi().getSpring().getOpenaiModel();
            case "anthropic" -> props.getAi().getSpring().getAnthropicModel();
            default          -> props.getAi().getSpring().getOllamaModel();
        });
        out.put("engineDefault", props.getAi().getEngine());
        return out;
    }

    // ============================================================
    //                         WRITE TOOLS
    // ============================================================

    @Tool(description = "Bulk-retry one or more DLQ messages back onto the primary queue. " +
            "`messageIds` is a comma-separated list. WRITE operation — requires confirm=true.")
    public Map<String, Object> retryDlqMessages(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId,
            @ToolParam(description = "Destination name (parent of the .dlq)") String destination,
            @ToolParam(description = "Comma-separated list of DLQ messageIds") String messageIds,
            @ToolParam(description = "MUST be true to actually perform the retry. " +
                    "If false, the tool returns a confirmation prompt for the user.") boolean confirm) {
        track();
        if (props.getAi().getCopilot().isConfirmDestructive() && !confirm) {
            return needsConfirmation("retryDlqMessages",
                    "About to retry messages [" + messageIds + "] from " + destination + " .dlq. " +
                            "Re-call with confirm=true to proceed.");
        }
        List<DlqManagementService.BulkRetryItem> items = new ArrayList<>();
        for (String id : messageIds.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) items.add(new DlqManagementService.BulkRetryItem(trimmed, null));
        }
        var result = dlqService.bulkRetry(tenantId, projectId, destination, items).block(TOOL_TIMEOUT);
        Map<String, Object> out = new HashMap<>();
        out.put("succeeded", result == null ? 0 : result.getSucceeded());
        out.put("failed", result == null ? items.size() : result.getFailed());
        out.put("requested", items.size());
        return out;
    }

    @Tool(description = "Declare a brand-new queue/topic via the service catalog. WRITE — requires confirm=true.")
    public Map<String, Object> declareQueue(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId,
            @ToolParam(description = "Destination name") String destination,
            @ToolParam(description = "RABBITMQ | KAFKA | ACTIVEMQ (null → platform default)") String brokerType,
            @ToolParam(description = "QUEUE | STREAMING (null → platform default)") String processingMode,
            @ToolParam(description = "MUST be true to actually declare.") boolean confirm) {
        track();
        if (props.getAi().getCopilot().isConfirmDestructive() && !confirm) {
            return needsConfirmation("declareQueue",
                    "About to declare '" + destination + "' on " + tenantId + "/" + projectId +
                            " (broker=" + brokerType + "). Re-call with confirm=true.");
        }
        var asset = queueService.declareFromCatalog(tenantId, projectId, destination,
                emptyToNull(brokerType), emptyToNull(processingMode), null).block(TOOL_TIMEOUT);
        Map<String, Object> out = new HashMap<>();
        if (asset != null) {
            out.put("declared", true);
            out.put("destinationName", asset.getDestinationName());
            out.put("brokerType", asset.getBrokerType());
            out.put("processingMode", asset.getProcessingMode());
        } else {
            out.put("declared", false);
        }
        return out;
    }

    @Tool(description = "Publish a payload to a destination via the lazy-ingress path. WRITE — requires confirm=true.")
    public Map<String, Object> publishMessage(
            @ToolParam(description = "Workspace tenant slug") String tenantId,
            @ToolParam(description = "Workspace project slug") String projectId,
            @ToolParam(description = "Destination name") String destination,
            @ToolParam(description = "Raw payload (UTF-8 text or JSON)") String payload,
            @ToolParam(description = "MUST be true.") boolean confirm) {
        track();
        if (props.getAi().getCopilot().isConfirmDestructive() && !confirm) {
            return needsConfirmation("publishMessage",
                    "About to publish to " + tenantId + "/" + projectId + "/" + destination +
                            " payload bytes=" + (payload == null ? 0 : payload.length()) +
                            ". Re-call with confirm=true.");
        }
        var target = new MultiTenantPublishService.PublishTarget(tenantId, projectId, destination);
        var outcome = publishService.publish(null, List.of(target), payload).blockFirst(TOOL_TIMEOUT);
        Map<String, Object> out = new HashMap<>();
        out.put("status", outcome == null ? "UNKNOWN" : outcome.getStatus());
        out.put("brokerType", outcome == null ? null : outcome.getBrokerType());
        return out;
    }

    // ============================================================
    //                          HELPERS
    // ============================================================

    private Map<String, Object> needsConfirmation(String tool, String message) {
        Map<String, Object> out = new HashMap<>();
        out.put("status", "needs_confirmation");
        out.put("tool", tool);
        out.put("prompt", message);
        out.put("hint", "Re-issue the same call with confirm=true once the human has approved.");
        return out;
    }

    private String emptyToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    // For ObjectMapper DI consistency
    @SuppressWarnings("unused")
    private ObjectMapper mapper() { return objectMapper; }
}
