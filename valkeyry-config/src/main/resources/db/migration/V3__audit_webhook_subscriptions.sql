-- ============================================================================
-- V3 — dynamic audit webhook subscriptions (managed via the Admin GUI).
--
-- The static, env-var driven URLs in AuditWebhookProperties remain supported;
-- this table layers DB-managed subscriptions on top so operators can add /
-- remove fan-out targets at runtime without redeploying the registry.
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_webhook_subscription (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(128) NOT NULL,
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(512),
    description VARCHAR(512),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_webhook_sub_tenant
    ON audit_webhook_subscription (tenant_id, enabled, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_sub_tenant_url
    ON audit_webhook_subscription (tenant_id, url);
