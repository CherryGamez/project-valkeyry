package io.valkeyry.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.valkeyry.config.config.AuditWebhookProperties;
import io.valkeyry.config.domain.ConfigAuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Pushes every persisted {@link ConfigAuditEntry} to the configured webhook URLs.
 *
 * <p>Designed to be SIEM-friendly:</p>
 * <ul>
 *   <li>One {@code POST application/json} per URL per event (no batching → SIEM can react in real time).</li>
 *   <li>Body is the full audit row (before + after values included).</li>
 *   <li>{@code X-Valkeyry-Signature: sha256=&lt;hex&gt;} HMAC-SHA256 of the raw body using the configured
 *       shared secret — the SIEM verifies and rejects any tampered or replayed payload.</li>
 *   <li>{@code X-Valkeyry-Event}, {@code X-Valkeyry-Tenant}, {@code X-Valkeyry-Request-Id} for routing.</li>
 *   <li>Retries with exponential backoff on transient failures (5xx, IO). 4xx fails fast.</li>
 *   <li><strong>Never</strong> propagates errors — the original audit write must not be undone if SIEM
 *       is down. Failed deliveries log {@code WARN} for operators to alert on.</li>
 * </ul>
 */
@Component
@Configuration
@EnableConfigurationProperties(AuditWebhookProperties.class)
public class AuditWebhookPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditWebhookPublisher.class);
    private static final String SIG_HEADER = "X-Valkeyry-Signature";
    private static final String EVENT_HEADER = "X-Valkeyry-Event";
    private static final String TENANT_HEADER = "X-Valkeyry-Tenant";
    private static final String REQID_HEADER = "X-Valkeyry-Request-Id";

    private final AuditWebhookProperties props;
    private final ObjectMapper mapper;
    private final WebClient webClient;

    public AuditWebhookPublisher(AuditWebhookProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        HttpClient hc = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, props.getTimeoutMs()));
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(hc))
                .build();
    }

    /**
     * Fan-out an audit event to all configured URLs.
     * Returns {@link Mono#empty()} when disabled or no URLs.
     */
    public Mono<Void> publish(ConfigAuditEntry entry) {
        if (!props.isEnabled() || props.getUrls().isEmpty()) return Mono.empty();
        String body;
        try { body = mapper.writeValueAsString(toPayload(entry)); }
        catch (Exception ex) {
            log.warn("audit-webhook: failed to serialise event {} — {}", entry.getId(), ex.getMessage());
            return Mono.empty();
        }
        String signature = signature(body);
        return Flux.fromIterable(props.getUrls())
                .flatMap(url -> deliver(url, body, signature, entry))
                .then();
    }

    private Mono<Void> deliver(String url, String body, String signature, ConfigAuditEntry entry) {
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIG_HEADER, signature)
                .header(EVENT_HEADER, "config.audit")
                .header(TENANT_HEADER, entry.getTenantId())
                .header(REQID_HEADER, entry.getRequestId() == null ? "" : entry.getRequestId())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(Retry.backoff(props.getMaxRetries(), Duration.ofMillis(props.getInitialBackoffMs()))
                        .filter(this::isRetryable))
                .doOnError(ex -> log.warn("audit-webhook: failed delivery to {} for event {} — {}",
                        url, entry.getId(), ex.toString()))
                .onErrorResume(ex -> Mono.empty())
                .then();
    }

    /** 5xx + IO are retryable; 4xx fails fast (caller is broken, no point retrying). */
    private boolean isRetryable(Throwable t) {
        if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException r) {
            return r.getStatusCode().is5xxServerError();
        }
        return true;
    }

    /** Computes {@code sha256=&lt;lowercase-hex&gt;}. Falls back to empty if no secret. */
    private String signature(String body) {
        if (props.getSecret().isBlank()) return "sha256=unsigned";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "sha256=" + sb;
        } catch (Exception ex) {
            log.warn("audit-webhook: HMAC failure — {}", ex.getMessage());
            return "sha256=error";
        }
    }

    /**
     * Builds the JSON payload — fields ordered to match the audit ledger model so SIEM
     * parsers can use a single schema. We embed the raw before/after JSON unparsed.
     */
    private Map<String, Object> toPayload(ConfigAuditEntry e) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", e.getId().toString());
        out.put("tenantId", e.getTenantId());
        out.put("tableName", e.getTableName());
        out.put("operation", e.getOperation());
        out.put("recordKey", e.getRecordKey());
        out.put("beforeValue", parseOrNull(e.getBeforeValue() == null ? null : e.getBeforeValue().asString()));
        out.put("afterValue",  parseOrNull(e.getAfterValue()  == null ? null : e.getAfterValue().asString()));
        out.put("actor", e.getActor());
        out.put("actorTrack", e.getActorTrack());
        out.put("changedAt", e.getChangedAt() == null ? null : e.getChangedAt().toString());
        out.put("requestId", e.getRequestId());
        return out;
    }

    private Object parseOrNull(String json) {
        if (json == null) return null;
        try { return mapper.readTree(json); }
        catch (Exception ex) { return json; }
    }
}
