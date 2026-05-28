# `valkeyry-config` — Headless Schema Registry Engine

A reactive Spring Boot WebFlux service that stores **virtual tables** as JSONB records in
PostgreSQL — no runtime DDL, full schema versioning, deterministic idempotency, and a complete
version trail per logical record.

## Endpoints

| Method | Path                                                                | Purpose                              |
|--------|---------------------------------------------------------------------|--------------------------------------|
| GET    | `/api/v1/tenants/{tenantId}/tables`                                 | List active virtual tables           |
| POST   | `/api/v1/tenants/{tenantId}/tables`                                 | Declare or re-version a virtual table ⚠ writer-role only |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}`                          | Get the active schema row            |
| POST   | `/api/v1/tenants/{tenantId}/tables/{name}/entries`                  | Ingest one record ⚠ writer-role only |
| POST   | `/api/v1/tenants/{tenantId}/tables/{name}/entries:batch`            | Ingest many records (used by plugin) ⚠ writer-role only |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}/entries`                  | Browse latest heads (paged)          |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}/entries/{recordKey}`      | Get the current head                 |
| GET    | `/api/v1/tenants/{tenantId}/tables/{name}/entries/{recordKey}/history` | Full version trail                 |
| POST   | `/api/v1/tenants/{tenantId}/tables/{name}/search`                   | JSONB containment search             |
| GET    | `/api/v1/tenants/{tenantId}/audit`                                  | Browse the change-history ledger (filters: `tableName`, `recordKey`, `actor`, `limit`, `offset`) |

All routes require authentication. Use any of:

- `Authorization: Bearer <OIDC JWT>` with a `valkeyry.tenants` claim
- `Authorization: Basic …` against the configured LDAP
- `X-API-Key: …` mapped to one or more tenants

### Authorization model
| Caller       | Tenant entitlement                      | Writer authority             | Effective rights         |
|--------------|-----------------------------------------|------------------------------|--------------------------|
| OIDC reader  | `valkeyry.tenants: ["demo"]`            | _absent_                     | Read-only on tenant      |
| OIDC writer  | `valkeyry.tenants: ["demo"]`            | `valkeyry.role: "writer"` or `roles: [...]` containing `writer` / `admin` / `valkeyry_writer` | Read + write |
| LDAP agent   | `ou: demo` on the user record           | **granted automatically**    | Read + write             |
| API-key      | configured tuple `key:tenant1,tenant2`  | **granted automatically**    | Read + write             |

> Technical users (LDAP + API-key) are writers by definition — they only exist to push schemas
> and entries from automation pipelines. The audit log captures exactly who did what, so the
> privilege is safe to grant by default.

## Configuration

Environment variables (most useful):

| Variable | Default | Purpose |
|----------|---------|---------|
| `VALKEYRY_CONFIG_PORT` | `8081` | HTTP listen port |
| `VALKEYRY_CONFIG_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/valkeyry_config` | Runtime DB URL |
| `VALKEYRY_CONFIG_JDBC_URL`  | `jdbc:postgresql://localhost:5432/valkeyry_config`  | Flyway URL (boot-time only) |
| `VALKEYRY_CONFIG_DB_USER` / `_PASS` | `valkeyry/valkeyry` | DB credentials |
| `VALKEYRY_OIDC_ISSUER` | `http://localhost:8079/default` | OIDC issuer-uri |
| `VALKEYRY_LDAP_URL` | `ldap://localhost:1389` | LDAP server |
| `VALKEYRY_API_KEYS` | _empty_ | `key1:tenantA,tenantB;key2:tenantC` |

## Local run

```bash
# Bring up Postgres
docker run -d --rm --name vc-pg -e POSTGRES_DB=valkeyry_config \
  -e POSTGRES_USER=valkeyry -e POSTGRES_PASSWORD=valkeyry \
  -p 5432:5432 postgres:16-alpine

# Run the service
mvn spring-boot:run

# Smoke test
curl -H "X-API-Key: dev-key" http://localhost:8081/actuator/health
```

## Storage model
- `virtual_table_registry` — one row per `(tenant, table)`+version; current row has `is_active=true`.
- `virtual_table_entry` — append-only; one row per write. `is_latest=true` marks the current head.
- The `data` column is JSONB; a GIN index on `jsonb_path_ops` accelerates `@>` containment search.

## Idempotency Guard
Every ingest computes a canonical-JSON SHA-256 of the payload. If a row with that hash is
already the current head for the same `(tenant, table, recordKey)`, the request returns
`409 Conflict` (HTTP) and is reported as a "duplicate" by the plugin batch endpoint.
