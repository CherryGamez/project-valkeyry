package io.valkeyry.ipaas.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.domain.ConsumerConfiguration;
import io.valkeyry.ipaas.domain.MessageLog;
import io.valkeyry.ipaas.domain.QueueAsset;
import io.valkeyry.ipaas.file.ClaimTicket;
import io.valkeyry.ipaas.file.ReactiveFileStreamingService;
import io.valkeyry.ipaas.interceptor.MessageInterceptorChain;
import io.valkeyry.ipaas.repository.ConsumerConfigurationRepository;
import io.valkeyry.ipaas.repository.MessageLogRepository;
import io.valkeyry.ipaas.repository.QueueAssetRepository;
import io.valkeyry.ipaas.routing.DynamicTransformationRoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activates configured consumers. Pipeline per message:
 *   Idempotency-guard (Valkey) -> Interceptor chain -> Transform -> WebClient POST
 *   -> ACK on 2xx OR route to DLQ on error/circuit-trip.
 * Active streams tracked in-memory by "tenantId:projectId:consumerId" key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicConsumerManager {

    private final ConsumerConfigurationRepository consumerRepo;
    private final QueueAssetRepository queueRepo;
    private final BrokerClientFactory brokerFactory;
    private final DynamicTransformationRoutingEngine engine;
    private final ReactiveStringRedisTemplate redis;
    private final MessageInterceptorChain interceptorChain;
    private final ReactiveFileStreamingService fileStreaming;
    private final MessageLogRepository logRepo;
    private final IpaasProperties props;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Disposable> registry = new ConcurrentHashMap<>();

    public Mono<String> activate(String tenantId, String projectId, UUID consumerId) {
        return consumerRepo.findById(consumerId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Consumer not found: " + consumerId)))
                .flatMap(cfg -> queueRepo
                        .findByTenantIdAndProjectIdAndDestinationName(tenantId, projectId, cfg.getSourceDestination())
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "Source destination not provisioned: " + cfg.getSourceDestination())))
                        .map(asset -> startStream(tenantId, projectId, cfg, asset)));
    }

    private String startStream(String tenantId, String projectId, ConsumerConfiguration cfg, QueueAsset asset) {
        String key = tenantId + ":" + projectId + ":" + cfg.getId();
        Disposable previous = registry.remove(key);
        if (previous != null) previous.dispose();

        ReactiveBrokerClient broker = brokerFactory.get(asset.getBrokerType());
        Map<String, String> mappings = parseMappings(cfg.getFieldMappingsJson());

        Disposable d = Mono.fromRunnable(() ->
                broker.subscribe(tenantId, projectId, cfg.getSourceDestination(),
                        asset.getProcessingMode(),
                        msg -> processMessage(tenantId, projectId, cfg, broker, mappings, msg)))
                .subscribe();
        registry.put(key, d);
        log.info("Activated consumer {} for {}.{}.{}", cfg.getId(), tenantId, projectId, cfg.getSourceDestination());
        return key;
    }

    public Mono<Boolean> deactivate(String tenantId, String projectId, UUID consumerId) {
        Disposable d = registry.remove(tenantId + ":" + projectId + ":" + consumerId);
        if (d != null) { d.dispose(); return Mono.just(true); }
        return Mono.just(false);
    }

    /** Visible for testing. */
    public Mono<Boolean> processMessage(String tenantId, String projectId, ConsumerConfiguration cfg,
                                        ReactiveBrokerClient broker,
                                        Map<String, String> mappings,
                                        ReactiveBrokerClient.IncomingMessage msg) {
        String uuidHeader = msg.getHeaders().getOrDefault("x-idempotency-key", msg.getMessageId());
        String idempKey = "idempotency:" + tenantId + ":" + projectId + ":" + uuidHeader;
        String traceId = currentTraceId();

        return redis.opsForValue().setIfAbsent(idempKey, "1",
                        Duration.ofSeconds(props.getIdempotency().getTtlSeconds()))
                .flatMap(firstSeen -> {
                    if (Boolean.FALSE.equals(firstSeen)) {
                        log.debug("Idempotent skip: {}", idempKey);
                        return Mono.just(true);
                    }
                    return interceptorChain.process(msg)
                            .flatMap(enriched -> dispatch(tenantId, projectId, cfg, broker, mappings, enriched, traceId));
                })
                .onErrorResume(ex -> {
                    log.warn("Consumer pipeline error -> DLQ: {}", ex.toString());
                    return broker.publishDlq(tenantId, projectId, cfg.getSourceDestination(),
                                    msg.getPayload(), msg.getHeaders())
                            .thenReturn(true);
                });
    }

    private Mono<Boolean> dispatch(String tenantId, String projectId, ConsumerConfiguration cfg,
                                   ReactiveBrokerClient broker, Map<String, String> mappings,
                                   ReactiveBrokerClient.IncomingMessage msg, String traceId) {

        String raw = new String(msg.getPayload(), StandardCharsets.UTF_8);

        // Detect Claim-Ticket file payload, stream-forward as chunked body.
        Mono<Integer> dispatchMono;
        if (isClaimTicket(raw)) {
            dispatchMono = forwardClaimTicket(tenantId, projectId, cfg, raw, traceId);
        } else {
            Map<String, Object> transformed = engine.transform(raw, mappings);
            dispatchMono = engine.dispatch(cfg.getTargetWebhookUrl(), transformed,
                    Map.of("x-trace-id", traceId == null ? "" : traceId));
        }

        return dispatchMono
                .flatMap(status -> {
                    boolean ok = status >= 200 && status < 300;
                    return logRepo.save(MessageLog.builder()
                                    .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                                    .destination(cfg.getSourceDestination()).traceId(traceId)
                                    .status(ok ? "DELIVERED" : "DLQ").payload(raw)
                                    .createdAt(OffsetDateTime.now()).build())
                            .then(ok
                                    ? Mono.just(true)
                                    : broker.publishDlq(tenantId, projectId, cfg.getSourceDestination(),
                                            msg.getPayload(), msg.getHeaders()).thenReturn(true));
                })
                .onErrorResume(ex -> broker.publishDlq(tenantId, projectId,
                                cfg.getSourceDestination(), msg.getPayload(), msg.getHeaders())
                        .thenReturn(true));
    }

    private boolean isClaimTicket(String raw) {
        return raw.contains("\"claimTicket\"") && raw.contains("\"objectKey\"");
    }

    private Mono<Integer> forwardClaimTicket(String tenantId, String projectId, ConsumerConfiguration cfg,
                                             String raw, String traceId) {
        try {
            ClaimTicket ticket = objectMapper.readValue(raw, ClaimTicket.class);
            return fileStreaming.streamObjectOut(tenantId, projectId,
                    ticket.getStorageConfigName(), ticket.getObjectKey(),
                    cfg.getTargetWebhookUrl(),
                    Map.of("x-trace-id", traceId == null ? "" : traceId,
                            "x-claim-ticket", ticket.getObjectKey()));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private Map<String, String> parseMappings(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    public Map<String, Disposable> activeRegistry() { return registry; }
}
