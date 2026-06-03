package io.valkeyry.config.domain.admin;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Join row between an {@link AppUser} and the tenants they may access. */
@Table("app_user_tenant")
public class AppUserTenant implements Persistable<UUID> {

    @Id @Column("id")     private UUID id;
    @Column("user_id")    private UUID userId;
    @Column("tenant_id")  private String tenantId;

    @Transient private boolean isNew = true;

    public AppUserTenant() {}
    public AppUserTenant(UUID userId, String tenantId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tenantId = tenantId;
    }

    @Override public UUID getId() { return id; }      public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }         public void setUserId(UUID v) { this.userId = v; }
    public String getTenantId() { return tenantId; }   public void setTenantId(String v) { this.tenantId = v; }

    @Override public boolean isNew() { return isNew; }
    public void markPersisted() { this.isNew = false; }
}
