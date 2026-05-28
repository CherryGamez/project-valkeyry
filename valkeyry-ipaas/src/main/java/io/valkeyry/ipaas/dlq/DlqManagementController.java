package io.valkeyry.ipaas.dlq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "DLQ Management")
@RestController
@Validated
@RequestMapping("/api/v1/{tenantId}/{projectId}/dlq")
@RequiredArgsConstructor
public class DlqManagementController {

    private final DlqManagementService service;

    @Operation(summary = "Peek/browse DLQ messages (non-destructive).")
    @GetMapping("/{queueName}/messages")
    public Flux<DlqManagementService.DlqMessage> browse(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                                        @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                                        @PathVariable String queueName,
                                                        @RequestParam(defaultValue = "20") int limit) {
        return service.browse(tenantId, projectId, queueName, limit);
    }

    @Operation(summary = "Kafka DLQ offset-window summary (AdminClient-driven).",
            description = "Returns per-partition earliest/latest offsets and approximate backlog depth. " +
                    "For AMQP brokers (RabbitMQ/ActiveMQ) returns a 'Kafka-only' note.")
    @GetMapping("/{queueName}/summary")
    public Mono<java.util.Map<String, Object>> summary(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                                       @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                                       @PathVariable String queueName) {
        return service.dlqSummary(tenantId, projectId, queueName);
    }

    @Operation(summary = "Bulk-retry: replay each message back to the primary queue, optionally substituting payload.")
    @PostMapping("/{queueName}/bulk-retry")
    public Mono<DlqManagementService.BulkRetryResult> bulkRetry(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                                                @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                                                @PathVariable String queueName,
                                                                @RequestBody List<DlqManagementService.BulkRetryItem> items) {
        return service.bulkRetry(tenantId, projectId, queueName, items);
    }
}
