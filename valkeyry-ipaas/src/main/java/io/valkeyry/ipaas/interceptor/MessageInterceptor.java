package io.valkeyry.ipaas.interceptor;

import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import reactor.core.publisher.Mono;

/** Reactive message interceptor stage. */
public interface MessageInterceptor {
    Mono<ReactiveBrokerClient.IncomingMessage> intercept(ReactiveBrokerClient.IncomingMessage msg);
}
