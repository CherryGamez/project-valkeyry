package io.valkeyry.ipaas.metrics;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationConvention;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Optional;

/**
 * Per-project Prometheus tag enrichment for Valkeyry.
 *
 * <p>Three pieces wired here:
 * <ol>
 *   <li>{@link #serviceTagsCustomizer(String)} — common tags ({@code service},
 *       {@code application}) on every metric.</li>
 *   <li>{@link #workspaceObservationConvention()} — the canonical Spring Boot 3.3+
 *       way to add tags to the {@code http.server.requests} histogram. We extract
 *       {@code tenantId}/{@code projectId} from the URI pattern
 *       {@code /api/v1/{tenantId}/{projectId}/**} and emit them as the
 *       low-cardinality KVs {@code tenant_id} + {@code project_id}.</li>
 *   <li>{@link #cardinalityGuard()} — caps tag-value cardinality so a flood of
 *       unique slugs can't blow up Prometheus.</li>
 * </ol>
 *
 * <p>Other code emitting custom counters/gauges should attach
 * {@link #workspaceTags(String, String)} so Grafana panels filter consistently.
 */
@Slf4j
@Configuration
public class MetricsTagConfig {

    /** Sentinel value for metrics emitted outside a workspace context. */
    public static final String NONE = "_none_";

    private static final PathPattern WORKSPACE_PATTERN =
            new PathPatternParser().parse("/api/v1/{tenantId}/{projectId}/**");

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> serviceTagsCustomizer(
            @org.springframework.beans.factory.annotation.Value("${spring.application.name:valkeyry-ipaas}")
            String appName) {
        return registry -> registry.config().commonTags(
                "application", appName,
                "service", "valkeyry-ipaas"
        );
    }

    /**
     * Replaces the default Spring Boot WebFlux observation convention with one that
     * adds {@code tenant_id} + {@code project_id} low-cardinality tags to every
     * {@code http.server.requests} sample.
     */
    @Bean
    public ServerRequestObservationConvention workspaceObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
                KeyValues base = super.getLowCardinalityKeyValues(context);
                String path = context.getCarrier() == null ? null
                        : context.getCarrier().getPath().pathWithinApplication().value();
                Workspace ws = parse(path);
                return base.and(
                        KeyValue.of("tenant_id", ws.tenant),
                        KeyValue.of("project_id", ws.project)
                );
            }
        };
    }

    /**
     * Cardinality guard. Without this, a flaky/malicious client could spam
     * unique workspace slugs and balloon Prometheus.
     */
    @Bean
    public MeterFilter cardinalityGuard() {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "tenant_id", 200,
                MeterFilter.maximumAllowableTags(
                        "http.server.requests", "project_id", 500, MeterFilter.deny()));
    }

    /**
     * Public utility for emitting custom counters / gauges with consistent
     * workspace tags. Example:
     * <pre>{@code
     *   meterRegistry.counter("valkeyry.broker.messages.published",
     *       MetricsTagConfig.workspaceTags(tenantId, projectId)).increment();
     * }</pre>
     */
    public static KeyValues workspaceTags(String tenant, String project) {
        return KeyValues.of(
                KeyValue.of("tenant_id", isBlank(tenant) ? NONE : tenant),
                KeyValue.of("project_id", isBlank(project) ? NONE : project)
        );
    }

    /** Parses a workspace-scoped path; returns the {@link #NONE} sentinels if unmatched. */
    public static Workspace parse(String path) {
        if (isBlank(path)) return Workspace.NONE_WS;
        var parsed = WORKSPACE_PATTERN.matchAndExtract(PathContainer.parsePath(path));
        if (parsed == null) return Workspace.NONE_WS;
        String t = Optional.ofNullable(parsed.getUriVariables().get("tenantId")).orElse(NONE);
        String p = Optional.ofNullable(parsed.getUriVariables().get("projectId")).orElse(NONE);
        return new Workspace(t, p);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    public record Workspace(String tenant, String project) {
        static final Workspace NONE_WS = new Workspace(NONE, NONE);
    }
}
