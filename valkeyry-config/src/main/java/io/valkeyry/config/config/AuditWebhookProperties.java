package io.valkeyry.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;

/**
 * Bind for {@code valkeyry.audit.webhook.*} — outbound SIEM fan-out config.
 *
 * <p>Every audit event written to the database is also POSTed to each configured URL with an
 * HMAC-SHA256 signature in the {@code X-Valkeyry-Signature} header. Disabled by default.</p>
 */
@ConfigurationProperties(prefix = "valkeyry.audit.webhook")
public class AuditWebhookProperties {

    private boolean enabled = false;
    private List<String> urls = List.of();
    private String secret = "";
    private long timeoutMs = 5000;
    private int maxRetries = 3;
    private long initialBackoffMs = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public List<String> getUrls() { return Collections.unmodifiableList(urls); }
    public void setUrls(List<String> urls) { this.urls = urls == null ? List.of() : urls; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret == null ? "" : secret; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long v) { this.timeoutMs = v; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = v; }
    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long v) { this.initialBackoffMs = v; }
}
