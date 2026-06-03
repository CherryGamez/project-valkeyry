package io.valkeyry.config.domain.admin;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Admin-managed tenant directory. The {@code id} column is a human-readable slug
 * (matching the {@code tenant_id} used throughout the registry), not a UUID.
 */
@Table("admin_tenant")
public class AdminTenant implements Persistable<String> {

    @Id @Column("id")        private String id;
    @Column("name")          private String name;
    @Column("description")   private String description;
    @Column("enabled")       private boolean enabled = true;
    @Column("created_at")    private Instant createdAt;
    @Column("created_by")    private String createdBy;

    @Transient private boolean isNew = true;

    public AdminTenant() {}

    @Override public String getId() { return id; }                public void setId(String id) { this.id = id; }
    public String getName() { return name; }                       public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }         public void setDescription(String v) { this.description = v; }
    public boolean isEnabled() { return enabled; }                 public void setEnabled(boolean v) { this.enabled = v; }
    public Instant getCreatedAt() { return createdAt; }            public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getCreatedBy() { return createdBy; }             public void setCreatedBy(String v) { this.createdBy = v; }

    @Override public boolean isNew() { return isNew; }
    public void markPersisted() { this.isNew = false; }
}
