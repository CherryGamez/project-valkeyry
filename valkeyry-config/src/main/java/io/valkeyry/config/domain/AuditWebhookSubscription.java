package io.valkeyry.config.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Dynamically-managed audit webhook target.
 *
 * <p>Mirrors the static URLs declared via {@link io.valkeyry.config.config.AuditWebhookProperties}
 * but is owned by a tenant and editable at runtime through the Admin GUI. The publisher
 * unions both sources on every fan-out.</p>
 */
@Table("audit_webhook_subscription")
public class AuditWebhookSubscription {

    @Id @Column("id")            private UUID id;
    @Column("tenant_id")         private String tenantId;
    @Column("url")               private String url;
    @Column("secret")            private String secret;
    @Column("description")       private String description;
    @Column("enabled")           private boolean enabled;
    @Column("created_at")        private Instant createdAt;
    @Column("created_by")        private String createdBy;

    public AuditWebhookSubscription() {}

    public UUID getId() { return id; }                       public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }         public void setTenantId(String v) { this.tenantId = v; }
    public String getUrl() { return url; }                   public void setUrl(String v) { this.url = v; }
    public String getSecret() { return secret; }             public void setSecret(String v) { this.secret = v; }
    public String getDescription() { return description; }   public void setDescription(String v) { this.description = v; }
    public boolean isEnabled() { return enabled; }           public void setEnabled(boolean v) { this.enabled = v; }
    public Instant getCreatedAt() { return createdAt; }      public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getCreatedBy() { return createdBy; }       public void setCreatedBy(String v) { this.createdBy = v; }
}
