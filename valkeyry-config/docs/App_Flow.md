# Application Flow — `valkeyry-config`

**Version:** 1.0.0-SNAPSHOT
**Companion docs:** [`PRD.md`](./PRD.md) · [`TRD.md`](./TRD.md)

---

## 1. Top-Level Flow

```
                ┌─────────────────────────────┐
                │  /login.html                │
                │  Local user · OIDC redirect │
                └──────────────┬──────────────┘
                               │ JWT stored in browser
                               ▼
   ┌──────────────────────────────────────────────────────────┐
   │  /index.html  — Operator Console                         │
   │  ┌──────────┬───────────┬──────────┬────────────┬──────┐ │
   │  │ Tables   │ Audit     │ Webhooks │ Endpoints  │ Docs │ │
   │  └──────────┴───────────┴──────────┴────────────┴──────┘ │
   └──────┬──────────────┬──────────────┬──────────────┬──────┘
          │              │              │              │
          ▼              ▼              ▼              ▼
   declare schema   browse ledger   manage HMAC    PRD / TRD
   ingest records   rollback row    subscribers    App Flow
   browse heads     filter actor    test signature
   view history
   JSONB search
```

## 2. Authentication Flow

```
┌─────────┐                              ┌──────────────────┐
│ Browser │ ───── GET /login.html ──────▶│ ValkeyryConfig   │
└────┬────┘                              └────────┬─────────┘
     │                                            │
     │ ◀──── 200 OK (login form) ─────────────────┘
     │
     │ POST /api/auth/login {user, pass}
     ├───────────────────────────────────▶
     │                                    │
     │                                    ▼
     │                          AuthController + UserDetailsService
     │                                    │
     │                                    ▼
     │                          BCrypt verify → issue short-lived JWT
     │                                    │
     │ ◀── 200 { token, roles, tenants } ─┘
     │
     │ persist JWT to localStorage  (assets/auth.js)
     │
     │ GET /api/v1/tenants/demo/tables
     │     Authorization: Bearer <token>
     ├────────────────────────────────────▶
     │                                    │
     │                                    ▼
     │                  TenantAccessGuard verifies SCOPE_tenant:demo
     │                                    │
     │ ◀──── 200 [ {tables…} ] ───────────┘
```

For technical users the flow is even shorter: `X-API-Key: …` or `Authorization: Basic …`
land directly on `TenantAccessGuard`, no login round-trip required.

## 3. Declaring a Virtual Table

```
1. User clicks "+ Declare new table" in the sidebar.
2. Modal opens; user pastes a Draft 2020-12 JSON Schema.
3. JS calls POST /api/v1/tenants/{tid}/tables with the schema.
4. Server …
   a. validates JSON syntax;
   b. compiles the schema (com.networknt:json-schema-validator);
   c. computes config_version = max(existing)+1;
   d. INSERT INTO virtual_table_registry … (is_active=true);
   e. previous active row flipped to is_active=false in the same tx;
   f. AuditEventPublisher emits a SCHEMA_DECLARED event.
5. Sidebar refreshes via htmx.ajax('GET', .../tables, swap:'none').
6. New row appears at the top of the list, marked v{n}.
```

## 4. Ingest a Record (Idempotency Guard)

```
plugin / human ──► POST /tables/x/entries  { recordKey, data }
                              │
                              ▼
                JsonSchemaValidator(table active schema)
                              │
                  invalid ?   │   valid ?
                    │         │     │
                    ▼         │     ▼
              400 + errors   │   PayloadFingerprint.sha256(canonical(data))
                             │     │
                             │     ▼
                  IdempotencyGuard.lookup(tenant,table,recordKey,hash)
                             │     │
                  match ?    │     │   no match ?
                    │        │     │
                    ▼        │     ▼
              409 DUPLICATE  │   markPreviousLatestStale(record_key)
                             │     │
                             │     ▼
                             │   INSERT virtual_table_entry (version+1, is_latest=true)
                             │     │
                             │     ▼
                             │   AuditEventPublisher (ENTRY_CREATED)
                             │     │
                             │     ▼
                             │   201 + EntryView
```

Batch ingest (`…/entries:batch`) runs the same pipeline per record but returns a
single response with `accepted / duplicate / failed` counts and per-row error detail —
that is the call the Maven & Gradle plugins use.

## 5. Audit Timeline & Rollback

```
GET /tenants/{tid}/audit?tableName=orders&recordKey=42&limit=50
                              │
                              ▼
                AuditController → AuditService.list(filters)
                              │
                              ▼
                R2DBC streams paged audit_event rows
                              │
                              ▼
                Frontend renders <AuditTimeline> with op-badges
                              │
                              ▼
   User clicks  ⟲ Rollback  on a historical row
                              │
                              ▼
   POST /tenants/{tid}/audit/rollback  { auditEventId }
                              │
                              ▼
   AuditRollbackController
     ├─ load audit_event.before_data (or after_data depending on op)
     ├─ markPreviousLatestStale(record_key)
     ├─ INSERT virtual_table_entry (… version+1, data=restored, op=ROLLBACK)
     └─ AuditEventPublisher (ENTRY_ROLLED_BACK { source: auditEventId })
```

The rolled-back row is just *another* version — there is no "delete & restore"; the
ledger keeps every action immutable.

## 6. Webhook Delivery

```
AuditEventPublisher emits N to in-process Sinks.Many<…>
       │
       ▼
WebhookDispatcher subscribes — for each enabled subscription:
       │
       ├─ secret = subscription.secretOverride ?: globalSecret
       ├─ body   = JSON.stringify(auditEventView)
       ├─ X-Valkeyry-Signature = hmacSha256(secret, body)
       ├─ POST <url>  (Reactor Netty WebClient, timeout 5s)
       ├─ retry 3× exponential backoff on 5xx/timeout
       └─ on terminal failure → audit_webhook_delivery_failure (metric + log)
```

Subscribers verify the signature offline; the secret never leaves the server.

## 7. Operator GUI Map (`index.html`)

| Tab | What it does | Key APIs |
| --- | --- | --- |
| **Virtual Tables** | Sidebar lists tables; main panel shows entries + history | `/tables`, `/entries`, `/entries/{k}/history` |
| **Audit Timeline** | Filterable ledger view with rollback action | `/audit`, `/audit/rollback` |
| **Webhooks** | CRUD on HMAC fan-out targets | `/webhooks` |
| **Endpoints & URLs** | Live registry of every URL in this environment, with "Try" button | local meta |
| **Docs** | PRD · TRD · App Flow — markdown rendered client-side | static `/docs/*.md` |

Sub-pages:
- `/admin.html` — tenants, local users, role assignments (admin authority only).
- `/tools.html` — JSON Schema sandbox and curl recipe generator for any endpoint.

## 8. Failure Modes (operator-visible)

| Error | HTTP | Where surfaced |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Inline under the ingest editor with JSON-Pointer paths |
| `DUPLICATE_PAYLOAD` | 409 | Sidebar toast + dimmed entry row |
| `TENANT_FORBIDDEN`  | 403 | Login modal re-opens, scope mismatch banner |
| `SCHEMA_NOT_FOUND`  | 404 | Empty-state with "Declare a new table" CTA |
| `WEBHOOK_DEAD`      | n/a (server-side) | Webhooks tab row turns red, last-error tooltip |

## 9. Lifecycle Summary

1. **Boot** — Flyway applies V1→V4 over JDBC, then R2DBC pool starts; `/actuator/health`
   returns `UP`.
2. **Auth handshake** — every request resolves into a `SCOPE_tenant:<id>` authority,
   regardless of track.
3. **Mutate** — every write goes through schema validation + idempotency guard, then
   emits an audit event.
4. **Observe** — operators browse the GUI; auditors stream the ledger; downstream
   services consume `is_latest=true` rows via the public API.
5. **Evolve** — declaring a new schema version flips `is_active`; existing entries
   remain valid against their original schema version (frozen by `config_version`).

---

*See [`TRD.md`](./TRD.md) for component specifics and [`PRD.md`](./PRD.md) for the product
intent behind each flow.*
