# `valkeyry-ipaas` — Reactive Messaging & Streaming Engine

Single reactive entry point that delivers a published message to **Kafka**, **RabbitMQ**, or
**ActiveMQ** with consistent semantics, multi-tenancy, rate-limiting, and S3-based payload
externalisation (claim-check).

## Endpoints

| Method | Path                                              | Purpose                  |
|--------|---------------------------------------------------|--------------------------|
| POST   | `/api/v1/tenants/{tenantId}/messages`             | Publish a message        |
| GET    | `/api/v1/tenants/{tenantId}/messages/subscribe`   | Subscribe (SSE) — auto-resolves claim-check |

### Subscribe (SSE)
```
GET /api/v1/tenants/acme/messages/subscribe?destination=orders.new&broker=kafka&mode=STREAM
Accept: text/event-stream
```
Returns an unbounded `text/event-stream` of `ReceivedMessage` JSON. Backed by
Reactor-Kafka / Reactor-RabbitMQ / JMS listener depending on broker. If the underlying message
carries `x-valkeyry-claimcheck=true`, the controller fetches the bytes from S3 and inlines them
into `payload`, transparently to the consumer.

Body:
```json
{
  "destination": "orders.new",
  "broker":      "kafka",            // optional, falls back to default
  "mode":        "STREAM",            // "QUEUE" or "STREAM"
  "headers":     { "content-type": "application/json" },
  "payload":     "base64-encoded bytes"
}
```

Response (`202 Accepted`):
```json
{
  "messageId":   "…uuid…",
  "broker":      "kafka",
  "destination": "orders.new",
  "externalised": false
}
```

If the `payload` exceeds `valkeyry.claimcheck.threshold-bytes` (default 256 KB), the engine
externalises it to S3/MinIO and the published message carries only the reference plus
`x-valkeyry-claim-{bucket,key}` headers.

## Rate limiting
Token-bucket on Valkey/Redis with the Lua snippet in
`TokenBucketRateLimiter`. Bucket key = authenticated principal (falls back to remote IP). On
throttle, returns `429 Too Many Requests`.

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `VALKEYRY_IPAAS_PORT` | `8082` | HTTP port |
| `VALKEYRY_VALKEY_URL` | `redis://localhost:6379` | Valkey/Redis URL |
| `VALKEYRY_BROKER_DEFAULT` | `kafka` | Default adapter when caller doesn't specify |
| `VALKEYRY_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka brokers |
| `VALKEYRY_RABBIT_URI` | `amqp://guest:guest@localhost:5672/` | RabbitMQ |
| `VALKEYRY_ACTIVEMQ_URL` | `tcp://localhost:61616` | ActiveMQ broker URL |
| `VALKEYRY_S3_ENDPOINT` | _AWS default_ | Override for MinIO / LocalStack |
| `VALKEYRY_S3_BUCKET` | `valkeyry-claimcheck` | Storage bucket |
| `VALKEYRY_CLAIMCHECK_THRESHOLD` | `262144` | Bytes above which payloads are externalised |
| `VALKEYRY_RATELIMIT_CAPACITY` | `200` | Bucket capacity |
| `VALKEYRY_RATELIMIT_REFILL` | `200` | Tokens refilled per period |
| `VALKEYRY_RATELIMIT_PERIOD` | `60` | Period in seconds |

## Security
Same dual-track scheme as `valkeyry-config`. The OIDC `valkeyry.tenants` claim and LDAP `ou`
attribute both map to `SCOPE_tenant:<id>` authorities consumed by `TenantAccessGuard`.

## Local run (one broker only)
For a quick smoke test, run Kafka via the bundled compose file or any single-node image:
```bash
docker run -d --name kafka -p 9092:9092 apache/kafka:3.7.0
docker run -d --name valkey -p 6379:6379 valkey/valkey:8.0
mvn spring-boot:run
```
