package io.valkeyry.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.valkeyry.config.config.AuditWebhookProperties;
import io.valkeyry.config.domain.AuditWebhookSubscription;
import io.valkeyry.config.domain.ConfigAuditEntry;
import io.valkeyry.config.repo.AuditWebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * Pushes every persisted {@link ConfigAuditEntry} to all configured webhook URLs.
 *
 * <p>URL sources are <em>merged</em>:</p>
 * <ul>
 *   <li>Static, env-driven URLs from {@link AuditWebhookProperties} apply to every tenant.</li>
 *   <li>Dynamic {@link AuditWebhookSubscription} rows are scoped to the audit event's tenant.</li>
 * </ul>
 *
 * <p>SIEM-friendly: one {@code POST application/json} per URL per event, HMAC-SHA256 signature,
 * exponential backoff on 5xx/IO; 4xx fails fast; webhook failure never undoes the audit insert.</p>
 */
@Component
@EnableConfigurationProperties(AuditWebhookProperties.class)
public class AuditWebhookPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditWebhookPublisher.class);
    private static final String SIG_HEADER = "X-Valkeyry-Signature";
    private static final String EVENT_HEADER = "X-Valkeyry-Event";
    private static final String TENANT_HEADER = "X-Valkeyry-Tenant";
    private static final String REQID_HEADER = "X-Valkeyry-Request-Id";

    private final AuditWebhookProperties props;
    private final AuditWebhookSubscriptionRepository subscriptions;
    private final ObjectMapper mapper;
    private final WebClient webClient;

    // NOTE: previously this class shipped a public no-arg constructor that set every field to
    // null. With Spring's "ambiguous constructor → pick the no-arg one" fallback for non-
    // annotated beans, the production wiring ended up using that constructor — which then
    // NPE'd on the very first audit event ("ObjectMapper is null"). Audit webhooks have been
    // removed from that ambiguity by deleting the no-arg constructor: there is now a single
    // 3-arg constructor which Spring auto-wires unconditionally. Tests construct the 2-arg
    // overload below.

    @org.springframework.beans.factory.annotation.Autowired
    public AuditWebhookPublisher(AuditWebhookProperties props,
                                 AuditWebhookSubscriptionRepository subscriptions,
                                 ObjectMapper mapper) {
        this.props = props;
        this.subscriptions = subscriptions;
        this.mapper = mapper;
        HttpClient hc = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, props.getTimeoutMs()));
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(hc))
                .build();
    }

    /**
     * Test-friendly constructor — runs without the DB-backed subscription source. Equivalent to
     * the production wiring when no dynamic subscriptions table exists yet. Production code always
     * receives the 3-arg constructor via Spring DI.
     */
    public AuditWebhookPublisher(AuditWebhookProperties props, ObjectMapper mapper) {
        this(props, null, mapper);
    }

    /** Fan-out an audit event to every configured target (static + per-tenant dynamic). */
    public Mono<Void> publish(ConfigAuditEntry entry) {
        final String body;
        try { body = mapper.writeValueAsString(toPayload(entry)); }
        catch (Exception ex) {
            log.warn("audit-webhook: failed to serialise event {} — {}", entry.getId(), ex.getMessage());
            return Mono.empty();
        }
        return resolveTargets(entry.getTenantId())
                .flatMap(target -> deliver(target, body, entry))
                .then();
    }

    /**
     * Build the merged target list: static URLs (global, gated by {@code enabled})
     * + DB subscriptions (tenant-scoped, always honoured when {@code enabled = true} per row).
     */
    private Flux<Target> resolveTargets(String tenantId) {
        Flux<Target> staticTargets = props.isEnabled()
                ? Flux.fromIterable(props.getUrls())
                        .filter(u -> u != null && !u.isBlank())
                        .map(u -> new Target(u, props.getSecret()))
                : Flux.empty();
        Flux<Target> dynamicTargets = (subscriptions == null)
                ? Flux.empty()
                : subscriptions.findEnabledByTenant(tenantId == null ? "" : tenantId)
                    .map(s -> new Target(s.getUrl(),
                            (s.getSecret() != null && !s.getSecret().isBlank()) ? s.getSecret() : props.getSecret()))
                    .onErrorResume(ex -> {
                        log.warn("audit-webhook: failed to read dynamic subscriptions for {} — {}", tenantId, ex.toString());
                        return Flux.empty();
                    });
        // Dedup by URL: dynamic wins (so per-tenant secrets override the global one if duplicated).
        return Flux.concat(dynamicTargets, staticTargets).distinct(Target::url);
    }

    private Mono<Void> deliver(Target target, String body, ConfigAuditEntry entry) {
        String signature = signature(body, target.secret());
        return webClient.post()
                .uri(target.url())
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
                        target.url(), entry.getId(), ex.toString()))
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

    /** Computes {@code sha256=&lt;lowercase-hex&gt;}. Falls back to {@code sha256=unsigned} when no secret. */
    private String signature(String body, String secret) {
        if (secret == null || secret.isBlank()) return "sha256=unsigned";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "sha256=" + sb;
        } catch (Exception ex) {
            log.warn("audit-webhook: HMAC failure — {}", ex.getMessage());
            return "sha256=error";
        }
    }

    /** SIEM-shaped payload. */
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

    /** Tuple of url + the secret used to sign the body for that url. */
    private record Target(String url, String secret) {}
}
