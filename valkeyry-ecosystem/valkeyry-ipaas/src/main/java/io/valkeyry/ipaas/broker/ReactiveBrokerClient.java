package io.valkeyry.ipaas.broker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Unified broker abstraction. Implementations adapt RabbitMQ, Kafka, ActiveMQ
 * to a single reactive contract scoped by {tenantId, projectId}.
 */
public interface ReactiveBrokerClient {

    /** Returned broker identifier (RABBITMQ|KAFKA|ACTIVEMQ). */
    String type();

    /** Declares the primary destination + matching DLQ. Idempotent. */
    Mono<Void> declareDestination(String tenantId, String projectId, String destinationName, String processingMode);

    /** Publishes a payload to the primary destination. */
    Mono<Void> publish(String tenantId, String projectId, String destinationName, byte[] payload, Map<String, String> headers);

    /** Routes a payload directly to the DLQ. */
    Mono<Void> publishDlq(String tenantId, String projectId, String destinationName, byte[] payload, Map<String, String> headers);

    /**
     * Subscribe to a destination with MANUAL acknowledgments. The supplied handler returns:
     * - Mono.empty() / Mono.just(true) => ACK
     * - Mono.error(...) or Mono.just(false) => NACK and route to DLQ
     */
    Disposable subscribe(String tenantId, String projectId, String destinationName,
                         String processingMode,
                         Function<IncomingMessage, Mono<Boolean>> handler);

    /** Peek messages in DLQ without removing them. */
    Flux<IncomingMessage> browseDlq(String tenantId, String projectId, String destinationName, int limit);

    /** Pull a single message from DLQ by id, removing it. */
    Mono<IncomingMessage> consumeDlqMessage(String tenantId, String projectId, String destinationName, String messageId);

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class IncomingMessage {
        private String messageId;
        private byte[] payload;
        private Map<String, String> headers;
    }
}
