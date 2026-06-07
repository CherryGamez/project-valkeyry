# Audit webhook examples

Every audit event recorded by `valkeyry-config` (schema change, entry ingest,
delete, rollback, …) is fanned out to a list of HTTPS endpoints — both the
**static** list from `valkeyry.audit.webhook.urls` and the **dynamic**
subscriptions managed via
`POST /api/v1/tenants/{tenantId}/webhooks` (see `WebhookSubscriptionController`).

This folder ships the minimal artefacts you need to round-trip the whole
fan-out locally:

| File          | Purpose                                                                                          |
|---------------|--------------------------------------------------------------------------------------------------|
| `receiver.js` | Node ≥ 18 server on `:9099` that prints + HMAC-verifies every payload.                          |
| `payload-sample.json` | The exact JSON shape `AuditWebhookPublisher` sends — useful for unit tests of consumers. |

The Postman collection (`../postman/valkeyry-config.postman_collection.json`,
folder **6 · Audit webhooks**) has Register / List / Delete requests prewired
against `{{webhookId}}`.

---

## Quick start (against the local Spring backend on :8081)

```bash
# 1. Start the receiver in one shell.
cd valkeyry-config/examples/webhooks
node receiver.js                     # listens on :9099, prints HMAC verdict per request

# 2. Mint an admin JWT in another shell.
JWT=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
       -H 'Content-Type: application/json' \
       -d '{"username":"admin","password":"admin"}' | jq -r .token)

# 3. Register the receiver as a dynamic webhook.
#    Inside Docker Compose, swap `localhost` for `host.docker.internal`.
curl -s -X POST http://localhost:8081/api/v1/tenants/demo-tenant/webhooks \
     -H "Authorization: Bearer $JWT" \
     -H 'Content-Type: application/json' \
     -d '{
           "url":         "http://localhost:9099/audit",
           "description": "Local dev receiver",
           "secret":      "local-dev-hmac-secret-32-bytes-long"
         }' | jq

# 4. Trigger an audit event — any schema change works.
curl -s -X POST http://localhost:8081/api/v1/tenants/demo-tenant/tables \
     -H 'X-API-Key: plugin-test-key' \
     -H 'Content-Type: application/json' \
     -d '{ "tableName": "ping", "schema": { "type": "object", "properties": { "msg": { "type": "string" } } } }' | jq

# 5. Watch the receiver shell — it should print a JSON body and
#    "Signature valid? : YES".
```

## Payload shape

`AuditWebhookPublisher` posts the event as JSON (see
`AuditWebhookPublisher#toPayload` in the source):

```json
{
  "id":          "01HF1Z9E…",
  "tenantId":    "demo-tenant",
  "tableName":   "customers",
  "operation":   "DECLARE_TABLE",
  "recordKey":   null,
  "beforeValue": null,
  "afterValue":  { "$schema": "…", "type": "object", "properties": { "…": "…" } },
  "actor":       "api-key:plugin-test-key",
  "actorTrack":  "API_KEY",
  "changedAt":   "2026-02-07T15:42:11.314Z",
  "requestId":   "67024b5d-27"
}
```

Headers attached to every delivery:

| Header                       | Value                                                                 |
|------------------------------|-----------------------------------------------------------------------|
| `Content-Type`               | `application/json`                                                    |
| `X-Valkeyry-Signature`       | `sha256=<hex>` — HMAC-SHA256 over the raw body, lowercase hex.<br>Fallback values: `sha256=unsigned` (no secret configured) or `sha256=error` (HMAC failure). |
| `X-Valkeyry-Event`           | always `config.audit`                                                 |
| `X-Valkeyry-Tenant`          | echoes `tenantId`                                                     |
| `X-Valkeyry-Request-Id`      | the originating HTTP request's id (for correlating across systems)    |

## Verifying the HMAC in your own consumer

`receiver.js` shows the canonical Node check. Equivalent snippets:

**Python**

```python
import hmac, hashlib
def verify(body: bytes, header_value: str, secret: str) -> bool:
    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header_value or "")
```

**Java**

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
boolean ok = MessageDigest.isEqual(expected.getBytes(), header.getBytes());
```

## Troubleshooting

| Symptom                                                | Likely cause / fix                                                                         |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------|
| Receiver gets the body but `Signature valid? : NO`     | Secret mismatch between registration body and `$SECRET`. Re-register or restart receiver. |
| No deliveries at all                                   | The Spring app can't reach the receiver. Inside Docker, use `host.docker.internal:9099`.  |
| 409 on registration                                    | The URL is already subscribed for this tenant. `DELETE` the old subscription or use a new URL. |
| Receiver gets duplicates                               | Expected — `AuditWebhookPublisher` retries with exponential backoff on non-2xx responses. |
