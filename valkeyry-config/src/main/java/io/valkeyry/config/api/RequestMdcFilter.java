package io.valkeyry.config.api;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reactive web filter that hydrates the SLF4J MDC with per-request context so every log
 * line — anywhere in the call graph — automatically carries
 * {@code requestId / tenantId / tableName / recordKey}. The Logback pattern in
 * {@code logback-spring.xml} prints those keys at the start of every line, which is what
 * turns a noisy log file into a directly-greppable trail.
 *
 * <p>Why a custom filter rather than {@code reactor.util.context}? Spring's reactive stack
 * runs the request on a small set of Netty event-loop threads. Stamping the MDC on the
 * dispatch thread (which the filter is on) means every synchronous log inside the controller
 * / service runs with the right tenant / table context. For the rare path that switches to
 * a different scheduler, the same key/value map is attached to the Reactor {@link Context}
 * so handlers can pull it back via {@code Mono.deferContextual} when needed.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestMdcFilter implements WebFilter {

    /** Captures `/api/v1/tenants/{tenantId}/tables/{tableName}/entries/{recordKey}` and friends. */
    private static final Pattern PATH = Pattern.compile(
            "/api/v1/tenants/([^/]+)(?:/tables/([^/]+))?(?:/entries(?::batch)?/?([^/?#]+)?)?.*");

    static final String KEY_REQUEST_ID = "requestId";
    static final String KEY_TENANT     = "tenantId";
    static final String KEY_TABLE      = "tableName";
    static final String KEY_RECORD_KEY = "recordKey";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        Map<String, String> ctx = new HashMap<>();
        ctx.put(KEY_REQUEST_ID, req.getId());

        Matcher m = PATH.matcher(req.getURI().getPath());
        if (m.matches()) {
            String tenant = m.group(1);
            String table = m.group(2);
            String recordKey = m.group(3);
            if (tenant != null) ctx.put(KEY_TENANT, tenant);
            if (table != null) ctx.put(KEY_TABLE, table);
            // Only set if it's a real per-record path — not the batch verb.
            if (recordKey != null && !recordKey.equals("batch")) ctx.put(KEY_RECORD_KEY, recordKey);
        }

        // Eagerly stamp the MDC on the dispatch thread so any sync log inside the filter
        // chain (e.g. security) also sees the values; the contextWrite below propagates to
        // reactive operators downstream of the controller.
        ctx.forEach(MDC::put);
        return chain.filter(exchange)
                .contextWrite(c -> {
                    Context out = c;
                    for (Map.Entry<String, String> e : ctx.entrySet()) {
                        out = out.put(e.getKey(), e.getValue());
                    }
                    return out;
                })
                .doFinally(sig -> ctx.keySet().forEach(MDC::remove));
    }
}
