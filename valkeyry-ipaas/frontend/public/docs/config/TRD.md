# Technical Requirements Document — `valkeyry-config`

**Version:** 1.0.0-SNAPSHOT
**Module:** `io.valkeyry:valkeyry-config`
**Companion docs:** [`PRD.md`](./PRD.md) · [`App_Flow.md`](./App_Flow.md)

---

## 1. Architecture Summary

```
┌──────────────────────────────┐
│  Humans / CI / curl          │
└──────────────┬───────────────┘
               │ OIDC Bearer · LDAP Basic · X-API-Key
               ▼
┌──────────────────────────────────────────────────────────┐
│                  valkeyry-config                          │
│  Netty (WebFlux) + Spring Security 6                      │
│   ├─ TenantAccessGuard  (single normalised authority)     │
│   ├─ JsonSchemaValidator (Draft 2020-12)                  │
│   ├─ PayloadFingerprint  (canonical SHA-256, shared)      │
│   ├─ IdempotencyGuard    (hash dedup)                     │
│   └─ AuditEventPublisher → WebhookFanOut (HMAC)           │
│                                                            │
│  R2DBC (runtime) ────────────────► PostgreSQL 14+         │
│  JDBC  (boot-only) ── Flyway ────► (V1…V4 migrations)     │
└──────────────────────────────────────────────────────────┘
```

### Tech stack
| Layer | Choice | Notes |
| --- | --- | --- |
| Runtime | **JDK 21** (Adoptium Temurin) | `<release>21</release>` in parent POM |
| HTTP | **Spring Boot 3 / WebFlux / Netty** | Single-thread-per-core, non-blocking |
| Security | **Spring Security 6 (reactive)** | OIDC resource-server + custom managers |
| DB driver (runtime) | **R2DBC PostgreSQL** | Streaming, back-pressure-aware |
| DB driver (boot) | **JDBC** | Flyway needs blocking driver to apply DDL once |
| Migration | **Flyway** | V1=schema, V2=audit, V3=webhooks, V4=admin users |
| Schema validation | **com.networknt:json-schema-validator** | Draft 2020-12 |
| Templating (GUI) | **HTMX 1.9 + Mustache 4** | Static HTML, hand-written DOM swaps |
| CSS | **Tailwind via CDN** | iOS-inspired palette, light theme |
| Build | **Maven 3.8.7+** | Reactor parent `io.valkeyry:valkeyry-ecosystem-parent` |
| Container | **Multi-stage Dockerfile** | Eclipse Temurin 21 JRE base |

## 2. Component Catalogue

### 2.1 Controllers (`io.valkeyry.config.api`)
- `VirtualTableController` — declare / list / get virtual tables.
- `EntryController` (within `VirtualTableController`) — ingest, browse, history, search.
- `AuditController` — paged audit ledger reads.
- `AuditRollbackController` — promote a historical version to head.
- `WebhookSubscriptionController` — CRUD on fan-out subscribers.
- `AdminController` — tenant + user + role management (admin authority required).
- `AuthController` — local-user login (issues short-lived JWT) for the HTMX GUI.
- `ToolsController` — backs `/tools.html` schema sandbox and recipe generator.
- `UnifiedQueryController` — JSONB containment search.
- `ApiExceptionHandler` — single source of truth for error → JSON mapping.
- `RequestMdcFilter` — populates MDC for structured logs (`traceId`, `tenantId`, `actor`).

### 2.2 Domain / Services
- `JsonSchemaValidator` — Draft 2020-12 validator cache, keyed by `(tenant,table,version)`.
- `PayloadFingerprint` — canonical-JSON SHA-256 (shared verbatim with `plugin-core`).
- `IdempotencyGuard` — `(tenant,table,recordKey,payloadHash)` head lookup before insert.
- `AuditEventPublisher` — emits ledger rows + fans out to webhooks.
- `WebhookDispatcher` — HMAC-SHA256 signed `POST`, retry/backoff, dead-letter on cap.

### 2.3 Security tracks (`io.valkeyry.config.security`)
- `OidcReactiveAuthorityConverter` — maps `valkeyry.tenants` claim → `SCOPE_tenant:<id>`.
- `LdapBasicAuthenticationManager` / `LdapBasicAuthenticationConverter` — Basic auth track.
- `ApiKeyAuthenticationManager` — `X-API-Key` mapped from `VALKEYRY_API_KEYS`.
- `TenantAccessGuard` — final reactive gate: extracts `{tenantId}` from path, requires
  `SCOPE_tenant:<id>` *and* (for write paths) writer/admin authority.

## 3. Data Model (PostgreSQL)

```
virtual_table_registry            virtual_table_entry
─────────────────────────         ─────────────────────────
id                UUID  PK        id            UUID  PK
tenant_id         VARCHAR         tenant_id     VARCHAR
table_name        VARCHAR         table_name    VARCHAR
schema_definition JSONB           record_key    VARCHAR
config_version    BIGINT          payload_hash  CHAR(64)
is_active         BOOLEAN         version       BIGINT
created_at        TIMESTAMPTZ     is_latest     BOOLEAN
created_by        VARCHAR         data          JSONB
                                  created_at    TIMESTAMPTZ
                                  created_by    VARCHAR

Indexes
─ uq_registry_active  (tenant, table) WHERE is_active
─ uq_entry_latest     (tenant, table, record_key) WHERE is_latest
─ ix_entry_history    (tenant, table, record_key, version DESC)
─ ix_entry_data_gin   USING GIN (data jsonb_path_ops)        ← powers @>
─ ix_entry_payload_hash (tenant, table, payload_hash)         ← idempotency lookup

audit_event                       audit_webhook_subscription
─────────────────                 ──────────────────────────
id          UUID PK               id          UUID PK
tenant_id   VARCHAR               tenant_id   VARCHAR
table_name  VARCHAR               url         TEXT
record_key  VARCHAR NULL          description TEXT
operation   VARCHAR               secret_override TEXT NULL
actor       VARCHAR               enabled     BOOLEAN
before_data JSONB NULL            created_at  TIMESTAMPTZ
after_data  JSONB NULL
created_at  TIMESTAMPTZ
```

Migrations (`src/main/resources/db/migration/`):
- `V1__schema_registry_init.sql` — registry + entry + indexes
- `V2__audit_log.sql` — audit ledger
- `V3__audit_webhook_subscriptions.sql` — fan-out table
- `V4__admin_users_and_tenants.sql` — local users, tenant catalogue, role mapping

## 4. API Surface

| Method | Path | Auth | Writer-only |
| --- | --- | --- | --- |
| GET    | `/api/v1/tenants/{tenantId}/tables` | yes | no |
| POST   | `/api/v1/tenants/{tenantId}/tables` | yes | **yes** |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}` | yes | no |
| POST   | `/api/v1/tenants/{tenantId}/tables/{name}/entries` | yes | **yes** |
| POST   | `/api/v1/tenants/{tenantId}/tables/{name}/entries:batch` | yes | **yes** |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}/entries` | yes | no |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}/entries/{recordKey}` | yes | no |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}/entries/{recordKey}/history` | yes | no |
| POST   | `/api/v1/tenants/{tenantId}/tables/{name}/search` | yes | no |
| GET    | `/api/v1/tenants/{tenantId}/audit` | yes | no |
| POST   | `/api/v1/tenants/{tenantId}/audit/rollback` | yes | **yes** |
| GET/POST/DELETE | `/api/v1/tenants/{tenantId}/webhooks…` | yes (admin) | **yes** |
| `/api/admin/**` | local user / tenant CRUD | OIDC writer + admin | **yes** |

### Error envelope
```json
{
  "timestamp": "2026-02-12T08:31:04.012Z",
  "status": 409,
  "error":  "DUPLICATE_PAYLOAD",
  "message":"Payload hash matches current head; no new version created.",
  "traceId":"4f7b…"
}
```

## 5. Configuration

| Env var | Default | Purpose |
| --- | --- | --- |
| `VALKEYRY_CONFIG_PORT` | `8081` | HTTP listen port |
| `VALKEYRY_CONFIG_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/valkeyry_config` | Runtime DB |
| `VALKEYRY_CONFIG_JDBC_URL` | `jdbc:postgresql://localhost:5432/valkeyry_config` | Flyway only |
| `VALKEYRY_CONFIG_DB_USER` / `_PASS` | `valkeyry/valkeyry` | DB credentials |
| `VALKEYRY_OIDC_ISSUER` | `http://localhost:8079/default` | OIDC issuer-uri |
| `VALKEYRY_LDAP_URL` | `ldap://localhost:1389` | LDAP server URL |
| `VALKEYRY_LDAP_BASE_DN` | `dc=valkeyry,dc=local` | Search base |
| `VALKEYRY_API_KEYS` | _empty_ | `key1:tenantA,tenantB;key2:tenantC` |
| `VALKEYRY_WEBHOOK_SECRET` | _required if webhooks_ | Global HMAC-SHA256 key |

## 6. Build, Test, Run

```bash
# From repo root
mvn -B -ntp -pl valkeyry-config -am -DskipTests clean install
mvn -pl valkeyry-config spring-boot:run         # :8081

# Docker
docker build -t valkeyry/config:dev -f valkeyry-config/Dockerfile valkeyry-config
docker compose -f valkeyry-config/docker-compose.dev.yml up

# Smoke
curl -H "X-API-Key: plugin-test-key" \
     http://localhost:8081/api/v1/tenants/demo-tenant/tables
```

### Test layers
- **Unit:** plain JUnit 5 + Reactor `StepVerifier` for service code.
- **Slice:** `@WebFluxTest` for controllers, `@DataR2dbcTest` for repositories.
- **Integration:** Testcontainers Postgres + Flyway, full HTTP round-trips.
- **Plugin parity:** `PayloadFingerprintParityTest` cross-checks the JVM implementation
  against the `plugin-core` artifact byte-for-byte.

## 7. Observability

- **Logs:** Structured JSON via Logback, MDC carries `traceId`, `tenantId`, `actor`.
- **Metrics:** Micrometer → `/actuator/prometheus`. Custom counters:
  `valkeyry.config.ingest.{accepted,duplicate,rejected}`,
  `valkeyry.config.validation.errors{table=…}`,
  `valkeyry.config.webhook.delivery.{success,fail}`.
- **Tracing:** Spring Boot 3 Micrometer Tracing + Brave / OTLP exporter.
- **Health:** `/actuator/health` (db, oidc, flyway), readiness gates on Flyway completion.

## 8. Security Threat Model (abridged)

| Threat | Mitigation |
| --- | --- |
| Cross-tenant data leak | `TenantAccessGuard` mandatory on every route; integration tests assert 403 |
| Replay of stale config | Idempotency guard via canonical SHA-256 |
| Webhook spoofing | HMAC-SHA256 signature in `X-Valkeyry-Signature` |
| Mass-validation DoS | Per-request schema validator with bounded cache; 413 on large bodies |
| SQL injection via JSONB | All queries parameterised via R2DBC; JSONB cast in SQL |
| Auth token replay | Bearer JWT exp ≤ 1h; LDAP creds via TLS only; rotating API-keys |

## 9. Deployment

- **Helm chart:** `deploy/helm/valkeyry-config/` — values for replicas, ingress, OIDC issuer.
- **Raw manifests:** `deploy/k8s/` for demo clusters.
- **Stateful dependency:** PostgreSQL (managed or in-cluster). Helm chart ships a dev
  `bitnami/postgresql` subchart toggle.
- **Sidecar:** none. The container is self-contained; Flyway runs on startup.

---

*This document tracks the implementation in `valkeyry-config/`. Update on every breaking
change.*
