package io.valkeyry.config.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable, versioned data record inside a virtual table.
 *
 * <p>Rows are append-only. A logical record (identified by {@code recordKey}) gains a new row
 * every time its payload changes. The Idempotency Guard uses {@code payloadHash} (deterministic
 * canonical-JSON SHA-256) to short-circuit duplicate writes. The current head of a logical record
 * is the row whose {@code isLatest = true} — there is exactly one such row per {@code (tenantId,
 * tableName, recordKey)} tuple.</p>
 */
@Table("virtual_table_entry")
public class VirtualTableEntry implements UuidEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("tenant_id")
    private String tenantId;

    @Column("table_name")
    private String tableName;

    /** Logical key for a record across versions (e.g. the customer id, the product SKU…). */
    @Column("record_key")
    private String recordKey;

    /** Canonical-JSON SHA-256 fingerprint — used by the Idempotency Guard. */
    @Column("payload_hash")
    private String payloadHash;

    /** Sequential version per {@code (tenant,table,recordKey)} — starts at 1. */
    @Column("version")
    private long version;

    /** True iff this row is the current head of its logical record. */
    @Column("is_latest")
    private boolean latest;

    /** The PostgreSQL JSONB payload itself. */
    @Column("data")
    private Json data;

    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private String createdBy;

    @Transient
    private boolean isNew = true;

    public VirtualTableEntry() {}

    // ------------- accessors -------------
    @Override public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getRecordKey() { return recordKey; }
    public void setRecordKey(String recordKey) { this.recordKey = recordKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public boolean isLatest() { return latest; }
    public void setLatest(boolean latest) { this.latest = latest; }
    public Json getData() { return data; }
    public void setData(Json data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    @Override public boolean isNew() { return isNew; }
    @Override public void markPersisted() { this.isNew = false; }
}
