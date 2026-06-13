# Application Flow — `valkeyry-ipaas`

**Version:** 1.0.0-SNAPSHOT
**Companion docs:** [`PRD.md`](./PRD.md) · [`TRD.md`](./TRD.md)

---

## 1. Top-Level Flow

```
                ┌─────────────────────────────┐
                │  LoginPage.jsx              │
                │  OIDC redirect / local user │
                └──────────────┬──────────────┘
                               │ session in browser
                               ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │  AdminConsole.jsx — React console                                │
   │  ┌────────┬──────┬───────────┬───────────────┬─────────┬──────┐  │
   │  │ Metrics│ DLQ  │ Topologies│ Multi-Publish │ Copilot │ Docs │  │
   │  └────────┴──────┴───────────┴───────────────┴─────────┴──────┘  │
   └─────┬────────┬───────────┬──────────────┬───────────┬───────────┘
         │        │           │              │           │
         ▼        ▼           ▼              ▼           ▼
      live SSE  paged DLQ  JSON pipeline  fan-out      LLM tool-calling
      metrics   re-queue   declaration    publish      DLQ / metrics / queues
```

## 2. Authentication Flow

```
┌─────────┐
│ Browser │
└────┬────┘
     │ open /  → AuthProvider (react-oidc-context) initialises
     │ redirect to OIDC provider, returns with code → token
     │
     │ session saved in memory + sessionStorage (auth/session.js)
     │
     │ GET /api/v1/tenants/{tid}/metrics/stream
     │     Authorization: Bearer <token>
     ├────────────────────────────────────────▶
     │                                          │
     │  RateLimitWebFilter (Valkey Lua bucket)  │
     │     │ ok                                 │
     │     ▼                                    │
     │  OIDC converter → SCOPE_tenant:<id>      │
     │     │                                    │
     │     ▼                                    │
     │  TenantAccessGuard verifies tid          │
     │     │                                    │
     │     ▼                                    │
     │  MetricsController.streamMetrics(tid)    │
     │                                          │
     │ ◀─── SSE stream of MetricView events ────┘
```

LDAP Basic flow lands on `LdapBasicAuthenticationConverter` instead; the rest of the
chain is identical.

## 3. Publish a Message (Happy Path)

```
producer ──► POST /messages  { destination, broker?, mode, headers, payload }
                              │
                              ▼
              RateLimitWebFilter.consume(principal)
                              │
                throttled?    │   ok ?
                    │         │     │
                    ▼         │     ▼
              429 + Retry-After│  ClaimCheckService.externaliseIfNeeded(payload)
                              │     │
                              │  size ≥ threshold ?
                              │     │
                              │     ▼
                              │  S3 PUT → headers { x-valkeyry-claimcheck=true,
                              │                     x-valkeyry-claim-bucket,
                              │                     x-valkeyry-claim-key }
                              │     │
                              │     ▼
                              │  BrokerRegistry.select(brokerOrDefault, mode)
                              │     │
                              │     ▼
                              │  BrokerAdapter.publish(destination, headers, body)
                              │     │
                              │     ▼
                              │  202 Accepted { messageId, broker, externalised }
                              │
                              └─ on failure: ApiExceptionHandler → 5xx + traceId
```

## 4. Subscribe (SSE + Auto Claim-Check Resolve)

```
GET /messages/subscribe?destination=orders.new&broker=kafka&mode=STREAM
                              │
                              ▼
              SubscribeController.openStream(tid, destination, broker, mode)
                              │
                              ▼
              BrokerAdapter.subscribe → Flux<RawMessage>
                              │
                              ▼
              map → if (headers.x-valkeyry-claimcheck) {
                       S3.GET(bucket, key) → inline payload
                    }
                              │
                              ▼
              Flux<ReceivedMessage> → text/event-stream
                              │
                              ▼
              browser EventSource onmessage(JSON)
```

The consumer never needs to know whether the payload was inline or externalised.

## 5. DLQ Inspect & Re-queue

```
operator → /dlq?broker=kafka&destination=orders.new&limit=50
                              │
                              ▼
              DlqController.list(...) — paged JSON
                              │
                              ▼
              React table renders rows
                              │
                              ▼
   user clicks ⟲ Requeue ───► POST /dlq/{id}/requeue
                              │
                              ▼
              QueueManagementService:
                ├─ read row + payload (resolve claim-check)
                ├─ BrokerAdapter.publish(original destination)
                └─ INSERT dlq_audit (action=REQUEUE, actor)

   user clicks ✕ Drop ──────► DELETE /dlq/{id}
                              │
                              ▼
                ├─ row purged from DLQ
                └─ INSERT dlq_audit (action=DROP, actor)
```

## 6. Declarative Topology

```
1. Operator opens Topology Builder, drags blocks:
      ┌────────┐     ┌──────────────┐     ┌────────────┐
      │ source │ ──► │ transform(s) │ ──► │   sink(s)  │
      └────────┘     └──────────────┘     └────────────┘
2. React serialises to JSON and POSTs to /topologies.
3. TopologyController persists → topology_declaration (JSONB graph).
4. DynamicTransformationRoutingEngine subscribes to source brokers,
   materialises a Reactor Flux per source, applies transforms,
   forwards to sinks. No restart required.
5. Disable from UI → engine unsubscribes immediately.
```

## 7. Multi-Tenant Publish

```
operator → POST /multi-publish
{
  "destination": "platform.announcement",
  "broker":      "kafka",
  "targets":     ["acme", "globex", "umbrella"],
  "payload":     "base64…"
}
                              │
                              ▼
              MultiTenantPublishService.dispatch(...)
                              │
                              ▼
              for each target tenant t:
                ├─ assert caller has SCOPE_tenant:t or platform-admin
                ├─ TenantRouter.publish(t, destination, …)
                └─ collect result { tenant, ok|err }
                              │
                              ▼
              return per-target report
```

## 8. Operator Copilot

```
user types ► /copilot/chat  { sessionId, message: "why is orders.new backed up?" }
                              │
                              ▼
              CopilotChatService.handle(...)
                              │
                              ▼
              LLM (configured provider) ←── system prompt + history
                              │
                              ▼
              tool_call { name: "metrics.read", args: { destination: "orders.new" } }
                              │
                              ▼
              CopilotToolset dispatches → MetricsController internally
                              │
                              ▼
              tool_result returned to LLM
                              │
                              ▼
              (optionally) tool_call { name: "dlq.peek", args: {…} }
                              │
                              ▼
              LLM finalises a natural-language answer
                              │
                              ▼
              React Copilot tab streams tokens to the user
```

All tool calls run with the caller's authority — the LLM cannot escalate privileges.

## 9. React Console Map (`AdminConsole.jsx`)

| Tab | Component | What it shows |
| --- | --- | --- |
| **Live Metrics** | `MetricsPanel` | Real-time SSE chart of publish/consume/throttle rates |
| **DLQ Inspector** | `DlqPanel` | Paged DLQ table with re-queue / drop actions |
| **Topology Builder** | `TopologyBuilder` | Visual block builder, JSON-backed declarations |
| **Multi-Tenant Publish** | `MultiPublishPanel` | Fan-out form with per-target broker selection |
| **Operator Copilot** | `CopilotPanel` | Streaming chat UI, tool-call transcript |
| **Docs** | `DocsPanel` | PRD · TRD · App Flow — markdown rendered client-side |

`TopBar` shows the active session, tenant selector, broker default, and claim-check
threshold so the operator always knows what context they're in.

## 10. Failure Modes (operator-visible)

| Error | HTTP | Where surfaced |
| --- | --- | --- |
| `RATE_LIMITED` | 429 | Toast + auto-retry timer in the publish form |
| `BROKER_UNREACHABLE` | 502 | Broker pill in TopBar turns red |
| `TENANT_FORBIDDEN` | 403 | Multi-Publish: per-target red badge with reason |
| `CLAIM_CHECK_FETCH_FAILED` | 502 | Subscribe SSE emits an `event: error` frame; React shows banner |
| `TOPOLOGY_INVALID` | 400 | Builder highlights the offending block with the validation message |
| `COPILOT_TOOL_DENIED` | 403 | Chat shows the denied tool call but no execution |

## 11. Lifecycle Summary

1. **Boot** — Spring Boot loads, R2DBC pool to Postgres, Lettuce pool to Valkey, broker
   producers/consumers warm up.
2. **Auth handshake** — every request resolves into `SCOPE_tenant:<id>` regardless of
   whether the caller used OIDC, LDAP, or API key.
3. **Throttle** — `RateLimitWebFilter` runs before any business logic.
4. **Publish** — `TenantRouter` chains claim-check ➜ broker selection ➜ adapter publish.
5. **Subscribe** — SSE stream; claim-check transparently resolved.
6. **Observe** — operators use the React console; metrics flow back over SSE; the
   Copilot can read every metric/DLQ row a human can.

---

*See [`TRD.md`](./TRD.md) for component specifics and [`PRD.md`](./PRD.md) for the product
intent behind each flow.*
