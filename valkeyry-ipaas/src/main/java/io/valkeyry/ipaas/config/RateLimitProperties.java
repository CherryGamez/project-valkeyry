package io.valkeyry.ipaas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "valkeyry.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
    private long capacity = 200;
    private long refillTokens = 200;
    private long refillPeriodSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public long getCapacity() { return capacity; }
    public void setCapacity(long v) { this.capacity = v; }
    public long getRefillTokens() { return refillTokens; }
    public void setRefillTokens(long v) { this.refillTokens = v; }
    public long getRefillPeriodSeconds() { return refillPeriodSeconds; }
    public void setRefillPeriodSeconds(long v) { this.refillPeriodSeconds = v; }

    public Duration refillPeriod() { return Duration.ofSeconds(refillPeriodSeconds); }
}
