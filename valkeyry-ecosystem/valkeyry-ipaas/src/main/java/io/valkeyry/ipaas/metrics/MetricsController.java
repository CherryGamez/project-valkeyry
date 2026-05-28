package io.valkeyry.ipaas.metrics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.RabbitBrokerClient;
import io.valkeyry.ipaas.repository.QueueAssetRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Tag(name = "Real-Time Metrics")
@RestController
@Validated
@RequestMapping("/api/v1/{tenantId}/{projectId}/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final QueueAssetRepository queueRepo;
    private final BrokerClientFactory brokerFactory;

    @Operation(summary = "Stream per-queue metrics via SSE at 1-second intervals.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<List<QueueMetric>>> stream(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                                           @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId) {
        return Flux.interval(Duration.ofSeconds(1))
                .flatMap(tick -> queueRepo.findAllByTenantIdAndProjectId(tenantId, projectId)
                        .flatMap(asset -> {
                            var broker = brokerFactory.get(asset.getBrokerType());
                            Mono<Long> depth = (broker instanceof RabbitBrokerClient rbc)
                                    ? rbc.queueDepth(asset.getTenantId(), asset.getProjectId(), asset.getDestinationName())
                                            .onErrorReturn(-1L)
                                    : Mono.just(-1L);
                            return depth.map(d -> new QueueMetric(asset.getDestinationName(),
                                    asset.getBrokerType(), d, 0L, 0L));
                        }).collectList())
                .map(list -> ServerSentEvent.<List<QueueMetric>>builder()
                        .event("metric").data(list).build());
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class QueueMetric {
        private String destination;
        private String brokerType;
        private long messageCount;
        private long consumerCount;
        private long dlqSize;
    }
}
