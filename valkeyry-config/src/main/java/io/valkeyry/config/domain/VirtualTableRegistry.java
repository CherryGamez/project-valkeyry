package io.valkeyry.config.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Registry of all virtual tables known to a tenant.
 *
 * <p>Each row binds a {@code (tenantId, tableName)} pair to a structural JSON Schema and a
 * monotonically increasing {@code configVersion}. There is exactly one active row per pair;
 * historical schema revisions live in the same table with a previous {@code configVersion}.</p>
 *
 * <p>Crucially, declaring a virtual table is purely a metadata insert — the Config engine
 * does <strong>not</strong> execute {@code CREATE TABLE} at runtime. Data lives in the
 * generic {@link VirtualTableEntry} table under a JSONB column.</p>
 */
@Table("virtual_table_registry")
public class VirtualTableRegistry implements UuidEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("tenant_id")
    private String tenantId;

    @Column("table_name")
    private String tableName;

    /** JSON Schema (Draft 2020-12) used to validate every entry payload. */
    @Column("schema_definition")
    private Json schemaDefinition;

    @Column("config_version")
    private long configVersion;

    @Column("is_active")
    private boolean active;

    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private String createdBy;

    @Transient
    private boolean isNew = true;

    public VirtualTableRegistry() {}

    // ------------- accessors -------------
    @Override public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public Json getSchemaDefinition() { return schemaDefinition; }
    public void setSchemaDefinition(Json schemaDefinition) { this.schemaDefinition = schemaDefinition; }
    public long getConfigVersion() { return configVersion; }
    public void setConfigVersion(long configVersion) { this.configVersion = configVersion; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    @Override public boolean isNew() { return isNew; }
    @Override public void markPersisted() { this.isNew = false; }
}
