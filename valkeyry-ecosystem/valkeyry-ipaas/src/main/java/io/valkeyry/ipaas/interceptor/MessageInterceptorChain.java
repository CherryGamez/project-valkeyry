package io.valkeyry.ipaas.interceptor;

import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/** Composes registered interceptors into a sequential reactive chain. */
@Component
@RequiredArgsConstructor
public class MessageInterceptorChain {

    private final List<MessageInterceptor> interceptors;

    public Mono<ReactiveBrokerClient.IncomingMessage> process(ReactiveBrokerClient.IncomingMessage msg) {
        Mono<ReactiveBrokerClient.IncomingMessage> pipeline = Mono.just(msg);
        for (MessageInterceptor i : interceptors) {
            pipeline = pipeline.flatMap(i::intercept);
        }
        return pipeline;
    }
}
