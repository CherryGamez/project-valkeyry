-- ============================================================================
-- Valkeyry iPaaS Metadata Schema
-- All tables carry (tenant_id, project_id) for strict hierarchical isolation.
-- ============================================================================

CREATE TABLE IF NOT EXISTS tenants (
    id            UUID PRIMARY KEY,
    name          VARCHAR(128) NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS projects (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(128) NOT NULL,
    retention_days  INTEGER NOT NULL DEFAULT 30,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS queue_assets (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    project_id        UUID NOT NULL,
    destination_name  VARCHAR(256) NOT NULL,
    broker_type       VARCHAR(32)  NOT NULL,        -- RABBITMQ|KAFKA|ACTIVEMQ
    processing_mode   VARCHAR(16)  NOT NULL,        -- QUEUE|STREAMING
    provisioning_mode VARCHAR(32)  NOT NULL,        -- CATALOG|LAZY_PROVISIONED
    ttl_seconds       INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, destination_name)
);

CREATE TABLE IF NOT EXISTS consumer_configurations (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    project_id           UUID NOT NULL,
    consumer_name        VARCHAR(128) NOT NULL,
    source_destination   VARCHAR(256) NOT NULL,
    target_webhook_url   VARCHAR(1024) NOT NULL,
    field_mappings_json  TEXT NOT NULL DEFAULT '{}', -- JSON map<targetField, jsonPath>
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, consumer_name)
);

CREATE TABLE IF NOT EXISTS storage_configurations (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    project_id         UUID NOT NULL,
    name               VARCHAR(128) NOT NULL,
    provider_type      VARCHAR(32)  NOT NULL,   -- S3_COMPATIBLE | AZURE_BLOB
    bucket_or_container VARCHAR(256) NOT NULL,
    endpoint_url       VARCHAR(512),
    region             VARCHAR(64),
    vault_secret_path  VARCHAR(512) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, project_id, name)
);

CREATE TABLE IF NOT EXISTS topology_configs (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    project_id    UUID NOT NULL,
    topology_name VARCHAR(128) NOT NULL,
    topology_type VARCHAR(32)  NOT NULL,     -- ONE_TO_MANY | MANY_TO_ONE | MANY_TO_MANY
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
    subject     VARCHAR(256) NOT NULL,            -- JWT subject (sub claim)
    tenant_id   UUID NOT NULL,
    project_id  UUID NOT NULL,
    privilege   VARCHAR(32) NOT NULL,             -- PROJECT_READ | PROJECT_WRITE
    UNIQUE (subject, tenant_id, project_id, privilege)
);

CREATE TABLE IF NOT EXISTS message_logs (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    project_id  UUID NOT NULL,
    destination VARCHAR(256) NOT NULL,
    trace_id    VARCHAR(64),
    status      VARCHAR(32) NOT NULL,             -- INGESTED | DELIVERED | DLQ | TRANSFORMED
    payload     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_msg_logs_created ON message_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_msg_logs_tenant_proj ON message_logs(tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_queue_assets_tenant_proj ON queue_assets(tenant_id, project_id);
