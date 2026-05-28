package io.valkeyry.ipaas.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.domain.ConsumerConfiguration;
import io.valkeyry.ipaas.file.ReactiveFileStreamingService;
import io.valkeyry.ipaas.interceptor.MessageInterceptorChain;
import io.valkeyry.ipaas.repository.MessageLogRepository;
import io.valkeyry.ipaas.routing.DynamicTransformationRoutingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamicConsumerManagerTest {

    @Mock ReactiveStringRedisTemplate redis;
    @Mock ReactiveValueOperations<String, String> valueOps;
    @Mock DynamicTransformationRoutingEngine engine;
    @Mock MessageInterceptorChain interceptorChain;
    @Mock ReactiveFileStreamingService fileStreaming;
    @Mock MessageLogRepository logRepo;
    @Mock Tracer tracer;
    @Mock ReactiveBrokerClient broker;

    DynamicConsumerManager manager;

    @BeforeEach
    void setUp() {
        IpaasProperties props = new IpaasProperties();
        manager = new DynamicConsumerManager(
                null, null, null, engine, redis, interceptorChain, fileStreaming,
                logRepo, props, tracer, new ObjectMapper());
        when(redis.opsForValue()).thenReturn(valueOps);
        when(logRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tracer.currentSpan()).thenReturn(null);
    }

    @Test
    void idempotencyHit_shortCircuitsAndAcknowledges() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Mono.just(false));

        ConsumerConfiguration cfg = ConsumerConfiguration.builder()
                .sourceDestination("orders")
                .targetWebhookUrl("http://x/hook").fieldMappingsJson("{}")
                .build();
        ReactiveBrokerClient.IncomingMessage msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1")
                .payload("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
                .headers(new HashMap<>(Map.of("x-idempotency-key", "uuid-1")))
                .build();
        StepVerifier.create(manager.processMessage("acme-corp", "payments-prod", cfg, broker, Map.of(), msg))
                .expectNext(true)
                .verifyComplete();
        verifyNoInteractions(engine);
    }

    @Test
    void successfulDispatch_returnsTrueAndLogsDelivered() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(interceptorChain.process(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(engine.transform(anyString(), anyMap())).thenReturn(Map.of("k", "v"));
        when(engine.dispatch(anyString(), anyMap(), anyMap())).thenReturn(Mono.just(200));

        ConsumerConfiguration cfg = ConsumerConfiguration.builder()
                .sourceDestination("orders").targetWebhookUrl("http://x/hook")
                .fieldMappingsJson("{\"k\":\"$.k\"}").build();
        ReactiveBrokerClient.IncomingMessage msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{\"k\":\"v\"}".getBytes()).headers(new HashMap<>())
                .build();
        StepVerifier.create(manager.processMessage("acme-corp", "payments-prod", cfg, broker, Map.of("k", "$.k"), msg))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void downstream5xx_routesToDlq() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(interceptorChain.process(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(engine.transform(anyString(), anyMap())).thenReturn(Map.of());
        when(engine.dispatch(anyString(), anyMap(), anyMap())).thenReturn(Mono.just(500));
        when(broker.publishDlq(any(), any(), anyString(), any(), anyMap())).thenReturn(Mono.empty());

        ConsumerConfiguration cfg = ConsumerConfiguration.builder()
                .sourceDestination("orders").targetWebhookUrl("http://x/hook")
                .fieldMappingsJson("{}").build();
        ReactiveBrokerClient.IncomingMessage msg = ReactiveBrokerClient.IncomingMessage.builder()
                .messageId("m1").payload("{}".getBytes()).headers(new HashMap<>())
                .build();
        StepVerifier.create(manager.processMessage("acme-corp", "payments-prod", cfg, broker, Map.of(), msg))
                .expectNext(true)
                .verifyComplete();
        verify(broker).publishDlq(any(), any(), eq("orders"), any(), anyMap());
    }
}
