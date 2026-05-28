-- ============================================================================
-- V2 — config_audit_log: who changed what, from-value → to-value, when, how
-- ============================================================================

CREATE TABLE IF NOT EXISTS config_audit_log (
    id            UUID         PRIMARY KEY,
    tenant_id     VARCHAR(128) NOT NULL,
    table_name    VARCHAR(255) NOT NULL,
    -- DECLARE_TABLE, REVISE_TABLE, INGEST_RECORD, DEDUP_SKIP
    operation     VARCHAR(32)  NOT NULL,
    record_key    VARCHAR(512),
    before_value  JSONB,                  -- prior schema / prior payload (null on first write)
    after_value   JSONB        NOT NULL,
    actor         VARCHAR(255) NOT NULL,
    -- OIDC, LDAP, API_KEY, ANONYMOUS
    actor_track   VARCHAR(32)  NOT NULL,
    changed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    request_id    VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS ix_audit_tenant_time
    ON config_audit_log (tenant_id, changed_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_tenant_table
    ON config_audit_log (tenant_id, table_name, changed_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_actor
    ON config_audit_log (tenant_id, actor, changed_at DESC);
