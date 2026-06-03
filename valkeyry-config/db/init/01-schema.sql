-- ============================================================================
-- Valkeyry Config :: Initial Postgres bootstrap
--
-- Postgres' docker-entrypoint-initdb.d runs this once on a fresh volume.
-- It creates the application schema + every table the app expects so a
-- developer can stand up the DB without Flyway running first (e.g. when
-- connecting from a SQL client, or when running tests that bypass the app).
--
-- The same DDL lives — versioned — under src/main/resources/db/migration
-- (V1…V4); both code paths are kept idempotent (CREATE … IF NOT EXISTS) so
-- they're safe to run in any order. When Flyway later starts against this
-- already-initialised database it sees identical objects, baselines, and
-- continues from V5 onwards.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS valkeyry_config AUTHORIZATION postgres;
GRANT ALL ON SCHEMA valkeyry_config TO postgres;

-- Make every subsequent statement land in valkeyry_config so unqualified
-- table references (admin_tenant, app_user, …) resolve correctly. Also pin
-- it as the default search path for the postgres role so R2DBC sessions
-- see the same view without needing ?schema=valkeyry_config in the URL.
SET search_path TO valkeyry_config, public;
ALTER ROLE postgres IN DATABASE valkeyry_config SET search_path TO valkeyry_config, public;

-- ----------------------------------------------------------------------------
-- V1 — schema registry: virtual tables + their per-record payloads
-- ----------------------------------------------------------------------------

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

CREATE UNIQUE INDEX IF NOT EXISTS uq_entry_latest
    ON virtual_table_entry (tenant_id, table_name, record_key)
    WHERE is_latest = TRUE;

CREATE INDEX IF NOT EXISTS ix_entry_history
    ON virtual_table_entry (tenant_id, table_name, record_key, version DESC);

CREATE INDEX IF NOT EXISTS ix_entry_data_gin
    ON virtual_table_entry USING GIN (data jsonb_path_ops);

CREATE INDEX IF NOT EXISTS ix_entry_payload_hash
    ON virtual_table_entry (tenant_id, table_name, payload_hash);

-- ----------------------------------------------------------------------------
-- V2 — append-only audit log
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS config_audit_log (
    id            UUID         PRIMARY KEY,
    tenant_id     VARCHAR(128) NOT NULL,
    table_name    VARCHAR(255) NOT NULL,
    operation     VARCHAR(32)  NOT NULL,    -- DECLARE_TABLE | REVISE_TABLE | INGEST_RECORD | DEDUP_SKIP
    record_key    VARCHAR(512),
    before_value  JSONB,                    -- prior schema / prior payload (null on first write)
    after_value   JSONB        NOT NULL,
    actor         VARCHAR(255) NOT NULL,
    actor_track   VARCHAR(32)  NOT NULL,    -- OIDC | LDAP | API_KEY | ANONYMOUS | LOCAL
    changed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    request_id    VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS ix_audit_tenant_time
    ON config_audit_log (tenant_id, changed_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_tenant_table
    ON config_audit_log (tenant_id, table_name, changed_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_actor
    ON config_audit_log (tenant_id, actor, changed_at DESC);

-- ----------------------------------------------------------------------------
-- V3 — audit-webhook fan-out subscriptions
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_webhook_subscription (
    id          UUID          PRIMARY KEY,
    tenant_id   VARCHAR(128)  NOT NULL,
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(512),
    description VARCHAR(512),
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  VARCHAR(255)  NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_webhook_sub_tenant
    ON audit_webhook_subscription (tenant_id, enabled, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_sub_tenant_url
    ON audit_webhook_subscription (tenant_id, url);

-- ----------------------------------------------------------------------------
-- V4 — admin identity (Camunda-Identity style tenants + users)
-- These are the tables the AdminController CRUDs at /api/v1/admin/*.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS admin_tenant (
    id           VARCHAR(128) PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    description  TEXT         NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS app_user (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(128) NOT NULL UNIQUE,
    display_name  VARCHAR(255) NULL,
    email         VARCHAR(255) NULL,
    password_hash VARCHAR(255) NULL,        -- BCrypt; NULL for SSO/LDAP-only entries
    role          VARCHAR(32)  NOT NULL DEFAULT 'reader',   -- reader | writer | admin
    source        VARCHAR(16)  NOT NULL DEFAULT 'LOCAL',    -- LOCAL | LDAP | SSO
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_app_user_role ON app_user (role);

CREATE TABLE IF NOT EXISTS app_user_tenant (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tenant_id  VARCHAR(128) NOT NULL REFERENCES admin_tenant(id) ON DELETE CASCADE,
    UNIQUE (user_id, tenant_id)
);

CREATE INDEX IF NOT EXISTS ix_app_user_tenant_user   ON app_user_tenant (user_id);
CREATE INDEX IF NOT EXISTS ix_app_user_tenant_tenant ON app_user_tenant (tenant_id);

-- ----------------------------------------------------------------------------
-- Optional seed: a 'demo-tenant' admin tenant so the Admin panel and existing
-- registry data line up out of the box. Safe to re-run.
-- ----------------------------------------------------------------------------

INSERT INTO admin_tenant (id, name, description, enabled, created_by)
VALUES ('demo-tenant', 'Demo Tenant', 'Pre-seeded for local development.', TRUE, 'bootstrap')
ON CONFLICT (id) DO NOTHING;
