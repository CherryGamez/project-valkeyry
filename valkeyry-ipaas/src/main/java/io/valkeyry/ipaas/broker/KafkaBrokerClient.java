package io.valkeyry.ipaas.broker;

import java.util.UUID;

import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Kafka broker client.
 *
 * <p><b>DLQ peek (browseDlq):</b> uses the Kafka {@link AdminClient} for partition + offset
 * metadata (deterministic, no consumer-group footprint), then a transient {@code KafkaConsumer}
 * to actually fetch the records inside the computed offset window {@code [latest-N, latest)}.
 * This separation gives us:
 * <ul>
 *   <li>Per-partition window math without consumer-group rebalances.</li>
 *   <li>{@code browseDlqSummary()} that returns earliest/latest offsets + approximate depth
 *       per partition for the React UI &amp; Copilot.</li>
 *   <li>Graceful handling when the DLQ topic does not yet exist (returns empty/zero).</li>
 * </ul>
 */
@Slf4j
@Component
public class KafkaBrokerClient implements ReactiveBrokerClient {

    private final KafkaSender<String, byte[]> sender;
    private final String bootstrap;
    private volatile AdminClient adminClient;

    public KafkaBrokerClient(IpaasProperties props) {
        Map<String, String> k = props.getBrokers().getOrDefault("kafka", Map.of());
        this.bootstrap = k.getOrDefault("bootstrap-servers", "localhost:9092");
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        this.sender = KafkaSender.create(SenderOptions.create(p));
    }

    /** Lazily creates a shared AdminClient. Safe to call from multiple threads. */
    private AdminClient admin() {
        AdminClient a = adminClient;
        if (a == null) {
            synchronized (this) {
                a = adminClient;
                if (a == null) {
                    Properties props = new Properties();
                    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(AdminClientConfig.CLIENT_ID_CONFIG, "valkeyry-admin");
                    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
                    props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
                    a = AdminClient.create(props);
                    adminClient = a;
                }
            }
        }
        return a;
    }

    @Override public String type() { return "KAFKA"; }

    private String topic(String t, String p, String d) { return t + "." + p + "." + d; }
    private String dlq(String t, String p, String d)   { return topic(t, p, d) + ".dlq"; }

    @Override
    public Mono<Void> declareDestination(String tenantId, String projectId, String destinationName, String processingMode) {
        log.info("Kafka declare: {} (mode={})", topic(tenantId, projectId, destinationName), processingMode);
        return Mono.empty();
    }

    @Override
    public Mono<Void> publish(String tenantId, String projectId, String destinationName, byte[] payload, Map<String, String> headers) {
        var record = new org.apache.kafka.clients.producer.ProducerRecord<String, byte[]>(
                topic(tenantId, projectId, destinationName), null, payload);
        if (headers != null) headers.forEach((k, v) -> record.headers().add(k, v.getBytes()));
        return sender.send(Mono.just(SenderRecord.create(record, null))).then();
    }

    @Override
    public Mono<Void> publishDlq(String tenantId, String projectId, String destinationName, byte[] payload, Map<String, String> headers) {
        var record = new org.apache.kafka.clients.producer.ProducerRecord<String, byte[]>(
                dlq(tenantId, projectId, destinationName), null, payload);
        if (headers != null) headers.forEach((k, v) -> record.headers().add(k, v.getBytes()));
        return sender.send(Mono.just(SenderRecord.create(record, null))).then();
    }

    @Override
    public Disposable subscribe(String tenantId, String projectId, String destinationName, String processingMode,
                                Function<IncomingMessage, Mono<Boolean>> handler) {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, tenantId + "." + projectId + ".grp");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "STREAMING".equalsIgnoreCase(processingMode) ? "earliest" : "latest");

        ReceiverOptions<String, byte[]> opts = ReceiverOptions
                .<String, byte[]>create(p)
                .subscription(Collections.singleton(topic(tenantId, projectId, destinationName)));

        return KafkaReceiver.create(opts).receive()
                .flatMap(rec -> {
                    Map<String, String> hdrs = new HashMap<>();
                    rec.headers().forEach(h -> hdrs.put(h.key(), new String(h.value())));
                    IncomingMessage m = IncomingMessage.builder()
                            .messageId(rec.topic() + "-" + rec.partition() + "-" + rec.offset())
                            .payload(rec.value())
                            .headers(hdrs)
                            .build();
                    return handler.apply(m)
                            .defaultIfEmpty(true)
                            .doOnNext(ok -> rec.receiverOffset().acknowledge())
                            .onErrorResume(ex -> {
                                log.warn("Kafka consumer error, committing offset and rerouting: {}", ex.toString());
                                rec.receiverOffset().acknowledge();
                                return Mono.just(false);
                            });
                })
                .subscribe();
    }

    /**
     * Promote DLQ peek to use AdminClient for partition + offset metadata.
     *
     * <p>The flow is:
     * <ol>
     *   <li>{@link AdminClient#describeTopics} → resolve partitions (404-equivalent: empty Flux).</li>
     *   <li>{@link AdminClient#listOffsets} with EARLIEST &amp; LATEST → bound the window.</li>
     *   <li>Spawn an ephemeral {@code KafkaConsumer} with a unique group id (so no committed
     *       offsets ever survive), assign+seek into {@code [latest-N, latest)}, poll until N
     *       collected or 1500ms deadline.</li>
     * </ol>
     */
    @Override
    public Flux<IncomingMessage> browseDlq(String t, String p, String d, int limit) {
        final String topic = dlq(t, p, d);
        final int safeLimit = Math.max(1, Math.min(500, limit));

        return Flux.<IncomingMessage>create(sink -> {
            try {
                List<TopicPartition> tps = describeTopicPartitions(topic);
                if (tps.isEmpty()) {
                    log.debug("Kafka DLQ peek: topic '{}' has no partitions (likely not yet created).", topic);
                    sink.complete();
                    return;
                }

                Map<TopicPartition, Long> earliest = listOffsets(tps, OffsetSpec.earliest());
                Map<TopicPartition, Long> latest   = listOffsets(tps, OffsetSpec.latest());
                int perPartition = Math.max(1, safeLimit / tps.size());

                Map<String, Object> cfg = new HashMap<>();
                cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                cfg.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-peek-" + UUID.randomUUID());
                cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
                cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

                try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(cfg)) {
                    consumer.assign(tps);
                    for (var tp : tps) {
                        long end   = latest.getOrDefault(tp, 0L);
                        long start = earliest.getOrDefault(tp, 0L);
                        long seekTo = Math.max(start, end - perPartition);
                        consumer.seek(tp, seekTo);
                    }
                    int emitted = 0;
                    long deadline = System.currentTimeMillis() + 1500;
                    while (emitted < safeLimit && System.currentTimeMillis() < deadline) {
                        var poll = consumer.poll(Duration.ofMillis(300));
                        if (poll.isEmpty()) continue;
                        for (var rec : poll) {
                            if (emitted >= safeLimit) break;
                            Map<String, String> hdrs = new HashMap<>();
                            rec.headers().forEach(h -> hdrs.put(h.key(), new String(h.value())));
                            sink.next(IncomingMessage.builder()
                                    .messageId(rec.topic() + "-" + rec.partition() + "-" + rec.offset())
                                    .payload(rec.value()).headers(hdrs).build());
                            emitted++;
                        }
                    }
                    // intentionally no commitSync — preserves messages in DLQ.
                }
            } catch (Exception e) {
                log.warn("Kafka DLQ peek failed for {}: {}", topic, e.toString());
                sink.error(e);
                return;
            }
            sink.complete();
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * Admin-client-driven summary: per-partition earliest/latest offsets and approximate depth.
     * Used by the Admin Console + Copilot to give an operator a quick "is there a backlog?" view.
     */
    public Mono<DlqOffsetSummary> browseDlqSummary(String t, String p, String d) {
        final String topic = dlq(t, p, d);
        return Mono.fromCallable(() -> {
            List<TopicPartition> tps = describeTopicPartitions(topic);
            if (tps.isEmpty()) return new DlqOffsetSummary(topic, 0, 0L, List.of());

            Map<TopicPartition, Long> earliest = listOffsets(tps, OffsetSpec.earliest());
            Map<TopicPartition, Long> latest   = listOffsets(tps, OffsetSpec.latest());

            long totalDepth = 0;
            List<PartitionWindow> windows = new ArrayList<>(tps.size());
            for (var tp : tps) {
                long e = earliest.getOrDefault(tp, 0L);
                long l = latest.getOrDefault(tp, 0L);
                long depth = Math.max(0, l - e);
                totalDepth += depth;
                windows.add(new PartitionWindow(tp.partition(), e, l, depth));
            }
            return new DlqOffsetSummary(topic, tps.size(), totalDepth, windows);
        }).onErrorResume(ex -> {
            log.warn("browseDlqSummary failed for {}: {}", topic, ex.toString());
            return Mono.just(new DlqOffsetSummary(topic, 0, 0L, List.of()));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private List<TopicPartition> describeTopicPartitions(String topic) {
        try {
            Map<String, TopicDescription> desc = admin().describeTopics(List.of(topic))
                    .allTopicNames().get();
            TopicDescription td = desc.get(topic);
            if (td == null) return List.of();
            List<TopicPartition> tps = new ArrayList<>(td.partitions().size());
            td.partitions().forEach(pi -> tps.add(new TopicPartition(topic, pi.partition())));
            return tps;
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof UnknownTopicOrPartitionException) return List.of();
            throw new RuntimeException(ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }

    private Map<TopicPartition, Long> listOffsets(List<TopicPartition> tps, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> req = new HashMap<>();
        for (TopicPartition tp : tps) req.put(tp, spec);
        try {
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> r =
                    admin().listOffsets(req).all().get();
            Map<TopicPartition, Long> out = new HashMap<>();
            r.forEach((tp, info) -> out.put(tp, info.offset()));
            return out;
        } catch (Exception e) {
            log.warn("listOffsets({}) failed: {}", spec, e.toString());
            Map<TopicPartition, Long> empty = new HashMap<>();
            for (TopicPartition tp : tps) empty.put(tp, 0L);
            return empty;
        }
    }

    @Override
    public Mono<IncomingMessage> consumeDlqMessage(String t, String p, String d, String mid) {
        // messageId format: "<topic>-<partition>-<offset>"
        String[] parts = mid == null ? new String[0] : mid.split("-");
        if (parts.length < 3) return Mono.empty();
        final int partition;
        final long offset;
        try {
            partition = Integer.parseInt(parts[parts.length - 2]);
            offset    = Long.parseLong(parts[parts.length - 1]);
        } catch (NumberFormatException e) { return Mono.empty(); }
        String topic = dlq(t, p, d);
        return Mono.fromCallable(() -> {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            cfg.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-pull-" + UUID.randomUUID());
            cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            try (org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]> consumer =
                         new org.apache.kafka.clients.consumer.KafkaConsumer<>(cfg)) {
                var tp = new TopicPartition(topic, partition);
                consumer.assign(java.util.Collections.singleton(tp));
                consumer.seek(tp, offset);
                var poll = consumer.poll(Duration.ofSeconds(2));
                for (var rec : poll) {
                    if (rec.offset() == offset) {
                        Map<String, String> hdrs = new HashMap<>();
                        rec.headers().forEach(h -> hdrs.put(h.key(), new String(h.value())));
                        // Kafka can't physically remove a single record; emit a tombstone marker so
                        // downstream consumers know this offset has been "consumed" administratively.
                        return IncomingMessage.builder()
                                .messageId(mid).payload(rec.value()).headers(hdrs).build();
                    }
                }
                return null;
            }
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .flatMap(m -> m == null ? Mono.empty() : Mono.just(m));
    }

    // ============================================================
    //                      Public DTOs
    // ============================================================

    public record DlqOffsetSummary(String topic, int partitionCount,
                                   long approximateDepth, List<PartitionWindow> partitions) {}
    public record PartitionWindow(int partition, long earliestOffset, long latestOffset, long depth) {}
}
