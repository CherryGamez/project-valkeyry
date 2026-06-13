# Technical Requirements Document — `valkeyry-ipaas`

**Version:** 1.0.0-SNAPSHOT
**Module:** `io.valkeyry:valkeyry-ipaas`
**Companion docs:** [`PRD.md`](./PRD.md) · [`App_Flow.md`](./App_Flow.md)

---

## 1. Architecture Summary

```
┌────────────────────────────────┐
│  React Admin Console           │
│  (valkeyry-ipaas/frontend)     │
└──────────────┬─────────────────┘
               │ OIDC Bearer · LDAP Basic
               ▼
┌─────────────────────────────────────────────────────────────┐
│                  valkeyry-ipaas                              │
│  Netty (WebFlux) + Spring Security 6                         │
│   ├─ RateLimitWebFilter   (Valkey Lua-atomic token bucket)   │
│   ├─ TenantAccessGuard    (single normalised authority)      │
│   ├─ PublishController    ──► TenantRouter ──► BrokerRegistry│
│   ├─ SubscribeController  ◀── ReactiveBrokerAdapters         │
│   ├─ ClaimCheckService    ──► S3 / Azure Blob / MinIO        │
│   ├─ TopologyExecutor     (Reactor Flux materialiser)        │
│   ├─ MultiTenantPublish   (fan-out across tenant guards)     │
│   ├─ OperatorCopilot      (LLM tool-calling)                 │
│   └─ MetricsController    (SSE live stream)                  │
│                                                              │
│  Brokers ──► Kafka / RabbitMQ / ActiveMQ (JMS)               │
│  Valkey ──► rate-limit bucket                                │
│  S3   ──► claim-check externalisation                        │
└─────────────────────────────────────────────────────────────┘
```

### Tech stack
| Layer | Choice | Notes |
| --- | --- | --- |
| Runtime | **JDK 21** | `<release>21</release>` enforced |
| HTTP | **Spring Boot 3 / WebFlux / Netty** | Single non-blocking thread per core |
| Security | **Spring Security 6 (reactive)** | OIDC + LDAP, same model as `valkeyry-config` |
| Kafka client | **Reactor-Kafka 1.3** | Producer/consumer as Mono/Flux |
| RabbitMQ client | **Reactor-RabbitMQ 1.5** | Same paradigm |
| ActiveMQ | **Spring JMS** wrapped in `Mono.fromCallable` | Bounded boundedElastic scheduler |
| Object store | **AWS SDK v2 (S3)** + Azure Blob | Pluggable via `DynamicStorageFactory` |
| Rate-limit store | **Valkey 8 / Redis 7 (Lettuce reactive)** | Lua snippet `TokenBucketRateLimiter.lua` |
| Frontend | **React 18 (CRA) + shadcn/ui + Tailwind** | Built into the same jar via `frontend-maven-plugin` (optional) |
| Copilot | **LLM via integration playbook** | Provider configured at deploy time |
| Container | **Multi-stage Dockerfile** | Temurin 21 JRE base |

## 2. Component Catalogue

### 2.1 Controllers (`io.valkeyry.ipaas.api` and sibling packages)
- `PublishController` — `POST /messages` entry-point; delegates to `TenantRouter`.
- `SubscribeController` — SSE endpoint, auto-resolves claim-check headers.
- `TopologyController` — declarative pipeline CRUD.
- `MultiTenantPublishController` — fan-out across tenant authorities.
- `OperatorCopilotController` — chat endpoint backing the React Copilot tab.
- `MetricsController` — live SSE metrics for the dashboard.
- `QueueManagementController` — admin-level broker introspection.
- `DlqController` (inside `queue/`) — paged DLQ with re-queue / drop.
- `ApiExceptionHandler` — single error → JSON translator.

### 2.2 Domain / Services
- `TenantRouter` — orchestrates the publish pipeline: claim-check ➜ broker ➜ adapter.
- `BrokerRegistry` — selects the right `BrokerAdapter` (`kafka`, `rabbit`, `activemq`),
  honours the per-call override and the `VALKEYRY_BROKER_DEFAULT` fallback.
- `ClaimCheckService` — externalises payloads above the threshold; sets
  `x-valkeyry-claim-{bucket,key}` headers.
- `ReactiveStorageClient` — uniform reactive façade over S3 / Azure / MinIO.
- `TokenBucketRateLimiter` — Lua-atomic bucket; key = principal or remote IP.
- `DynamicTransformationRoutingEngine` — runtime execution of declared topologies.
- `MultiTenantPublishService` — iterates target tenants under their own guards.
- `CopilotChatService` + `CopilotToolset` — LLM agent with tool-calling.

### 2.3 Security (`io.valkeyry.ipaas.security`)
- `SecurityConfiguration` — WebFlux security chain order:
  `RateLimitWebFilter` ➜ OIDC/LDAP/ApiKey converter ➜ `TenantAccessGuard` ➜ controllers.
- `RateLimitWebFilter` — bucket check up-front, before any business logic.
- `LdapBasicAuthenticationManager` / Converter — Basic auth track.
- `TenantAccessGuard` — `SCOPE_tenant:<id>` extraction & enforcement.
- `DynamicWorkspaceAuthorizationManager` — fine-grained per-workspace check
  on multi-publish & topology routes.

## 3. Data Model

`valkeyry-ipaas` is intentionally **mostly stateless**. Persistent state lives in:

- **PostgreSQL** — `topology_declaration`, `multi_publish_target`, `dlq_audit`,
  `copilot_session`, `tenant_storage_binding`.
- **Valkey** — rate-limit buckets, ephemeral copilot session memory.
- **S3** — claim-check payloads (cleaned by lifecycle policy).
- **Brokers** — queues, topics, DLQs.

Key columns (high-level):
```
topology_declaration       multi_publish_target
─────────────────────      ─────────────────────
id          UUID PK         id            UUID PK
tenant_id   VARCHAR         tenant_id     VARCHAR
name        VARCHAR         target_tenant VARCHAR
graph       JSONB           broker        VARCHAR
enabled     BOOLEAN         destination   VARCHAR
created_at  TIMESTAMPTZ     enabled       BOOLEAN

copilot_session             dlq_audit
─────────────────           ──────────────────────
id           UUID PK         id          UUID PK
tenant_id    VARCHAR         broker      VARCHAR
session_id   VARCHAR         destination VARCHAR
last_used_at TIMESTAMPTZ     payload_ref TEXT
history      JSONB           action      VARCHAR    -- REQUEUE / DROP
                             actor       VARCHAR
                             created_at  TIMESTAMPTZ
```

## 4. API Surface

| Method | Path | Auth | Writer-only |
| --- | --- | --- | --- |
| POST | `/api/v1/tenants/{tid}/messages` | yes | yes |
| GET  | `/api/v1/tenants/{tid}/messages/subscribe` (SSE) | yes | no |
| POST | `/api/v1/tenants/{tid}/multi-publish` | yes | yes |
| GET  | `/api/v1/tenants/{tid}/topologies` | yes | no |
| POST | `/api/v1/tenants/{tid}/topologies` | yes | yes |
| GET  | `/api/v1/tenants/{tid}/dlq` | yes | no |
| POST | `/api/v1/tenants/{tid}/dlq/{id}/requeue` | yes | yes |
| DELETE | `/api/v1/tenants/{tid}/dlq/{id}` | yes | yes |
| GET  | `/api/v1/tenants/{tid}/queues` | yes | no |
| POST | `/api/v1/tenants/{tid}/copilot/chat` | yes | yes |
| GET  | `/api/v1/tenants/{tid}/metrics/stream` (SSE) | yes | no |

### Publish request
```json
{
  "destination": "orders.new",
  "broker":      "kafka",
  "mode":        "STREAM",
  "headers":     { "content-type": "application/json" },
  "payload":     "base64-encoded bytes"
}
```

### Publish response (`202 Accepted`)
```json
{
  "messageId":    "9c2a…",
  "broker":       "kafka",
  "destination":  "orders.new",
  "externalised": false
}
```

## 5. Configuration

| Env var | Default | Purpose |
| --- | --- | --- |
| `VALKEYRY_IPAAS_PORT` | `8082` | HTTP port |
| `VALKEYRY_VALKEY_URL` | `redis://localhost:6379` | Rate-limit bucket store |
| `VALKEYRY_BROKER_DEFAULT` | `kafka` | Adapter when caller omits `broker` |
| `VALKEYRY_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka brokers |
| `VALKEYRY_RABBIT_URI` | `amqp://guest:guest@localhost:5672/` | RabbitMQ URI |
| `VALKEYRY_ACTIVEMQ_URL` | `tcp://localhost:61616` | ActiveMQ URL |
| `VALKEYRY_S3_ENDPOINT` | _AWS default_ | MinIO / LocalStack override |
| `VALKEYRY_S3_BUCKET` | `valkeyry-claimcheck` | Claim-check bucket |
| `VALKEYRY_CLAIMCHECK_THRESHOLD` | `262144` | Externalise above this many bytes |
| `VALKEYRY_RATELIMIT_CAPACITY` | `200` | Bucket capacity |
| `VALKEYRY_RATELIMIT_REFILL`   | `200` | Tokens refilled per period |
| `VALKEYRY_RATELIMIT_PERIOD`   | `60`  | Period in seconds |
| `VALKEYRY_COPILOT_PROVIDER`   | _required_ | LLM provider name |
| `VALKEYRY_OIDC_ISSUER` / `VALKEYRY_LDAP_URL` | see `valkeyry-config` | Shared auth |

## 6. Build, Test, Run

```bash
# Maven, from repo root
mvn -B -ntp -pl valkeyry-ipaas -am -DskipTests clean install
mvn -pl valkeyry-ipaas spring-boot:run          # :8082

# Frontend (dev only — production is bundled)
cd valkeyry-ipaas/frontend
yarn install && yarn start                       # :3000

# Docker
docker build -t valkeyry/ipaas:dev -f valkeyry-ipaas/Dockerfile valkeyry-ipaas

# Local stack
docker compose -f valkeyry-ipaas/local-dev/docker-compose.yaml up -d
```

### Test layers
- **Unit:** Reactor `StepVerifier` on service code.
- **Slice:** `@WebFluxTest` for controllers, mocked brokers.
- **Integration:** Testcontainers Kafka + Rabbit + Postgres + MinIO + Valkey.
- **Load:** `load-tests/` Gatling scenarios (publish, subscribe-claim-check, multi-pub).

## 7. Observability

- **Logs:** structured JSON, MDC `traceId`, `tenantId`, `principal`, `broker`.
- **Metrics:** Micrometer Prometheus —
  `valkeyry.ipaas.publish.{accepted,throttled,failed}{broker=…}`,
  `valkeyry.ipaas.claimcheck.{externalised,inlined}`,
  `valkeyry.ipaas.dlq.size{broker=…,destination=…}`,
  `valkeyry.ipaas.copilot.tool.calls{tool=…}`.
- **Tracing:** Micrometer Tracing + OTLP exporter.
- **SSE live metrics:** `/metrics/stream` drives the React **Live Metrics** tab.

## 8. Security Threat Model

| Threat | Mitigation |
| --- | --- |
| Cross-tenant publish | `TenantAccessGuard` + `DynamicWorkspaceAuthorizationManager` |
| Rate-limit bypass | `RateLimitWebFilter` is the **first** filter in the chain |
| Claim-check link tampering | Bucket name + key in HMAC-signed headers, lifecycle policy expires data |
| LLM tool abuse | `CopilotToolset` whitelists tool names; tools run under the caller's authority |
| Broker credentials in logs | Logback filter masks `pass=`/`secret=` patterns |

## 9. Deployment

- **Helm chart:** `deploy/helm/valkeyry-ipaas/` — replicas, autoscaler, ingress.
- **Raw manifests:** `deploy/k8s/`.
- **External dependencies:** Kafka, Rabbit, ActiveMQ, Valkey, Postgres, S3 — none
  shipped in the chart; values point to existing services.
- **Scaling:** stateless; scale on CPU + Valkey latency + publish-queue depth metric.

---

*This document tracks the implementation in `valkeyry-ipaas/`. Update on every breaking
change.*
