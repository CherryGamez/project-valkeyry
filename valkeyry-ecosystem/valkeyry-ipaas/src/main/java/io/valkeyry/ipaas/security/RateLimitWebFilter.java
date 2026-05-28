package io.valkeyry.ipaas.security;

import io.valkeyry.ipaas.config.IpaasProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Token-bucket rate-limiter backed by Valkey via a Lua script (atomic).
 * Key is namespaced: ratelimit:{remote-ip}:{tenantId-or-_}:{projectId-or-_}.
 * Rejects with 429 when bucket is empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitWebFilter implements WebFilter {

    private final IpaasProperties props;
    private final ReactiveStringRedisTemplate redis;

    private RedisScript<List> scriptCached;

    private RedisScript<List> tokenBucketScript() {
        if (scriptCached == null) {
            scriptCached = RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);
        }
        return scriptCached;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            return chain.filter(exchange);
        }
        String remote = exchange.getRequest().getRemoteAddress() == null
                ? "unknown" : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        String[] parts = path.split("/");
        String tenant = parts.length >= 5 ? parts[3] : "_";
        String project = parts.length >= 5 ? parts[4] : "_";
        String key = "ratelimit:" + remote + ":" + tenant + ":" + project;

        long capacity = props.getRateLimit().getCapacity();
        long refillTokens = props.getRateLimit().getRefillTokens();
        long refillSeconds = props.getRateLimit().getRefillPeriodSeconds();
        long now = System.currentTimeMillis() / 1000;

        return redis.execute(tokenBucketScript(),
                        List.of(key),
                        List.of(String.valueOf(capacity),
                                String.valueOf(refillTokens),
                                String.valueOf(refillSeconds),
                                String.valueOf(now)))
                .next()
                .flatMap(result -> {
                    @SuppressWarnings("unchecked")
                    List<Object> r = (List<Object>) result;
                    long allowed = Long.parseLong(String.valueOf(r.get(0)));
                    long remaining = Long.parseLong(String.valueOf(r.get(1)));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(remaining));
                    if (allowed == 0L) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(ex -> {
                    log.warn("Rate-limit backend unavailable, allowing request: {}", ex.toString());
                    return chain.filter(exchange);
                });
    }
}
