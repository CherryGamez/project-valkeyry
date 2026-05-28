package io.valkeyry.ipaas.dlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.repository.QueueAssetRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqManagementService {

    private final QueueAssetRepository queueRepo;
    private final BrokerClientFactory brokerFactory;
    private final ObjectMapper objectMapper;

    public Flux<DlqMessage> browse(String tenantId, String projectId, String destination, int limit) {
        return queueRepo.findByTenantIdAndProjectIdAndDestinationName(tenantId, projectId, destination)
                .flatMapMany(asset -> brokerFactory.get(asset.getBrokerType())
                        .browseDlq(tenantId, projectId, destination, limit))
                .map(m -> new DlqMessage(m.getMessageId(),
                        new String(m.getPayload(), StandardCharsets.UTF_8),
                        m.getHeaders()));
    }

    /**
     * Kafka-only DLQ offset-window summary (AdminClient-driven).
     * For non-Kafka brokers, returns an empty summary so the UI degrades gracefully.
     */
    public Mono<Map<String, Object>> dlqSummary(String tenantId, String projectId, String destination) {
        return queueRepo.findByTenantIdAndProjectIdAndDestinationName(tenantId, projectId, destination)
                .flatMap(asset -> {
                    var broker = brokerFactory.get(asset.getBrokerType());
                    if (broker instanceof io.valkeyry.ipaas.broker.KafkaBrokerClient kbc) {
                        return kbc.browseDlqSummary(tenantId, projectId, destination)
                                .map(s -> {
                                    Map<String, Object> out = new HashMap<>();
                                    out.put("brokerType", "KAFKA");
                                    out.put("topic", s.topic());
                                    out.put("partitionCount", s.partitionCount());
                                    out.put("approximateDepth", s.approximateDepth());
                                    out.put("partitions", s.partitions());
                                    return out;
                                });
                    }
                    Map<String, Object> out = new HashMap<>();
                    out.put("brokerType", asset.getBrokerType());
                    out.put("note", "DLQ offset window summary is Kafka-only; use queueDepth for AMQP brokers.");
                    return Mono.just(out);
                });
    }

    public Mono<BulkRetryResult> bulkRetry(String tenantId, String projectId, String destination,
                                           List<BulkRetryItem> items) {
        return queueRepo.findByTenantIdAndProjectIdAndDestinationName(tenantId, projectId, destination)
                .flatMap(asset -> {
                    ReactiveBrokerClient broker = brokerFactory.get(asset.getBrokerType());
                    return Flux.fromIterable(items)
                            .flatMap(item -> retryOne(broker, tenantId, projectId, destination, item))
                            .reduce(new BulkRetryResult(0, 0), (acc, ok) -> {
                                if (ok) acc.succeeded++; else acc.failed++;
                                return acc;
                            });
                });
    }

    private Mono<Boolean> retryOne(ReactiveBrokerClient broker, String tenantId, String projectId,
                                   String destination, BulkRetryItem item) {
        return broker.consumeDlqMessage(tenantId, projectId, destination, item.getMessageId())
                .flatMap(msg -> {
                    byte[] payload = item.getNewPayload() != null
                            ? item.getNewPayload().getBytes(StandardCharsets.UTF_8)
                            : msg.getPayload();
                    Map<String, String> headers = new HashMap<>(msg.getHeaders());
                    headers.remove("x-death");
                    int retry = 0;
                    try { retry = Integer.parseInt(headers.getOrDefault("x-retry-count", "0")); }
                    catch (NumberFormatException ignored) {}
                    headers.put("x-retry-count", String.valueOf(retry + 1));
                    return broker.publish(tenantId, projectId, destination, payload, headers)
                            .thenReturn(true);
                })
                .defaultIfEmpty(false)
                .onErrorResume(ex -> {
                    log.warn("Bulk retry failure for {}: {}", item.getMessageId(), ex.toString());
                    return Mono.just(false);
                });
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class DlqMessage {
        private String messageId;
        private String payload;
        private Map<String, String> headers;
    }
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class BulkRetryItem {
        private String messageId;
        private String newPayload;          // optional
    }
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class BulkRetryResult {
        private int succeeded;
        private int failed;
    }
}
