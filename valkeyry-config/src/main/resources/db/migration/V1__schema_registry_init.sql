-- ============================================================================
-- Valkeyry Config :: Headless Schema Registry
-- V1 — initial bootstrap: registry, entry, audit & indices.
-- ============================================================================

CREATE TABLE IF NOT EXISTS virtual_table_registry (
    id                 UUID         PRIMARY KEY,
    tenant_id          VARCHAR(128) NOT NULL,
    table_name         VARCHAR(255) NOT NULL,
    schema_definition  JSONB        NOT NULL,
    config_version     BIGINT       NOT NULL DEFAULT 1,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(255) NOT NULL
);

-- Exactly one active row per (tenant, table)
CREATE UNIQUE INDEX IF NOT EXISTS uq_registry_active
    ON virtual_table_registry (tenant_id, table_name)
    WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS ix_registry_tenant_history
    ON virtual_table_registry (tenant_id, table_name, config_version DESC);

CREATE TABLE IF NOT EXISTS virtual_table_entry (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(128) NOT NULL,
    table_name   VARCHAR(255) NOT NULL,
    record_key   VARCHAR(512) NOT NULL,
    payload_hash CHAR(64)     NOT NULL,
    version      BIGINT       NOT NULL,
    is_latest    BOOLEAN      NOT NULL DEFAULT TRUE,
    data         JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(255) NOT NULL
);

-- Strictly one "current head" per logical record.
CREATE UNIQUE INDEX IF NOT EXISTS uq_entry_latest
    ON virtual_table_entry (tenant_id, table_name, record_key)
    WHERE is_latest = TRUE;

CREATE INDEX IF NOT EXISTS ix_entry_history
    ON virtual_table_entry (tenant_id, table_name, record_key, version DESC);

-- JSONB GIN index powers @> containment search.
CREATE INDEX IF NOT EXISTS ix_entry_data_gin
    ON virtual_table_entry USING GIN (data jsonb_path_ops);

CREATE INDEX IF NOT EXISTS ix_entry_payload_hash
    ON virtual_table_entry (tenant_id, table_name, payload_hash);
