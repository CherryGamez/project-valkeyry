-- ============================================================================
-- Valkeyry Config :: Admin Identity
-- V4 — application-managed tenants & users (Camunda-Identity style).
--
-- These tables are SEPARATE from the registry's per-row `tenant_id` slug — they
-- are the *admin* view (CRUD via /api/v1/admin/*). The slug stored in
-- admin_tenant.id is the same value used as tenant_id everywhere else.
-- ============================================================================

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
    -- BCrypt hash, NULL for SSO/LDAP-only entries.
    password_hash VARCHAR(255) NULL,
    -- reader | writer | admin
    role          VARCHAR(32)  NOT NULL DEFAULT 'reader',
    -- LOCAL | LDAP | SSO  — where the credential check is performed.
    source        VARCHAR(16)  NOT NULL DEFAULT 'LOCAL',
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
