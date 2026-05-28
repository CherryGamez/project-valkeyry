package io.valkeyry.ipaas.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit checks on the workspace path parser used by the Prometheus tag
 * enrichment. We don't boot Spring here — the parser is static.
 */
class MetricsTagConfigTest {

    @Test
    void parsesMatchingWorkspacePath() {
        var ws = MetricsTagConfig.parse("/api/v1/acme-corp/payments-prod/queues/orders");
        assertThat(ws.tenant()).isEqualTo("acme-corp");
        assertThat(ws.project()).isEqualTo("payments-prod");
    }

    @Test
    void returnsNoneSentinelForUnscopedPath() {
        var ws = MetricsTagConfig.parse("/actuator/prometheus");
        assertThat(ws.tenant()).isEqualTo(MetricsTagConfig.NONE);
        assertThat(ws.project()).isEqualTo(MetricsTagConfig.NONE);
    }

    @Test
    void handlesNullPath() {
        var ws = MetricsTagConfig.parse(null);
        assertThat(ws.tenant()).isEqualTo(MetricsTagConfig.NONE);
        assertThat(ws.project()).isEqualTo(MetricsTagConfig.NONE);
    }

    @Test
    void workspaceTagsFillsBlanks() {
        var kvs = MetricsTagConfig.workspaceTags("", null);
        assertThat(kvs).extracting("key").containsExactlyInAnyOrder("tenant_id", "project_id");
        assertThat(kvs).extracting("value").containsExactly(MetricsTagConfig.NONE, MetricsTagConfig.NONE);
    }

    @Test
    void workspaceTagsRoundtrip() {
        var kvs = MetricsTagConfig.workspaceTags("globex-eu", "billing-dev");
        assertThat(kvs).extracting("value")
                .containsExactlyInAnyOrder("globex-eu", "billing-dev");
    }
}
