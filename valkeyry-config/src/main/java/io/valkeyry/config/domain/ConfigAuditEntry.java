package io.valkeyry.config.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, append-only audit record.
 *
 * <p>Every config-changing call (table declare, schema revision, record ingest, even
 * idempotency-skipped writes) deposits one row here. The pair {@code beforeValue} /
 * {@code afterValue} carries the full payload diff so any UI or compliance tooling can render
 * a side-by-side view without re-querying the registry.</p>
 */
@Table("config_audit_log")
public class ConfigAuditEntry implements UuidEntity {

    @Id @Column("id")             private UUID id;
    @Column("tenant_id")          private String tenantId;
    @Column("table_name")         private String tableName;
    @Column("operation")          private String operation;
    @Column("record_key")         private String recordKey;
    @Column("before_value")       private Json beforeValue;
    @Column("after_value")        private Json afterValue;
    @Column("actor")              private String actor;
    @Column("actor_track")        private String actorTrack;
    @Column("changed_at")         private Instant changedAt;
    @Column("request_id")         private String requestId;

    @Transient
    private boolean isNew = true;

    public ConfigAuditEntry() {}

    @Override public UUID getId() { return id; }             public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }         public void setTenantId(String v) { this.tenantId = v; }
    public String getTableName() { return tableName; }       public void setTableName(String v) { this.tableName = v; }
    public String getOperation() { return operation; }       public void setOperation(String v) { this.operation = v; }
    public String getRecordKey() { return recordKey; }       public void setRecordKey(String v) { this.recordKey = v; }
    public Json getBeforeValue() { return beforeValue; }     public void setBeforeValue(Json v) { this.beforeValue = v; }
    public Json getAfterValue() { return afterValue; }       public void setAfterValue(Json v) { this.afterValue = v; }
    public String getActor() { return actor; }               public void setActor(String v) { this.actor = v; }
    public String getActorTrack() { return actorTrack; }     public void setActorTrack(String v) { this.actorTrack = v; }
    public Instant getChangedAt() { return changedAt; }      public void setChangedAt(Instant v) { this.changedAt = v; }
    public String getRequestId() { return requestId; }       public void setRequestId(String v) { this.requestId = v; }

    @Override public boolean isNew() { return isNew; }
    @Override public void markPersisted() { this.isNew = false; }
}
