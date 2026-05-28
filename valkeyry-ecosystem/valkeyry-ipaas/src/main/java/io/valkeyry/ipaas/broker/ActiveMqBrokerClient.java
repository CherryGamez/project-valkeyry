package io.valkeyry.ipaas.broker;

import io.valkeyry.ipaas.config.IpaasProperties;
import jakarta.jms.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Note: JMS is inherently blocking. We bridge to reactive by isolating
 * the small synchronous JMS calls onto {@link Schedulers#boundedElastic()}.
 */
@Slf4j
@Component
public class ActiveMqBrokerClient implements ReactiveBrokerClient {

    private final ActiveMQConnectionFactory factory;

    public ActiveMqBrokerClient(IpaasProperties props) {
        Map<String, String> a = props.getBrokers().getOrDefault("activemq", Map.of());
        this.factory = new ActiveMQConnectionFactory(
                a.getOrDefault("username", "admin"),
                a.getOrDefault("password", "admin"),
                a.getOrDefault("broker-url", "tcp://localhost:61616"));
    }

    @Override public String type() { return "ACTIVEMQ"; }

    private String q(String t, String p, String d)   { return t + "." + p + "." + d; }
    private String dlq(String t, String p, String d) { return q(t, p, d) + ".dlq"; }

    @Override
    public Mono<Void> declareDestination(String t, String p, String d, String mode) {
        // ActiveMQ auto-creates queues on first use; no-op declare.
        return Mono.empty();
    }

    @Override
    public Mono<Void> publish(String t, String p, String d, byte[] payload, Map<String, String> headers) {
        return Mono.fromRunnable(() -> sendBlocking(q(t, p, d), payload, headers))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> publishDlq(String t, String p, String d, byte[] payload, Map<String, String> headers) {
        return Mono.fromRunnable(() -> sendBlocking(dlq(t, p, d), payload, headers))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void sendBlocking(String queue, byte[] payload, Map<String, String> headers) {
        try (Connection conn = factory.createConnection()) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue dest = session.createQueue(queue);
            try (MessageProducer producer = session.createProducer(dest)) {
                BytesMessage msg = session.createBytesMessage();
                msg.writeBytes(payload);
                if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                    msg.setStringProperty(e.getKey(), e.getValue());
                }
                producer.send(msg);
            }
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Disposable subscribe(String t, String p, String d, String mode,
                                Function<IncomingMessage, Mono<Boolean>> handler) {
        return Flux.<IncomingMessage>create(sink -> {
            try {
                Connection conn = factory.createConnection();
                conn.start();
                Session session = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                MessageConsumer consumer = session.createConsumer(session.createQueue(q(t, p, d)));
                consumer.setMessageListener(m -> {
                    try {
                        byte[] body = new byte[0];
                        if (m instanceof BytesMessage bm) {
                            body = new byte[(int) bm.getBodyLength()];
                            bm.readBytes(body);
                        }
                        Map<String, String> hdrs = new HashMap<>();
                        var en = m.getPropertyNames();
                        while (en.hasMoreElements()) {
                            String k = (String) en.nextElement();
                            hdrs.put(k, String.valueOf(m.getObjectProperty(k)));
                        }
                        IncomingMessage incoming = IncomingMessage.builder()
                                .messageId(m.getJMSMessageID()).payload(body).headers(hdrs).build();
                        handler.apply(incoming)
                                .defaultIfEmpty(true)
                                .doOnNext(ok -> { try { if (ok) m.acknowledge(); } catch (JMSException ignored) {} })
                                .subscribe();
                    } catch (Exception ex) {
                        log.warn("ActiveMQ consumer error: {}", ex.toString());
                    }
                });
            } catch (JMSException e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    @Override public Flux<IncomingMessage> browseDlq(String t, String p, String d, int limit) { return Flux.empty(); }
    @Override public Mono<IncomingMessage> consumeDlqMessage(String t, String p, String d, String mid) { return Mono.empty(); }
}
