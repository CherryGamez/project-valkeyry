# Product Requirements Document — `valkeyry-config`

**Version:** 1.0.0-SNAPSHOT
**Module:** `io.valkeyry:valkeyry-config`
**Status:** Active development
**Owner:** Valkeyry Platform Team

---

## 1. Overview

`valkeyry-config` is a **headless JSON-Schema registry engine** that stores arbitrary tenant
configuration as *virtual tables* — JSON Schema-validated documents backed by PostgreSQL
JSONB. It provides a single, reactive HTTP surface that build-tool plugins (Maven, Gradle),
CI agents, human operators, and downstream services can call to declare schemas, push
records, browse the version trail, and audit every mutation.

It is one of the three deliverables in the Valkeyry ecosystem, alongside `valkeyry-ipaas`
(messaging engine) and the `valkeyry-config-plugin` family (build-tool validators that
share the same hash algorithm so the server can short-circuit unchanged payloads).

## 2. Problem Statement

Operating teams struggle with three recurring pain points in configuration management:

1. **No single source of truth.** Configuration lives in YAML files scattered across
   repos, broker UIs, secret stores, and ad-hoc databases — drift is invisible until
   production breaks.
2. **No schema enforcement at write-time.** Mistyped keys land in production because the
   only validation is the consumer crashing later.
3. **No tamper-evident history.** "Who changed the rate-limit on Tuesday at 03:14?"
   is unanswerable without a forensic audit log.

`valkeyry-config` addresses all three by turning every config object into a versioned,
JSON-Schema-validated record with an append-only audit ledger and webhook fan-out.

## 3. Goals & Non-Goals

### Goals
- **G1.** Single REST endpoint surface for declaring schemas and ingesting records.
- **G2.** Strict JSON-Schema (Draft 2020-12) validation on every write.
- **G3.** Deterministic idempotency via canonical-JSON SHA-256 fingerprint.
- **G4.** Append-only version trail per logical record (`is_latest` semantics).
- **G5.** Multi-tenant isolation via OIDC, LDAP, and API-key authentication tracks.
- **G6.** Tamper-evident audit log with optional webhook fan-out (HMAC-signed).
- **G7.** Reactive top-to-bottom (WebFlux + R2DBC) to handle bursty plugin uploads.

### Non-Goals
- ❌ Generic key-value store. Records must be schema-bound.
- ❌ Runtime DDL. Virtual tables share one physical table; no `CREATE TABLE` per tenant.
- ❌ Message brokering. That responsibility belongs to `valkeyry-ipaas`.
- ❌ Code generation from schemas. Plugins do client-side validation only.

## 4. Target Personas

| Persona | Primary use-case | Auth track |
| --- | --- | --- |
| **Build pipeline (CI)** | Push validated config from a build-tool plugin | `X-API-Key` |
| **LDAP automation agent** | Sync configs from a directory-driven workflow | `Authorization: Basic` |
| **Platform engineer (human)** | Browse, declare schemas, roll back, audit | OIDC Bearer JWT |
| **Compliance auditor** | Inspect immutable audit ledger, verify webhook signatures | OIDC reader-only |
| **Downstream service** | Consume the active head of a table | OIDC reader-only |

## 5. Core User Stories

- **US-1** As a CI pipeline I can `POST /tables` to declare or re-version a virtual table
  so my schema evolves alongside the code that uses it.
- **US-2** As a plugin I can batch-upload records and trust that duplicates are silently
  deduped by the idempotency guard.
- **US-3** As a platform engineer I can see the full version trail of any record and roll
  it back to a previous version with a single click.
- **US-4** As an auditor I can subscribe a webhook to receive every config mutation,
  signed with HMAC-SHA256, and verify it offline.
- **US-5** As a tenant admin I can browse only **my** tenant's data — even if I share
  the same registry instance with other tenants.

## 6. Functional Requirements

### 6.1 Schema Registry
- `POST /api/v1/tenants/{tenantId}/tables` — declare or re-version a virtual table
  (writer-only). Validates Draft 2020-12 JSON Schema before accepting.
- `GET /api/v1/tenants/{tenantId}/tables` — list active tables.
- `GET /api/v1/tenants/{tenantId}/tables/{name}` — fetch active schema row.

### 6.2 Record Ingest
- `POST .../tables/{name}/entries` — single-record ingest with schema validation
  + idempotency check.
- `POST .../tables/{name}/entries:batch` — bulk ingest used by build-tool plugins.
  Returns per-record success / duplicate / validation error.
- `GET .../tables/{name}/entries` — paged browse of latest heads.
- `GET .../tables/{name}/entries/{recordKey}` — fetch current head.
- `GET .../tables/{name}/entries/{recordKey}/history` — full version trail.

### 6.3 Search
- `POST .../tables/{name}/search` — JSONB containment (`@>`) search backed by GIN index.

### 6.4 Audit & Webhooks
- `GET .../audit` — browse the change-history ledger with filters
  (`tableName`, `recordKey`, `actor`, `limit`, `offset`).
- Rollback endpoint promotes a historical version to the new head.
- `POST .../webhooks` (admin) — register HMAC-signed audit fan-out URL.

### 6.5 Admin / Tools
- `/admin.html` — tenant + user management UI (writer-role + admin authority).
- `/tools.html` — interactive JSON Schema sandbox and curl recipe generator.

## 7. Non-Functional Requirements

| ID | Requirement | Target |
| --- | --- | --- |
| NFR-1 | Reactive end-to-end (no thread-blocking I/O on hot path) | WebFlux + R2DBC |
| NFR-2 | UTF-8 fidelity across HTTP / DB / static assets | `template0`, `LC_*=en_US.UTF-8` |
| NFR-3 | Tamper-evident audit ledger | Append-only, FK to actor + IP |
| NFR-4 | Plugin/server fingerprint parity | Shared canonical-JSON SHA-256 |
| NFR-5 | Cold-start latency | < 5 s in container, Flyway V1→V4 applied |
| NFR-6 | Container-friendly | Single fat-jar, `Dockerfile` multi-stage, K8s Helm chart |

## 8. Success Metrics

- **Adoption:** ≥ 80% of internal services declare their config through the registry
  within the first quarter of GA.
- **Validation savings:** ≥ 95% of malformed configs caught at write-time (vs. runtime
  crash baseline).
- **Audit coverage:** 100% of mutations land in the ledger; webhook delivery success ≥ 99.5%.
- **Plugin dedup hit-rate:** ≥ 60% of repeated CI uploads short-circuit on the server.

## 9. Release Scope

### v1.0 (current SNAPSHOT)
- Schema registry, entry ingest, search, audit, webhooks
- HTMX admin GUI (`index.html`), tenant/user admin (`admin.html`), tooling (`tools.html`)
- OIDC + LDAP + API-key triple-auth
- Maven & Gradle plugins ship in the same reactor
- Built-in documentation tab (PRD / TRD / App Flow)

### Backlog
- GraphQL projection over `/search`
- Multi-region replication
- Per-tenant rate limiting (currently global)
- Slack / Microsoft Teams native webhook adapters

## 10. Dependencies

- **`valkeyry-config-plugin/plugin-core`** — shared canonical-JSON fingerprint algorithm.
- **PostgreSQL 14+** — UTF-8 database, JSONB + GIN.
- **OIDC provider** — issuer of bearer JWTs with `valkeyry.tenants` claim.
- **Optional:** Valkey/Redis (for future rate-limiting), LDAP server.

---

*Last updated: see `git log -- docs/PRD.md`.*
