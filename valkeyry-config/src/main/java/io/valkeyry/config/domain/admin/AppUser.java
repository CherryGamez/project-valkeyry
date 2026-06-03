package io.valkeyry.config.domain.admin;

import io.valkeyry.config.domain.UuidEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-managed application user (Camunda-Identity style).
 *
 * <p>{@code passwordHash} is a BCrypt hash for {@code source = LOCAL} users; nullable for
 * SSO/LDAP-only users that are merely tracked here for entitlement editing.</p>
 */
@Table("app_user")
public class AppUser implements UuidEntity {

    @Id @Column("id")          private UUID id;
    @Column("username")        private String username;
    @Column("display_name")    private String displayName;
    @Column("email")           private String email;
    @Column("password_hash")   private String passwordHash;
    /** "reader" | "writer" | "admin". */
    @Column("role")            private String role;
    /** "LOCAL" | "LDAP" | "SSO". */
    @Column("source")          private String source;
    @Column("enabled")         private boolean enabled;
    @Column("created_at")      private Instant createdAt;
    @Column("created_by")      private String createdBy;

    @Transient private boolean isNew = true;

    public AppUser() {}

    @Override public UUID getId() { return id; }                          public void setId(UUID id) { this.id = id; }
    public String getUsername() { return username; }                      public void setUsername(String v) { this.username = v; }
    public String getDisplayName() { return displayName; }                public void setDisplayName(String v) { this.displayName = v; }
    public String getEmail() { return email; }                            public void setEmail(String v) { this.email = v; }
    public String getPasswordHash() { return passwordHash; }              public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getRole() { return role; }                              public void setRole(String v) { this.role = v; }
    public String getSource() { return source; }                          public void setSource(String v) { this.source = v; }
    public boolean isEnabled() { return enabled; }                        public void setEnabled(boolean v) { this.enabled = v; }
    public Instant getCreatedAt() { return createdAt; }                   public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getCreatedBy() { return createdBy; }                    public void setCreatedBy(String v) { this.createdBy = v; }

    @Override public boolean isNew() { return isNew; }
    @Override public void markPersisted() { this.isNew = false; }
}
