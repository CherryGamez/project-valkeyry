# Product Requirements Document — `valkeyry-ipaas`

**Version:** 1.0.0-SNAPSHOT
**Module:** `io.valkeyry:valkeyry-ipaas`
**Status:** Active development
**Owner:** Valkeyry Platform Team

---

## 1. Overview

`valkeyry-ipaas` is the **reactive messaging & streaming engine** of the Valkeyry
ecosystem. A single HTTP surface — one publish, one subscribe — delivers a tenant's
message to **Apache Kafka**, **RabbitMQ**, or **ActiveMQ** with consistent semantics,
multi-tenant isolation, token-bucket rate limiting, S3-based payload externalisation
("claim check"), declarative routing topologies, and an LLM-backed operator copilot.

It pairs with `valkeyry-config` (schema registry) to give teams a complete control plane:
config goes in the registry, traffic goes through the iPaaS.

## 2. Problem Statement

Modern platforms wire together several message systems — Kafka for streams, Rabbit for
RPC fan-out, ActiveMQ for legacy bridges. Application teams should not have to:

1. **Learn three SDKs.** Switching brokers should be a header value, not a refactor.
2. **Reinvent multi-tenancy.** Each consumer ends up writing their own ACL + isolation.
3. **Operate three rate limiters.** Producers want one fair bucket, not three.
4. **Pipe huge payloads through queues.** Anything over a few hundred KB needs to go
   to object storage with a reference, not the queue.
5. **Run their own DLQ tooling.** Dead-letter inspection should be a first-class UX.

`valkeyry-ipaas` solves all five with a thin reactive façade that the operator console
(React) and any service can hit.

## 3. Goals & Non-Goals

### Goals
- **G1.** Single publish/subscribe HTTP surface, broker chosen per-call via header.
- **G2.** Reactive backpressure-aware pipeline (WebFlux + Reactor-Kafka / Rabbit).
- **G3.** Token-bucket rate limit on Valkey/Redis with a Lua-atomic snippet.
- **G4.** Claim-check externalisation: payloads over a threshold go to S3/MinIO and
  the queue carries only a reference.
- **G5.** Multi-tenant isolation via OIDC + LDAP, normalised to a single authority.
- **G6.** Declarative topology builder — wire sources → transforms → sinks via JSON.
- **G7.** First-class DLQ inspection with re-queue / drop actions per row.
- **G8.** Cross-tenant fan-out publishing for platform-wide announcements.
- **G9.** Operator Copilot — LLM-assisted answer + tool-calling for routine ops.
- **G10.** Live metrics over Server-Sent Events into the React console.

### Non-Goals
- ❌ Schema validation. That is `valkeyry-config`.
- ❌ Replace the brokers. Kafka/Rabbit/ActiveMQ remain the source of truth.
- ❌ Long-term storage of message payloads. Claim-check is for in-flight only.
- ❌ Become a workflow engine. Topologies are pipelines, not DAGs with state.

## 4. Target Personas

| Persona | Primary use-case | Auth track |
| --- | --- | --- |
| **Application producer** | Publish events without picking a broker SDK | OIDC Bearer JWT |
| **Application consumer** | Subscribe via SSE, get auto-resolved claim-checks | OIDC Bearer JWT |
| **Platform operator** | Watch metrics, drain DLQ, build topologies | OIDC writer |
| **SRE on-call** | Ask the Copilot to diagnose a stuck queue | OIDC writer |
| **Compliance auditor** | Read-only on metrics + topology config | OIDC reader |
| **LDAP automation agent** | Headless publish from a directory-driven job | LDAP Basic |

## 5. Core User Stories

- **US-1** As a producer I `POST /messages` with `broker: "rabbit"` and don't care about
  the underlying SDK.
- **US-2** As a consumer I `GET /messages/subscribe` (SSE) and receive resolved
  payloads even when the message went via claim-check.
- **US-3** As an operator I open the **Live Metrics** tab and watch publish/consume
  throughput per broker in real time.
- **US-4** As an operator I open **DLQ Inspector**, filter by destination, and re-queue
  / drop poison messages.
- **US-5** As a platform engineer I declare a topology (`source → transform → sink`)
  as JSON and it runs without re-deploying the engine.
- **US-6** As a tenant admin I publish one message to N peer tenants via the
  **Multi-Tenant Publish** tab.
- **US-7** As an on-call SRE I ask the **Operator Copilot** "why is `orders.new`
  backed up?" and it calls the right tools to answer.

## 6. Functional Requirements

### 6.1 Publish / Subscribe
- `POST /api/v1/tenants/{tenantId}/messages` — accept `{destination, broker?, mode, headers, payload}`.
  Returns `202 { messageId, broker, destination, externalised }`.
- `GET .../messages/subscribe` (SSE) — unbounded stream of `ReceivedMessage` JSON,
  claim-check resolved server-side.

### 6.2 Rate limiting
- Token-bucket on Valkey/Redis, key = authenticated principal (falls back to remote IP).
- Atomic Lua snippet — single round-trip per request.
- On throttle: `429 Too Many Requests` with `Retry-After` header.

### 6.3 Claim-check externalisation
- Payloads ≥ `valkeyry.claimcheck.threshold-bytes` (default 256 KiB) → uploaded to S3.
- Outgoing message body carries `x-valkeyry-claimcheck=true` + bucket/key headers.
- Subscribe endpoint re-inlines the body transparently.

### 6.4 Declarative topology
- `POST /api/v1/tenants/{tid}/topologies` — declare `{sources, transforms, sinks}`.
- `GET .../topologies` — list active.
- Runtime executor materialises a Reactor `Flux` per source, fans into sinks.

### 6.5 DLQ Inspector
- `GET .../dlq` — paged dead-letter rows per broker.
- `POST .../dlq/{id}/requeue` — re-publish to the original destination.
- `DELETE .../dlq/{id}` — drop with audit trail.

### 6.6 Multi-tenant publish
- `POST .../multi-publish` — array of target tenants, single payload, per-target broker.
- Returns per-target success / failure.

### 6.7 Operator Copilot
- `POST .../copilot/chat` — LLM-driven assistant with tool-calling access to metrics,
  DLQ, topology, and queue-management endpoints. Backed by the configured provider.

### 6.8 Queue management
- `GET .../queues` — broker-introspected queue list.
- Drain / purge / inspect head per queue (admin authority).

## 7. Non-Functional Requirements

| ID | Requirement | Target |
| --- | --- | --- |
| NFR-1 | Reactive end-to-end | WebFlux + Reactor-Kafka/Rabbit + JMS Mono adapter |
| NFR-2 | Sub-50 ms p95 publish | At default rate-limit + threshold |
| NFR-3 | Backpressure honoured | No unbounded buffers in the publish/subscribe path |
| NFR-4 | Horizontal scale | Stateless engine, shared Valkey + S3 |
| NFR-5 | Observability | Prometheus metrics, structured logs, OTLP tracing |
| NFR-6 | Multi-tenant isolation | `TenantAccessGuard` mandatory on every route |

## 8. Success Metrics

- **Adoption:** ≥ 50% of internal producers publish through iPaaS within first quarter.
- **Mean time to triage** an `orders.new` backlog from open-question to root-cause:
  ≤ 5 minutes using Copilot + DLQ tabs.
- **Claim-check savings:** ≥ 80% reduction in broker storage for large-message tenants.
- **Topology change leadtime:** ≤ 2 minutes from JSON declaration to live traffic.

## 9. Release Scope

### v1.0 (current SNAPSHOT)
- Publish / Subscribe (Kafka, Rabbit, ActiveMQ)
- Token-bucket rate limit on Valkey
- Claim-check externalisation (S3, Azure Blob, MinIO)
- Topology engine + builder UI
- DLQ inspector, multi-tenant publish, operator copilot
- React admin console (`frontend/`) with 5 tabs + this Docs tab
- Helm chart & Docker image

### Backlog
- WebSocket sub instead of SSE for browser consumers
- Per-tenant rate-limit buckets (today: per-principal)
- Native S3 → Kafka tiered storage when Kafka 3.7 tiered-storage GA
- Native ActiveMQ Artemis adapter alongside the JMS bridge

## 10. Dependencies

- **Apache Kafka 3.x**, **RabbitMQ 3.12+**, **ActiveMQ Artemis** (any combination).
- **Valkey 8.x / Redis 7+** — rate-limit bucket store.
- **S3-compatible object store** — AWS S3, MinIO, or Azure Blob.
- **OIDC provider** with `valkeyry.tenants` claim.
- **LLM provider** — Operator Copilot uses the configured `valkeyry.copilot.provider`.
- **`valkeyry-config`** — optional pairing for schema-validated payloads.

---

*Last updated: see `git log -- docs/PRD.md`.*
