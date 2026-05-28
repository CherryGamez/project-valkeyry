# Audit Log — change ledger for `valkeyry-config`

Every config-changing call against `valkeyry-config` deposits a row in the
`config_audit_log` table. The ledger is **append-only** and visible via a tenant-scoped
REST endpoint.

## Storage shape

| Column         | Type           | Purpose                                                  |
|----------------|----------------|----------------------------------------------------------|
| `id`           | UUID           | row id                                                   |
| `tenant_id`    | varchar(128)   | tenant scope                                             |
| `table_name`   | varchar(255)   | virtual-table name                                       |
| `operation`    | varchar(32)    | `DECLARE_TABLE` / `REVISE_TABLE` / `INGEST_RECORD` / `DEDUP_SKIP` |
| `record_key`   | varchar(512)   | logical record id (null for table-level ops)             |
| `before_value` | jsonb          | prior schema / payload (null on first write)             |
| `after_value`  | jsonb          | new schema / payload                                     |
| `actor`        | varchar(255)   | username / subject / API-key fingerprint                 |
| `actor_track`  | varchar(32)    | `OIDC` / `LDAP` / `API_KEY` / `ANONYMOUS`                |
| `changed_at`   | timestamptz    | server time of the change                                |
| `request_id`   | varchar(128)   | Reactor exchange id (handy for trace correlation)        |

Three indexes keep tenant-scoped browse + per-table + per-actor queries cheap.

## Operations captured

| Operation        | When fired                                                       |
|------------------|------------------------------------------------------------------|
| `DECLARE_TABLE`  | First time a `(tenant, table)` is registered.                    |
| `REVISE_TABLE`   | Schema for an existing `(tenant, table)` is re-versioned.        |
| `INGEST_RECORD`  | Each new version of a logical record.                            |
| `DEDUP_SKIP`     | The Idempotency Guard short-circuited a duplicate write.         |

`DEDUP_SKIP` is recorded so compliance can prove that yes, the caller did try to write
something, and yes, the server correctly recognised it as identical.

## REST API

```
GET /api/v1/tenants/{tenantId}/audit
```

Filters (all optional, combined as `AND`):

| Query param  | Meaning                                                           |
|--------------|-------------------------------------------------------------------|
| `tableName`  | restrict to one virtual table                                     |
| `recordKey`  | combined with `tableName`: history of a single logical record     |
| `actor`      | every change a given subject performed (cross-table)              |
| `limit`      | 1-500, default 50                                                 |
| `offset`     | 0+, default 0                                                     |

### Examples

```bash
# Last 50 events for tenant
curl -H "Authorization: Bearer $TOKEN" \
  "$API/api/v1/tenants/acme-prod/audit"

# Full trail for customers/alice (paged)
curl -H "Authorization: Bearer $TOKEN" \
  "$API/api/v1/tenants/acme-prod/audit?tableName=customers&recordKey=alice&limit=200"

# Everything build-bot has done
curl -H "X-API-Key: $KEY" \
  "$API/api/v1/tenants/acme-prod/audit?actor=build-bot"
```

### Sample response (truncated)

```json
[
  {
    "id":"…",
    "tenantId":"acme-prod",
    "tableName":"customers",
    "operation":"INGEST_RECORD",
    "recordKey":"alice",
    "beforeValue":{"id":"alice","tier":"gold"},
    "afterValue":{"id":"alice","tier":"platinum"},
    "actor":"build-bot",
    "actorTrack":"LDAP",
    "changedAt":"2026-02-14T09:13:22.481Z",
    "requestId":"e3-1"
  },
  {
    "id":"…",
    "tenantId":"acme-prod",
    "tableName":"customers",
    "operation":"DECLARE_TABLE",
    "recordKey":null,
    "beforeValue":null,
    "afterValue":{"type":"object","required":["id","tier"]},
    "actor":"writer@acme.io",
    "actorTrack":"OIDC",
    "changedAt":"2026-02-14T09:11:05.011Z",
    "requestId":"e1-1"
  }
]
```

## Tamper-resistance

The endpoint exposes only GET — there is no API to delete or mutate audit rows from outside
the JVM. For full immutability in regulated environments:

1. Grant the application DB user `INSERT, SELECT` on `config_audit_log` and **revoke**
   `UPDATE, DELETE`:
   ```sql
   REVOKE UPDATE, DELETE ON config_audit_log FROM valkeyry;
   ```
2. Optionally replicate the table to a separate WORM-backed store (Postgres logical
   replication → S3-object-lock sink, for example).

## Failure handling

Audit writes happen inside the same transaction as the business write. If the audit insert
itself errors (e.g. transient DB failure), the audit service swallows it (`onErrorResume`)
so the original operation is **never** failed by an audit problem. Operationally we treat
audit-log gaps as alerts, not request-time failures.

---

## Audit Console (web UI)

`valkeyry-config` ships a thin React 18 audit-browser served at:

```
http://<host>:8081/audit/
```

Zero-build (Babel standalone + Tailwind via CDN), bundled under
`valkeyry-config/src/main/resources/static/audit/`. Shows the same JSON payload as the SIEM
webhook, with a side-by-side **before/after diff** + a unified key-by-key tree. Useful for
in-app investigations without spelunking the SIEM. See its own README for the testid map +
auth model.

---

## Webhook fan-out (SIEM / Splunk / Datadog Logs / …)

Every persisted audit row is also POSTed to one or more configured URLs in near-real-time.
The fan-out is **off** by default; enable per environment.

### Configuration

| Property                                       | Env var                              | Default |
|------------------------------------------------|--------------------------------------|---------|
| `valkeyry.audit.webhook.enabled`               | `VALKEYRY_AUDIT_WEBHOOK_ENABLED`     | `false` |
| `valkeyry.audit.webhook.urls`                  | `VALKEYRY_AUDIT_WEBHOOK_URLS` (CSV)  | _empty_ |
| `valkeyry.audit.webhook.secret`                | `VALKEYRY_AUDIT_WEBHOOK_SECRET`      | _empty_ |
| `valkeyry.audit.webhook.timeout-ms`            | `VALKEYRY_AUDIT_WEBHOOK_TIMEOUT_MS`  | `5000`  |
| `valkeyry.audit.webhook.max-retries`           | `VALKEYRY_AUDIT_WEBHOOK_MAX_RETRIES` | `3`     |
| `valkeyry.audit.webhook.initial-backoff-ms`    | `VALKEYRY_AUDIT_WEBHOOK_BACKOFF_MS`  | `200`   |

### Request shape

```
POST <url>
Content-Type: application/json
X-Valkeyry-Event:       config.audit
X-Valkeyry-Tenant:      <tenantId>
X-Valkeyry-Request-Id:  <reactor exchange id>
X-Valkeyry-Signature:   sha256=<lowercase-hex HMAC-SHA256 of body using shared secret>

{
  "id":"…", "tenantId":"…", "tableName":"…", "operation":"…", "recordKey":"…",
  "beforeValue": { … },         // raw JSON, embedded
  "afterValue":  { … },         // raw JSON, embedded
  "actor":"…", "actorTrack":"OIDC|LDAP|API_KEY", "changedAt":"…ISO-8601…", "requestId":"…"
}
```

### Verifying the signature (consumer side)

```python
import hmac, hashlib
def verify(body: bytes, header: str, secret: str) -> bool:
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)
```

If `secret` is empty in the producer, the header is `sha256=unsigned` — accept only in dev.

### Delivery semantics

- **At-least-once** with exponential backoff (configurable max retries) on 5xx + network IO.
- **Fail-fast** on 4xx — your handler URL is misconfigured; no point retrying.
- **Never** propagates errors. Audit DB write is the source of truth; failed webhook deliveries
  log `WARN` lines (look for `audit-webhook: failed delivery to …`). Wire those into an alert
  so you notice a stuck SIEM ingest.
- **Non-blocking**. Uses Reactor Netty; the original HTTP response is not delayed by the fan-out.

### Reliable backfill / re-emit

The webhook is **best-effort**, complementing — never replacing — the DB ledger. If the SIEM
was down for a window, query the `config_audit_log` over the affected timeframe and replay
the rows downstream:

```sql
SELECT * FROM config_audit_log
 WHERE changed_at BETWEEN $1 AND $2
 ORDER BY changed_at;
```

A small CLI / script can then POST each row through your SIEM's ingestion API.
