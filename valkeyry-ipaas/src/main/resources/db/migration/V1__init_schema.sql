-- ============================================================================
-- Valkeyry iPaaS Metadata Schema
-- tenantId / projectId are user-facing alphanumeric slugs (^[A-Za-z0-9_-]{1,200}$).
-- All other primary keys remain UUID for internal stability.
-- ============================================================================

CREATE TABLE IF NOT EXISTS tenants (
    id            VARCHAR(200) PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{1,200}$'),
    name          VARCHAR(128) NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS projects (
    id              VARCHAR(200) PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{1,200}$'),
    tenant_id       VARCHAR(200) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(128) NOT NULL,
    retention_days  INTEGER NOT NULL DEFAULT 30,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS queue_assets (
    id                UUID PRIMARY KEY,
    tenant_id         VARCHAR(200) NOT NULL,
    project_id        VARCHAR(200) NOT NULL,
    destination_name  VARCHAR(256) NOT NULL,
    broker_type       VARCHAR(32)  NOT NULL,
    processing_mode   VARCHAR(16)  NOT NULL,
    provisioning_mode VARCHAR(32)  NOT NULL,
    ttl_seconds       INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, destination_name)
);

CREATE TABLE IF NOT EXISTS consumer_configurations (
    id                   UUID PRIMARY KEY,
    tenant_id            VARCHAR(200) NOT NULL,
    project_id           VARCHAR(200) NOT NULL,
    consumer_name        VARCHAR(128) NOT NULL,
    source_destination   VARCHAR(256) NOT NULL,
    target_webhook_url   VARCHAR(1024) NOT NULL,
    field_mappings_json  TEXT NOT NULL DEFAULT '{}',
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, consumer_name)
);

CREATE TABLE IF NOT EXISTS storage_configurations (
    id                 UUID PRIMARY KEY,
    tenant_id          VARCHAR(200) NOT NULL,
    project_id         VARCHAR(200) NOT NULL,
    name               VARCHAR(128) NOT NULL,
    provider_type      VARCHAR(32)  NOT NULL,
    bucket_or_container VARCHAR(256) NOT NULL,
    endpoint_url       VARCHAR(512),
    region             VARCHAR(64),
    vault_secret_path  VARCHAR(512) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, name)
);

CREATE TABLE IF NOT EXISTS topology_configs (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(200) NOT NULL,
    project_id    VARCHAR(200) NOT NULL,
    topology_name VARCHAR(128) NOT NULL,
    topology_type VARCHAR(32)  NOT NULL,
    manifest_yaml TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, topology_name)
);

CREATE TABLE IF NOT EXISTS field_mappings (
    id           UUID PRIMARY KEY,
    consumer_id  UUID NOT NULL REFERENCES consumer_configurations(id) ON DELETE CASCADE,
    target_field VARCHAR(256) NOT NULL,
    source_path  VARCHAR(512) NOT NULL
);

CREATE TABLE IF NOT EXISTS user_access_policies (
    id          UUID PRIMARY KEY,
    subject     VARCHAR(256) NOT NULL,
    tenant_id   VARCHAR(200) NOT NULL,
    project_id  VARCHAR(200) NOT NULL,
    privilege   VARCHAR(32)  NOT NULL,
    UNIQUE (subject, tenant_id, project_id, privilege)
);

CREATE TABLE IF NOT EXISTS message_logs (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(200) NOT NULL,
    project_id  VARCHAR(200) NOT NULL,
    destination VARCHAR(256) NOT NULL,
    trace_id    VARCHAR(64),
    status      VARCHAR(32) NOT NULL,
    payload     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_msg_logs_created     ON message_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_msg_logs_tenant_proj ON message_logs(tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_queue_assets_tp      ON queue_assets(tenant_id, project_id);
