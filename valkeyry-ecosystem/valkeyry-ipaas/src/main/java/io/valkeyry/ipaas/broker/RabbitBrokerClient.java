package io.valkeyry.ipaas.broker;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Component
public class RabbitBrokerClient implements ReactiveBrokerClient {

    private final Sender sender;
    private final Receiver receiver;

    @Autowired
    public RabbitBrokerClient(IpaasProperties props) {
        Map<String, String> r = props.getBrokers().getOrDefault("rabbitmq", Map.of());
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(r.getOrDefault("host", "localhost"));
        cf.setPort(Integer.parseInt(r.getOrDefault("port", "5672")));
        cf.setUsername(r.getOrDefault("username", "guest"));
        cf.setPassword(r.getOrDefault("password", "guest"));
        SenderOptions senderOpts = new SenderOptions().connectionFactory(cf);
        ReceiverOptions receiverOpts = new ReceiverOptions().connectionFactory(cf);
        this.sender = RabbitFlux.createSender(senderOpts);
        this.receiver = RabbitFlux.createReceiver(receiverOpts);
    }

    @Override public String type() { return "RABBITMQ"; }

    private String physical(String t, String p, String d) { return t + "." + p + "." + d; }
    private String dlq(String t, String p, String d)      { return physical(t, p, d) + ".dlq"; }

    @Override
    public Mono<Void> declareDestination(String tenantId, String projectId, String destinationName, String processingMode) {
        String primary = physical(tenantId, projectId, destinationName);
        String deadLetter = dlq(tenantId, projectId, destinationName);

        Map<String, Object> dlqArgs = new HashMap<>();
        Map<String, Object> primaryArgs = new HashMap<>();
        primaryArgs.put("x-queue-type", "STREAMING".equalsIgnoreCase(processingMode) ? "stream" : "quorum");
        primaryArgs.put("x-dead-letter-exchange", "");
        primaryArgs.put("x-dead-letter-routing-key", deadLetter);

        return sender.declareQueue(QueueSpecification.queue(deadLetter).durable(true).arguments(dlqArgs))
                .then(sender.declareQueue(QueueSpecification.queue(primary).durable(true).arguments(primaryArgs)))
                .then();
    }

    @Override
    public Mono<Void> publish(String tenantId, String projectId, String destinationName,
                              byte[] payload, Map<String, String> headers) {
        return sendInternal(physical(tenantId, projectId, destinationName), payload, headers);
    }

    @Override
    public Mono<Void> publishDlq(String tenantId, String projectId, String destinationName,
                                 byte[] payload, Map<String, String> headers) {
        return sendInternal(dlq(tenantId, projectId, destinationName), payload, headers);
    }

    private Mono<Void> sendInternal(String routingKey, byte[] payload, Map<String, String> headers) {
        Map<String, Object> hdrs = new HashMap<>();
        if (headers != null) hdrs.putAll(headers);
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(hdrs).deliveryMode(2).build();
        OutboundMessage msg = new OutboundMessage("", routingKey, props, payload);
        return sender.send(Mono.just(msg)).then();
    }

    @Override
    public Disposable subscribe(String tenantId, String projectId, String destinationName,
                                String processingMode,
                                Function<IncomingMessage, Mono<Boolean>> handler) {
        String queue = physical(tenantId, projectId, destinationName);
        ConsumeOptions opts = new ConsumeOptions().qos(50);
        return receiver.consumeManualAck(queue, opts)
                .flatMap(ad -> {
                    IncomingMessage incoming = IncomingMessage.builder()
                            .messageId(String.valueOf(ad.getEnvelope().getDeliveryTag()))
                            .payload(ad.getBody())
                            .headers(toStringMap(ad.getProperties().getHeaders()))
                            .build();
                    return handler.apply(incoming)
                            .defaultIfEmpty(true)
                            .doOnNext(ok -> {
                                if (ok) ad.ack();
                                else    ad.nack(false);
                            })
                            .onErrorResume(ex -> {
                                log.warn("Consumer handler error, NACKing: {}", ex.toString());
                                ad.nack(false);
                                return Mono.just(false);
                            });
                })
                .subscribe();
    }

    @Override
    public Flux<IncomingMessage> browseDlq(String tenantId, String projectId, String destinationName, int limit) {
        // Reactor-RabbitMQ does not expose a 'basicGet' API; in production
        // we'd use HTTP Management API. For this reference we consume + republish.
        String dlqName = dlq(tenantId, projectId, destinationName);
        return receiver.consumeManualAck(dlqName, new ConsumeOptions().qos(limit))
                .take(limit)
                .map(ad -> {
                    IncomingMessage m = IncomingMessage.builder()
                            .messageId(String.valueOf(ad.getEnvelope().getDeliveryTag()))
                            .payload(ad.getBody())
                            .headers(toStringMap(ad.getProperties().getHeaders()))
                            .build();
                    ad.nack(true);   // requeue: peek-only semantics
                    return m;
                });
    }

    @Override
    public Mono<IncomingMessage> consumeDlqMessage(String tenantId, String projectId, String destinationName, String messageId) {
        String dlqName = dlq(tenantId, projectId, destinationName);
        return receiver.consumeManualAck(dlqName, new ConsumeOptions().qos(50))
                .filter(ad -> messageId.equals(String.valueOf(ad.getEnvelope().getDeliveryTag())))
                .next()
                .map(ad -> {
                    IncomingMessage m = IncomingMessage.builder()
                            .messageId(String.valueOf(ad.getEnvelope().getDeliveryTag()))
                            .payload(ad.getBody())
                            .headers(toStringMap(ad.getProperties().getHeaders()))
                            .build();
                    ad.ack();
                    return m;
                });
    }

    private Map<String, String> toStringMap(Map<String, Object> headers) {
        Map<String, String> out = new HashMap<>();
        if (headers != null) headers.forEach((k, v) -> {
            if (v != null) out.put(k, v.toString());
        });
        return out;
    }

    public Mono<Long> queueDepth(String tenantId, String projectId, String destinationName) {
        return sender.declareQueue(QueueSpecification.queue(
                        physical(tenantId, projectId, destinationName)).passive(true))
                .map(ok -> (long) ok.getMessageCount())
                .onErrorReturn(-1L);
    }

    public byte[] payloadAsBytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
