# Restructure log — Feb 2026

This iteration **pulled the latest code from GitHub** (`CherryGamez/valkeyry-ecosystem`),
flattened the wrapper layout, removed build/junk artefacts, and de-duplicated three
startup-blocking class conflicts inside `valkeyry-ipaas`.

## Source of truth

* Remote: `https://github.com/CherryGamez/valkeyry-ecosystem` · branch `main`
* Last commit pulled: `e915de8 restructured`
* Local: `/app/valkeyry-ecosystem/` (previous scaffold preserved as
  `/app/valkeyry-ecosystem.backup.<epoch>/` — delete once you've reviewed the diff).

## Restructure actions

### 1. Flattened nested wrapper

The GitHub repo's `main` branch had this shape:

```
valkeyry-ecosystem/        ← GH repo root
├── pom.xml                ← wrapper parent (unused at build time)
├── README.md (29 bytes)
├── LOCAL_SETUP.md
├── WINDOWS_GUIDE.md
├── test_result.md
├── yarn.lock              ← stray
├── .gitconfig             ← stray
├── .emergent/             ← platform metadata
└── valkeyry-ecosystem/    ← the actual reactor
    ├── valkeyry-ipaas
    ├── valkeyry-config
    └── valkeyry-config-plugin
```

The outer wrapper is gone. The inner `valkeyry-ecosystem/` (with the three real modules) is now
the sole root at `/app/valkeyry-ecosystem/`. The orphan `pom.xml`, `LOCAL_SETUP.md`,
`WINDOWS_GUIDE.md`, `test_result.md`, `yarn.lock`, `.gitconfig`, and 29-byte stub `README.md`
were dropped — none of them is referenced by the build.

### 2. Removed build / junk directories

| Removed                                  | Reason                                                       | Size |
|------------------------------------------|--------------------------------------------------------------|------|
| `valkeyry-ipaas/target/`                 | Maven build output — should never be committed               | 1.4 MB |
| `valkeyry-ipaas/test_reports/`           | Test reports from a previous CI run                          | 16 KB |
| `valkeyry-ipaas/memory/`                 | Stray copy of platform `/app/memory/` (PRD etc.)             | 36 KB |
| `valkeyry-ipaas/tests/__init__.py`       | Orphan Python scaffold (not used by Java module)             | 0 B   |
| `valkeyry-ipaas/backend/server.py`       | Orphan FastAPI scaffold (not used by Java module)            | small |
| `valkeyry-ipaas/backend/requirements.txt`| ditto                                                        | small |

### 3. Removed startup-blocking duplicates inside `valkeyry-ipaas`

| Removed                                                | Reason                                                                                                                  |
|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `io/valkeyry/ipaas/ValkeyryIpaasApplication.java`      | Two `@SpringBootApplication` classes → spring-boot-maven-plugin fails main-class auto-detection. Kept `IpaasApplication` (newer, has `@EnableScheduling` required by `RetentionCleanupScheduler`). |
| `io/valkeyry/ipaas/security/SecurityConfig.java`       | Two `@EnableWebFluxSecurity` `@Configuration` classes → ambiguous filter chain. Kept `SecurityConfiguration` (CORS-aware, anonymous-dev support, `DynamicWorkspaceAuthorizationManager`-wired). |
| `io/valkeyry/ipaas/ratelimit/` package                 | Both `ratelimit/RateLimitWebFilter` and `security/RateLimitWebFilter` are `@Component WebFilter` → Spring would register **two** rate-limit beans (same default bean name `rateLimitWebFilter` → `BeanDefinitionStoreException`). Kept the `security/` version (newer, classpath-Lua, `IpaasProperties`-driven). |

These three deletes are the **minimum set** required for the app to start cleanly on a fresh JVM.

## Iteration 7b — unified broker hierarchy

The previously-coexisting two-track broker hierarchy has now been **collapsed to one** —
all 16 callers use `ReactiveBrokerClient` exclusively.

### Migrated
| File | Change |
|------|--------|
| `routing/TenantRouter.java` | Now constructed with `BrokerClientFactory` + `BrokerProperties`. Translates inline payload + `Message` headers into the unified contract `publish(tenantId, projectId, destinationName, payload, headers)`. `projectId` defaults to `"default"` for the single-tenant API. Claim-check externalisation still happens before publish; resulting `DispatchResult` preserves the `externalised` flag. |
| `api/SubscribeController.java` | Bridges the push-based `ReactiveBrokerClient.subscribe(...)` → SSE-style `Flux<ReceivedMessage>` via `Flux.create(sink → client.subscribe(..., msg → sink.next(...); return Mono.just(true)))`. Auto-ACKs deliveries (the SSE client has no ack channel; consumers needing at-most-once should use `/api/v1/consumers/**`). |
| `api/ApiExceptionHandler.java` | `BrokerNotConfiguredException` handler removed. The `BrokerClientFactory.get(...)` instead throws `IllegalArgumentException` on unknown broker type, mapped to 400 with `urn:valkeyry:error:bad-request`. |
| `domain/ReceivedMessage.java` | Javadoc updated to point at `ReactiveBrokerClient#subscribe`. |

### Deleted
- `broker/BrokerAdapter.java`
- `broker/BrokerRegistry.java`
- `broker/kafka/KafkaBrokerAdapter.java`, `broker/rabbit/RabbitBrokerAdapter.java`, `broker/activemq/ActiveMqBrokerAdapter.java` (and their packages)
- `error/BrokerNotConfiguredException.java`

### Final `valkeyry-ipaas` broker package (5 files)
```
broker/
├── ReactiveBrokerClient.java       ← single interface
├── BrokerClientFactory.java        ← string-type → bean resolver
├── KafkaBrokerClient.java
├── RabbitBrokerClient.java
└── ActiveMqBrokerClient.java
```

### Java-source count after unification
- `valkeyry-ipaas`: **90** classes (was 96; -6 obsolete)
- `valkeyry-config`: 47
- `valkeyry-config-plugin`: 30
- **Total**: 144 (was 150)


## Inventory after restructure

```
valkeyry-ecosystem/
├── pom.xml                              ← Maven reactor parent
├── README.md
├── deploy/
│   ├── helm/{valkeyry-ipaas, valkeyry-config}/
│   ├── k8s/{valkeyry-ipaas, valkeyry-config}.yaml
│   └── README.md
├── docs/
│   ├── architecture.md
│   ├── AUDIT_LOG.md
│   ├── INTEGRATION_TEST_GUIDE.md
│   ├── RESTRUCTURE.md                    ← this file
│   └── api-examples/{valkeyry-ipaas, valkeyry-config}.http
├── valkeyry-ipaas/                      ← 96 Java classes, full feature set
│   ├── Dockerfile
│   ├── pom.xml
│   ├── README.md
│   ├── frontend/                         ← React 19 admin console
│   │   └── src/components/admin/{Copilot,Dlq,Topology,Metrics,MultiPublish}Panel.jsx
│   ├── local-dev/                        ← docker-compose, AI bridge, observability
│   ├── load-tests/
│   └── src/{main,test}/java/io/valkeyry/ipaas/
│       ├── api/, publish/, consumer/, dlq/, queue/
│       ├── broker/, routing/, interceptor/
│       ├── copilot/                       ← OperatorCopilotController + ChatService + Toolset
│       ├── claimcheck/, file/, storage/
│       ├── config/, security/, secret/, vault/
│       ├── domain/, repository/
│       └── metrics/, retention/
├── valkeyry-config/                     ← 47 Java classes, schema registry
│   ├── Dockerfile
│   ├── pom.xml
│   ├── README.md
│   └── src/{main,test}/...
└── valkeyry-config-plugin/              ← 30 Java classes, Maven+Gradle plugins
    ├── plugin-core/
    ├── maven-plugin/
    └── gradle-plugin/
```

## How to verify

```bash
cd /app/valkeyry-ecosystem
# 1. Reactor build — should compile clean
mvn -DskipTests clean install

# 2. Unit + slice tests (no Docker required)
mvn -pl valkeyry-config-plugin/plugin-core -Dtest='!*MockServerTest' test
mvn -pl valkeyry-config -Dtest='*SliceTest,*FingerprintTest,*ValidatorTest,*WebhookPublisherTest' test

# 3. Full ecosystem integration (needs Docker)
mvn -pl valkeyry-config -Dtest=ValkeyryConfigEcosystemIntegrationTest test
mvn -pl valkeyry-ipaas  -Dtest=BrokerAdaptersSmokeTest test
```

## Feature inventory — `valkeyry-ipaas`

All present:

- ✅ Reactive publish API (single-tenant + multi-tenant batch)
- ✅ Reactive subscribe API (SSE, claim-check auto-resolve)
- ✅ Polymorphic broker adapter — Kafka / RabbitMQ / ActiveMQ
- ✅ S3 / MinIO / Azure Blob claim-check externalisation
- ✅ Valkey Lua token-bucket rate limiter
- ✅ OIDC + LDAP + API-key bifurcated auth
- ✅ Dynamic workspace authorization (per-tenant policy)
- ✅ Vault-backed reactive secret manager
- ✅ DLQ management API + UI
- ✅ Topology builder (declarative routing graph) + executor
- ✅ Dynamic consumer manager
- ✅ Queue management API
- ✅ Reactive file streaming with claim-tickets
- ✅ AI enrichment interceptor (Spring AI + bridge)
- ✅ Retention cleanup scheduler
- ✅ Metrics + Prometheus
- ✅ React 19 admin console with **CopilotPanel**, DlqPanel, MetricsPanel, MultiPublishPanel, TopologyBuilder
- ✅ **Operator Copilot** — Spring AI ChatClient agent, multi-provider (Ollama default; OpenAI / Anthropic activate via API key), streaming SSE, tool-calling (`/api/v1/copilot/{chat,stream,tools,providers,session/{id}/history,session/{id}/reset}`)
- ✅ `local-dev/docker-compose.yaml` — one-command sandbox

## Feature inventory — `valkeyry-config`

- ✅ Schema registry (Postgres JSONB, no runtime DDL)
- ✅ JSON Schema 2020-12 validation
- ✅ SHA-256 idempotency guard + version trail
- ✅ Append-only audit ledger
- ✅ Audit webhook fan-out (SIEM-friendly, HMAC signed)
- ✅ Static **Audit Console** at `/audit/` (React 18 single-file, zero build)
- ✅ Writer-role gate (technical users automatic, OIDC needs claim)
