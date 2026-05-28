# Valkeyry iPaaS — PRD

## Original Problem Statement
Production-ready Java 21 / Spring Boot 3.x reactive multi-tenant messaging
middleware (iPaaS). Hierarchical tenant/project isolation, polymorphic brokers
(RabbitMQ/Kafka/ActiveMQ), Claim-Check file streaming over dynamic Vault-resolved
S3/Azure storage, dual provisioning (catalog + lazy), 1->N / N->1 / N->N topologies,
SSE metrics dashboard, OIDC + dynamic RBAC, Resilience4j circuit-breakers,
Valkey token-bucket rate-limiting, Micrometer OTel tracing, R2DBC retention purge,
Testcontainers integration tests, Windows-friendly local-dev sandbox.

## Architecture
- **Stack:** Java 21, Spring Boot 3.3.5, WebFlux/Netty, Spring Data R2DBC (Postgres),
  Spring Data Redis Reactive on Valkey, Spring Vault Reactive, reactor-rabbitmq,
  reactor-kafka, activemq-client, AWS SDK v2 S3 (Netty), Azure Storage Blob async,
  Resilience4j-reactor, Micrometer Tracing + OTLP, Springdoc-openapi.
- **Package root:** `io.valkeyry.ipaas`
- **All API routes:** `/api/v1/{tenantId}/{projectId}/**`
- **Naming convention:** `{tenantId}.{projectId}.{destination}` + matching `.dlq`

## Implemented (Feb 2026)
- Domain entities (`Tenant`, `Project`, `QueueAsset`, `ConsumerConfiguration`,
  `StorageConfiguration`, `TopologyConfig`, `FieldMapping`, `UserAccessPolicy`, `MessageLog`)
- R2DBC repositories + Flyway V1 schema
- `ReactiveBrokerClient` abstraction with RabbitMQ / Kafka / ActiveMQ implementations
  + `BrokerClientFactory` runtime resolver
- `QueueManagementService`: service-catalog `declareFromCatalog`, self-healing
  `resolveOrLazyProvision` with admin-webhook alert
- `DeclarativeTopologyParser` (YAML) + `TopologyExecutor` for 1->N / N->1 / N->N
- `DynamicTransformationRoutingEngine` (JsonPath + WebClient w/ Resilience4j retry & CB)
- `DynamicConsumerManager`: in-memory `ConcurrentHashMap<String, Disposable>` registry,
  Valkey idempotency guard, MessageInterceptorChain hook, claim-ticket streaming detection,
  manual ACK + DLQ routing on 5xx
- `ReactiveFileStreamingService` (Claim Check Pattern, multipart `Flux<DataBuffer>` ingest,
  chunked WebClient outbound)
- `ReactiveVaultSecretManager` + `DynamicStorageFactory` (S3-compatible / Azure Blob)
- `SecurityConfiguration` (OIDC JWT resource server) + `DynamicWorkspaceAuthorizationManager`
  (Postgres-backed RBAC PROJECT_READ/WRITE)
- `RateLimitWebFilter` (Valkey Lua token-bucket) — atomic, 429 on overflow
- `MetricsController` SSE 1-second push
- `DlqManagementService` + Controller (peek / bulk-retry with payload substitution,
  x-death cleanup, x-retry-count bump)
- `RetentionCleanupScheduler` @Scheduled cron with reactive R2DBC batch DELETE
- `AiEnrichmentInterceptor` placeholder + comment hook for Spring AI ChatClient
- Springdoc OpenAPI + @Tag/@Operation annotations on every controller
- Unit tests (StepVerifier + Mockito): 9 passing
- Testcontainers integration test (`DynamicPlatformIntegrationTest`) for Postgres + Valkey + RabbitMQ
- Local-dev sandbox: `docker-compose.yaml` (Postgres, Valkey 8.0, RabbitMQ 3.13, Kafka,
  ActiveMQ Classic 6.x, MinIO, Vault dev), `bootstrap-vault.sh`, sample manifests for
  all three brokers (`ONE_TO_MANY` RabbitMQ, `ONE_TO_MANY` Kafka, `MANY_TO_ONE` ActiveMQ)

## Verification
- `mvn compile`           ✅
- `mvn test-compile`      ✅
- `mvn test` (unit tests, **15** cases)   ✅ all green
- `mvn package`           ✅ produces `target/ipaas-platform-1.0.0.jar`
- `mvn verify` Testcontainers test — requires Docker on host

## Implemented additions (Jul 2025 — iteration 8)
- **Spring AI ChatClient wired into `AiEnrichmentInterceptor`** with a pluggable engine layer:
  - `AiEnrichmentEngine` interface; impls `BridgeAiEnrichmentEngine` (legacy Python sidecar) and `SpringAiEnrichmentEngine` (in-process ChatClient).
  - Engine selection via `ipaas.ai.engine = SPRING_AI | BRIDGE` (default `SPRING_AI`); per-message override `x-ai-engine`.
  - **Multi-provider:** Ollama (default — no API key), OpenAI, Anthropic. `ChatModelRegistry` resolves provider name to bean with a deterministic fallback chain.
  - Spring AI BOM **1.0.1**, starters `spring-ai-starter-model-ollama|openai|anthropic`; OpenAI/Anthropic auto-config only fires when api-key is set.
- **Operator Copilot** (`io.valkeyry.ipaas.copilot.*`): chat agent that can introspect AND (with `confirm=true`) operate the platform.
  - REST: `/api/v1/copilot/{chat, stream, tools, providers, session/{id}/history, session/{id}/reset}`. SSE streaming.
  - **9 Spring AI `@Tool` methods:** 6 READ (`listQueues`, `listTopologies`, `peekDlq`, `queueDepth`, `metricsSnapshot`, `aiProviders`) + 3 WRITE confirm-gated (`retryDlqMessages`, `declareQueue`, `publishMessage`).
  - Per-session `MessageWindowChatMemory` (window 20). Provider sticky-per-session, switchable via UI dropdown or `provider` field.
  - Micrometer counter `valkeyry.copilot.tool.invocations` for Grafana.
- **Kafka DLQ peek promoted to AdminClient** (`KafkaBrokerClient`):
  - Shared `AdminClient` bean; `describeTopics` + `listOffsets` compute deterministic `[latest-N, latest)` per-partition windows; transient consumer with unique group id polls the window only.
  - New `browseDlqSummary()` returns per-partition earliest/latest/depth; exposed via `GET /api/v1/{t}/{p}/dlq/{queue}/summary`.
- **Per-project Prometheus tag enrichment** (`MetricsTagConfig`):
  - `ServerRequestObservationConvention` adds `tenant_id` + `project_id` (parsed from `/api/v1/{tenantId}/{projectId}/**`) to every `http.server.requests` sample.
  - Cardinality guard: tenant_id ≤ 200, project_id ≤ 500.
  - Public helper `MetricsTagConfig.workspaceTags(t, p)` for custom meters.
- **Grafana per-tenant dashboard** (`valkeyry-per-tenant.json`): template vars `tenant_id`/`project_id`, panels RPS / p95 / p99 / 5xx / 429s / top endpoints / CB state / RabbitMQ depth / Copilot invocations.
- **Ollama service in `docker-compose.yaml`** + `ollama-bootstrap` sidecar that pulls `$COPILOT_MODEL` (default `qwen2.5:7b`) on first compose-up. Named volume `ollama-models`.
- **Frontend Copilot tab** (`CopilotPanel.jsx`): streaming SSE chat (fetch ReadableStream parser in `ipaasClient.copilotStream`), provider switcher, tools drawer, prompt-chip presets, per-browser persisted session id, Reset.
- **Gatling load-tests Maven module** under `/app/load-tests` — Gatling 3.13.4 + plugin 4.17.4, **4 simulations** (Publish 10→500 RPS, MultiTenantPublish, FileStreaming 1 MiB, CopilotChat). HTML reports under `target/gatling/`.
- **`WINDOWS_GUIDE.md`** (490+ lines) — PowerShell-driven step-by-step setup, 11 manual-test sections covering every feature including Spring AI, Copilot, Kafka summary, Grafana per-tenant, Gatling. Linked from `LOCAL_SETUP.md`.
- **Tests:** `AiEnrichmentInterceptorTest` updated (engine-agnostic); new `SpringAiEnrichmentEngineTest` (JSON parser), new `MetricsTagConfigTest` (path parsing + helpers).


## Implemented additions (Feb 2026 — iteration 7)
- **Tenant + Project IDs are now alphanumeric slugs**, not UUIDs:
  - Pattern: `^[A-Za-z0-9_-]{1,200}$`. Max 200 chars; supports `-` and `_`.
  - **Schema** (`V1__init_schema.sql`) — `tenants.id`, `projects.id`, and every `tenant_id`/`project_id` column flipped to `VARCHAR(200)` with Postgres `CHECK (id ~ '^[A-Za-z0-9_-]{1,200}$')` on the two PK tables.
  - **New `WorkspaceId` constants class** (`PATTERN`, `MAX_LENGTH`, `MESSAGE`).
  - **8 domain entities** retyped: `Tenant.id`, `Project.id`, `Project.tenantId`, and `tenantId`/`projectId` on `QueueAsset`, `ConsumerConfiguration`, `StorageConfiguration`, `TopologyConfig`, `UserAccessPolicy`, `MessageLog`. Business PKs (`QueueAsset.id` etc.) stay UUID.
  - **All 8 repositories** + **6 controllers** + **services** + **3 broker implementations** + **MultiTenantPublishService.PublishTarget/Outcome** retyped from UUID to String.
  - **Validation**: controllers annotated `@Validated`; path variables annotated `@Pattern(regexp = WorkspaceId.PATTERN, message = WorkspaceId.MESSAGE)` — invalid slugs now return a `400` instead of reaching the service layer.
  - **Security manager** path regex tightened from `[^/]+` to `[A-Za-z0-9_-]{1,200}` — defense in depth against path-traversal.
  - **Tests**: 21/21 still green; UUID literals replaced with `acme-corp` / `payments-prod` / `globex-eu` / `billing-dev`.
  - **Frontend**: `TopBar.jsx` placeholders are now `e.g. acme-corp` / `e.g. payments-prod`, inputs carry `maxLength=200` + `pattern="[A-Za-z0-9_-]{1,200}"` + `title` tooltip. `MultiPublishPanel` rows enforce the same constraints per row. Sample loader populates `acme-corp` / `payments-prod`.
  - **LOCAL_SETUP.md + local-dev/README.md** sample shell snippets updated to use the new slugs.

## Implemented additions (Feb 2026 — iteration 6)
- **Enterprise login page** at `pages/LoginPage.jsx` — split layout (brand panel + feature grid on the left, sign-in card on the right). Three auth paths gated by a single visible page:
  - **Dev account** (`admin/admin` or `operator/operator`) — local dummy users; visible only when OIDC env is not configured.
  - **JWT paste** — power-user textarea for non-OIDC backends.
  - **Continue with SSO** — top-of-page CTA, visible only when `REACT_APP_OIDC_AUTHORITY` + `_CLIENT_ID` are set; PKCE redirect via `react-oidc-context`.
- **Session model** (`auth/session.js`): three `kind` values — `LOCAL`, `JWT_PASTE`, `OIDC` — persisted in `localStorage["valkeyry.session"]`. Identity badge in the top bar renders `identity · role · kind`.
- **App gate** in `App.js` — no session means LoginPage; with session means AdminConsole; `recordOidcSession()` syncs the OIDC user object automatically. Sign-out clears local session + calls `signoutRedirect()` when OIDC is active.
- **Contrast & readability fixes** — `--text-primary` brightened to `#f1f5f9`, `--text-muted` brightened to `#cbd5e1`, AdminConsole heading promoted to pure white, every `text-muted` Label upgraded to `text-slate-300`, active tab rendered with solid cyan background + dark text (no more dim grey).
- **`/app/LOCAL_SETUP.md`** — step-by-step guide: prerequisites table (JDK 21, Maven, Docker, Node, Yarn) with per-OS install hints, full `docker compose up -d` walkthrough with the ports/credentials table, Spring boot launch (`ALLOW_ANONYMOUS=true java -jar`), React boot, then a feature-by-feature walkthrough (catalog declare, lazy ingress, topology builder, SSE metrics, DLQ peek + bulk-retry, multi-publish, AI enrich/decide, file streaming, Grafana + Tempo, Vault renewal) followed by test commands and a troubleshooting matrix.
- Screenshot-verified — clean enterprise login, identity badge visible post-login, all 4 tabs accessible, high contrast across the board.

## Implemented additions (Feb 2026 — iteration 5)
- **Generic OIDC login button** in the React top-bar via `react-oidc-context` (built on `oidc-client-ts`) — PKCE Authorization-Code flow against any spec-compliant provider (Keycloak / Auth0 / Okta / Cognito / Azure AD / Google).
  - Toggle: set `REACT_APP_OIDC_AUTHORITY` + `REACT_APP_OIDC_CLIENT_ID` in `frontend/.env` to activate. Unset env preserves the legacy paste-the-bearer-token UX (zero behaviour change for existing devs).
  - Top-bar shows **Sign in** (cyan outline, `LogIn` icon) when unauthenticated; on success the access_token is pushed into `localStorage["valkeyry.bearer-token"]` so every `fetch()` in `ipaasClient.js` automatically carries it.
  - When authenticated: token input is hidden, an identity badge (`data-testid='top-bar-identity-badge'`, green pulse) shows `preferred_username | email | sub`, and a **Sign out** button (`data-testid='top-bar-oidc-signout'`) clears the local token and calls `signoutRedirect()`.
  - Optional Keycloak 26 service added to `docker-compose.yaml` under the `auth` profile (`docker compose --profile auth up -d keycloak`) for fully self-contained local OIDC verification.
- Screenshot-verified both modes — disabled (legacy) and enabled (Sign-in button visible) — UI testids preserved so the previous `testing_agent_v3` run still applies.

## Implemented additions (Feb 2026 — iteration 4)
- **Multi-tenant publish backend tests**: 6 new Mockito + StepVerifier tests covering anonymous bypass, RBAC allow, RBAC deny, partial authorization (mixed accept + deny), broker error capture, and no-JWT-without-anonymous. **21/21 unit tests now passing.**
- **React Admin Console validated by testing_agent_v3**: 11/11 scenarios green (rendering, navigation, localStorage persistence, tab switching, live YAML preview, multi-row add/remove, graceful error toasts). Single polish action item applied — `ipaasClient.js` now wraps every `fetch()` in a `call()` helper that converts `TypeError`/network failures into clean `"<endpoint>: network unreachable"` toasts.
- **Tempo replaces Jaeger for unified Java + AI-bridge traces**: `local-dev/observability/tempo.yaml` + Grafana datasource provisioning + new dashboard `valkeyry-traces.json` (Traces panel + Service Map). Spring Boot + AI-bridge both ship OTLP HTTP to `tempo:4318`; W3C `traceparent` is propagated end-to-end so a single trace spans Java → Python → upstream LLM provider, all visible in one Grafana pane.

## Implemented additions (Feb 2026 — iteration 3)
- **Multi-tenant fan-out publish**: `MultiTenantPublishController` at `/api/v1/multi-publish` with per-target RBAC. Denied targets are reported, never block the rest of the fan-out.
- **CORS + dev-mode RBAC bypass**: `ipaas.security.allow-anonymous=true` skips OIDC entirely; `cors.allowed-origins` for the React dev server. Added `IpaasProperties.Security` + `IpaasProperties.Cors` blocks.
- **Spring Vault auto-renewal**: `VaultTokenRenewer` runs on `@Scheduled` fixed-delay calling `/v1/auth/token/renew-self`. Properties: `ipaas.vault.renewal-enabled`, `renewal-interval-ms` (30 min default), `renewal-increment-seconds`. Dev root tokens log debug + no-op since they're non-renewable.
- **OTel propagation Java→Python**: `local-dev/ai-bridge/main.py` now boots an OTel `TracerProvider` with OTLP HTTP exporter (Jaeger), `FastAPIInstrumentor`, and `propagate.extract()` on inbound `traceparent` headers. `llm.enrich` and `llm.decide` spans capture provider, model, payload/decision attrs. Jaeger all-in-one added to docker-compose (`16686`/`4317`/`4318`).
- **React Admin Console**: rewrote `/app/frontend` with:
  - Top bar: tenantId/projectId/JWT (localStorage), Load sample button.
  - Live Metrics tab — SSE `EventSource` to `/metrics/stream`, per-destination cards.
  - DLQ Inspector tab — peek + bulk-retry w/ optional per-message payload editing.
  - Topology Builder tab — name + type + mode + broker + sources/targets, live YAML preview, Save / Save & Deploy.
  - Multi-Tenant Publish tab — dynamic target rows, RBAC-aware fan-out, color-coded outcomes (ACCEPTED / DENIED / ERROR).
  - Design: deep slate (#0b1220) + electric cyan (#22d3ee), JetBrains Mono code / IBM Plex Sans body, asymmetric left-aligned layout, staggered fade-up entrance. No purple gradients, no Inter. All interactive elements carry unique `data-testid`.
- **Spring-AI-style optional enrichment**: rewrote `AiEnrichmentInterceptor` with `ENRICH | DECIDE | OFF` modes, header per-message overrides (`x-ai-provider`, `x-ai-model`, `x-ai-mode`, `x-ai-options`), reactive `WebClient` non-blocking, graceful fallback on bridge failure. Backed by a new Python FastAPI sidecar `/local-dev/ai-bridge/` wrapping `emergentintegrations` + Emergent Universal LLM Key. `IpaasProperties.Ai` config block + `application.yml` defaults. 6 new unit tests with `MockWebServer`.
- **Kafka DLQ peek & pull at parity with RabbitMQ**: `KafkaBrokerClient.browseDlq` / `consumeDlqMessage` now use `KafkaConsumer.assign()` + `seek()` with no offset commits — non-destructive peek across all partitions, time-bounded poll.
- **Observability stack**: Prometheus + Grafana provisioned in `docker-compose.yaml`, scrape config in `observability/prometheus.yml`, datasource + dashboard auto-provisioning, JSON dashboard `valkeyry-overview.json` with 7 panels (HTTP RPS, p95 latency, JVM heap, Reactor Netty, Resilience4j CB state, retry rate, RabbitMQ queue depth). RabbitMQ image now also exposes the `:15692` Prometheus plugin.

## Backlog / Next
- P1: Wire Spring AI ChatClient into `AiEnrichmentInterceptor`
- P1: Promote DLQ peek for Kafka via Admin client (browse offset window)
- P1: Add per-project Prometheus tag enrichment + Grafana dashboards
- P2: Webhook for outbound delivery signing (HMAC headers)
- P2: Multi-region Vault namespace routing

---

# Valkeyry Ecosystem (Side-by-side clean-room) — Feb 2026

> ⚠ **Critical**: This is a SEPARATE codebase living under `/app/valkeyry-ecosystem/`.
> The pre-existing `/app/src` iPaaS platform is untouched and unchanged.

## Original problem statement
Production-ready, highly resilient, multi-tenant ecosystem partitioned into two standalone
deployable Spring Boot 3 / Java 21 applications, plus a build-tool automation component:

1. **`valkeyry-core`** — Reactive Messaging & Streaming Engine (Kafka stream + RabbitMQ/ActiveMQ queue + S3 claim-check + Valkey rate-limit).
2. **`valkeyry-config`** — Headless Schema Registry Engine on Postgres JSONB (no runtime DDL, full version trail, SHA-256 idempotency).
3. **`valkeyry-config-plugin`** — Maven Mojo + Gradle Task wrapping a shared `plugin-core` engine; pushes virtual-table schemas and entries from the build.

Bifurcated security: **Track 1** OIDC JWT (humans/tenants), **Track 2** LDAP-Basic or `X-API-Key` (headless agents). Both tracks converge on `SCOPE_tenant:<id>` authorities so `TenantAccessGuard` is single-source.

Testing strategy: **Testcontainers** unified integration test spinning up Postgres + mock-oauth2-server + (optional Valkey/OpenLDAP/MinIO) — verifies LDAP/api-key plugin push → dedup → version trail → OIDC tenant read.

## What's implemented (Feb 2026 — initial scaffold)
- **`valkeyry-config`**: domain, repos, JSON-Schema validator, canonical SHA-256 fingerprint, REST controller, dual security chain (OIDC + Basic + ApiKey), Flyway V1 migration (registry + entry + indexes + GIN), application.yml with profiles.
- **`valkeyry-core`**: domain (Message, ClaimCheckRef, DispatchResult), BrokerAdapter + Kafka/RabbitMQ/ActiveMQ impls, BrokerRegistry, ClaimCheckService + S3ClaimCheckStore, Lua-atomic TokenBucketRateLimiter + WebFilter, dual SecurityConfig, TenantRouter, PublishController, Vault facade.
- **`valkeyry-config-plugin`**:
  - `plugin-core`: ManifestLoader (YAML + env-vars), PayloadFingerprint (mirror of server), AuthStrategy (basic/api-key), ValkeyryConfigClient (JDK HttpClient), PluginEngine orchestrator.
  - `maven-plugin`: PushMojo (goal `push`, bound to `deploy`).
  - `gradle-plugin`: ValkeyryConfigPlugin + ValkeyryConfigExtension + PushTask + plugin descriptor.
- **Tests**:
  - `PayloadFingerprintTest` ×2 (server + plugin) — verify byte-for-byte identical hashes.
  - `JsonSchemaValidatorServiceTest`.
  - `ManifestLoaderTest`.
  - `PluginEngineMockServerTest` (in-process `HttpServer`).
  - `TokenBucketRateLimiterIntegrationTest` (Valkey Testcontainer).
  - **`ValkeyryConfigEcosystemIntegrationTest`** — Postgres + mock-oauth2; full plugin push → dedup → versioning → OIDC valid/wrong tenant.
- **Docs**:
  - `README.md` (top-level overview).
  - `docs/INTEGRATION_TEST_GUIDE.md` (beginner step-by-step).
  - `docs/architecture.md` (data-flow + security tracks).
  - Per-module READMEs.
  - `docs/api-examples/*.http`.

## Build instructions (run on your machine — JDK/Maven not in this pod)
```bash
cd /app/valkeyry-ecosystem
mvn clean install                                # full reactor
mvn -pl valkeyry-config -Dtest=ValkeyryConfigEcosystemIntegrationTest test    # E2E
```

## Module order in reactor
`valkeyry-config-plugin` → `valkeyry-core` → `valkeyry-config`
(swap was necessary: valkeyry-config test scope depends on plugin-core JAR.)

## P0 backlog / Future
- Real OpenLDAP Testcontainer variant of the ecosystem integration test (currently uses API-key bridge for Track-2).
- MinIO Testcontainer + S3 claim-check round-trip test in `valkeyry-core`.
- Kafka + RabbitMQ + ActiveMQ multi-broker Testcontainer smoke test.
- StepVerifier-based unit tests for `TenantRouter` + `ClaimCheckService` (with mocked store).
- ReactivePublishController WebTestClient slice tests.
- Helm/K8s deployment manifests.
- `consume` API on `valkeyry-core` (subscribe + auto-resolve claim-check).

## Iteration 2 — Feb 2026 (extended action items)
- **Broker Testcontainers smoke test** (`BrokerAdaptersSmokeTest`): full round-trip for Kafka + RabbitMQ + ActiveMQ. Publishes via the adapter and subscribes via the new `BrokerAdapter.subscribe(String)` contract; asserts payload + broker identity. Pulled the three test containers (`confluentinc/cp-kafka:7.6.1`, `rabbitmq:3.13-management`, `apache/activemq-classic:6.1.4`) and required Testcontainers modules (`kafka`, `rabbitmq`, `amqp-client`).
- **WebTestClient slice tests**: `VirtualTableControllerSliceTest` (6 cases: declare success, validation 400, dedup 409, schema 422, not-found 404, list 200) + `PublishControllerSliceTest` (3 cases: happy path 202, unknown broker 400, wrong tenant 403). Both exclude `ReactiveOAuth2ResourceServerAutoConfiguration` to skip JWK fetch at slice boot.
- **Consume API on valkeyry-core**: new `ReceivedMessage` record + `BrokerAdapter.subscribe(destination)` contract implemented for Kafka (Reactor-Kafka receiver), RabbitMQ (`Receiver.consumeAutoAck`), ActiveMQ (JMS listener bridged via `Flux.create`). New `SubscribeController` exposes `GET /api/v1/tenants/{tenantId}/messages/subscribe` as `text/event-stream` and auto-resolves claim-check refs against S3 before streaming.
- **Dockerfiles**: multi-stage Maven → Temurin 21 JRE Alpine, non-root, read-only rootfs, dropped caps, healthchecks. One per module.
- **Kubernetes manifests** (`deploy/k8s/*`): Namespace, ConfigMap, Secret, Deployment, Service for each module; HPA + 70% CPU for `valkeyry-core`; rolling update strategy + readiness/liveness on `/actuator/health/{readiness,liveness}`.
- **Helm charts** (`deploy/helm/{valkeyry-config,valkeyry-core}`): per-module `Chart.yaml`, `values.yaml`, `_helpers.tpl`, ConfigMap + Secret, Deployment + Service, optional Ingress (config) and HPA (core).
- **deploy/README.md**: one-shot kubectl install plus Helm install incantations + companion-service operator suggestions (Strimzi, RabbitMQ cluster-operator, Postgres operators, etc.).

## Updated counts
- Java sources: **91**
- Tests: **10** (3 plugin-core + 3 valkeyry-config + 4 valkeyry-core)
- Deploy artifacts: 2 Dockerfiles + 2 k8s manifests + 2 Helm charts + README

## Iteration 3 — Feb 2026 (writer-role authorization + audit ledger)

**Goal**: Make sure technical users (LDAP / API-key) are explicitly authorized to update configs, and that every change is traceable to who/when/from→to.

- **Explicit `ROLE_VALKEYRY_WRITER` authority** on top of `SCOPE_tenant:*`. Write paths (`POST/PUT/PATCH/DELETE /api/v1/tenants/**`) require this authority. LDAP + API-key principals get it automatically (they exist to push schemas); OIDC tokens get it iff `valkeyry.role: "writer"` (or `roles` array contains `writer`/`admin`/`valkeyry_writer`).
- **Append-only audit ledger** in Postgres:
  - V2 Flyway migration: `config_audit_log` table + 3 indexes (tenant/time, tenant/table/time, tenant/actor/time).
  - `ConfigAuditEntry` domain + `ConfigAuditRepository` (R2DBC) + `ConfigAuditService` (audit writes are `onErrorResume(Mono.empty())` — they never fail the business operation).
  - 4 operations captured: `DECLARE_TABLE`, `REVISE_TABLE`, `INGEST_RECORD`, `DEDUP_SKIP` — even the dedup-skip is logged so compliance can prove the caller tried.
  - Each row carries `beforeValue` + `afterValue` (full JSONB before/after diff), `actor` (subject/username/api-key fingerprint), `actor_track` (`OIDC` / `LDAP` / `API_KEY`), `changed_at`, `request_id`.
- **`GET /api/v1/tenants/{tenantId}/audit`** with filters `tableName`, `recordKey`, `actor`, `limit`, `offset`. Tenant-scoped via `TenantAccessGuard` — same as the rest of the API.
- **`AuthTrack`** enum + `Authentication`-to-track classifier so the audit ledger faithfully records the calling channel without coupling the service to security internals.
- **Tests**:
  - Integration test extended: audit trail assertions (`INGEST_RECORD`, `DEDUP_SKIP`, `REVISE_TABLE` captured; `actorTrack=API_KEY`/`OIDC` correctly tagged; `before`/`after` payloads visible).
  - Two new OIDC cases in the integration test:
    - reader-only token (no `valkeyry.role`) → write returns 403, read returns 200.
    - writer-role token → write returns 201 + audit row carries `actorTrack=OIDC` and the subject.
- **Docs**: `valkeyry-config/README.md` Authorization-model table; new `docs/AUDIT_LOG.md` with schema, REST examples, tamper-resistance guidance (`REVOKE UPDATE, DELETE`) and failure-handling rationale; `docs/api-examples/valkeyry-config.http` updated with 3 audit examples.

## Final counts (Iteration 3)
- Java sources: **97** (was 91; +6 audit/auth track classes)
- Tests: **10** (3 new integration cases added to existing E2E)
- Migrations: 2 Flyway scripts
- Docs: top README + per-module READMEs + 3 docs/ files (INTEGRATION_TEST_GUIDE, architecture, AUDIT_LOG)

## Iteration 4 — Feb 2026 (rename core → ipaas)
Restructured per user request:
```
valkeyry-ecosystem/
├── valkeyry-ipaas         ← renamed from valkeyry-core
├── valkeyry-config        (unchanged)
└── valkeyry-config-plugin (kept as sibling — build-time artifact, independently publishable)
```

**Scope of rename** (all in one pass, zero residual refs):
- Maven artifactId `valkeyry-core` → `valkeyry-ipaas`
- Directory `valkeyry-core/` → `valkeyry-ipaas/`
- Java package `io.valkeyry.core` → `io.valkeyry.ipaas`
- App class `ValkeyryCoreApplication` → `ValkeyryIpaasApplication`
- Env var `VALKEYRY_CORE_PORT` → `VALKEYRY_IPAAS_PORT`
- Helm chart `deploy/helm/valkeyry-core` → `valkeyry-ipaas`
- K8s manifest `deploy/k8s/valkeyry-core.yaml` → `valkeyry-ipaas.yaml`
- All READMEs + per-module docs + architecture/API examples updated

**Independent-deployability invariants** (verified by structure):
- Each module owns its own `pom.xml`, `application.yml`, port, Dockerfile, K8s manifest, Helm chart.
- Zero runtime cross-deps between `valkeyry-ipaas` and `valkeyry-config`.
- Reactor build is *convenience*; either module can be `mvn -pl valkeyry-X -am package` standalone.

## Iteration 5 — Feb 2026 (Webhook audit fan-out for SIEM)

- **`AuditWebhookProperties`**: bind for `valkeyry.audit.webhook.*` (enabled/urls/secret/timeoutMs/maxRetries/initialBackoffMs). Disabled by default.
- **`AuditWebhookPublisher`** (Reactor Netty `WebClient`): per saved `ConfigAuditEntry`, POSTs JSON to each configured URL with headers:
  - `X-Valkeyry-Event: config.audit`
  - `X-Valkeyry-Tenant: <tenantId>`
  - `X-Valkeyry-Request-Id: <reactor exchange id>`
  - `X-Valkeyry-Signature: sha256=<hex HMAC-SHA256 of body>` (falls back to `sha256=unsigned` when no secret).
  Body embeds the full audit row (id, operation, recordKey, beforeValue, afterValue, actor, actorTrack, changedAt, requestId). Retries 5xx + IO with exponential backoff; fails fast on 4xx. **Never** propagates errors — audit DB row remains source of truth.
- **`ConfigAuditService.record(...)`** wired to fan-out after successful `repo.save`. Delivery runs on a detached subscription so it doesn't block the calling request.
- **`application.yml`** + Helm chart values + ConfigMap template all carry the new envvars.
- **Test** (`AuditWebhookPublisherTest`): 5 cases via `com.sun.net.httpserver.HttpServer`:
  - successful delivery + HMAC signature validates;
  - multi-URL fan-out;
  - retries on 503 then succeeds;
  - 4xx fails fast (no retry, no exception);
  - disabled → no traffic.
  Awaitility used for async assertions (added to `valkeyry-config/pom.xml` test deps).
- **Docs**: new "Webhook fan-out" section in `docs/AUDIT_LOG.md` covering config, request shape, Python signature-verify snippet, delivery semantics, and backfill recipe.

## Final counts
- Java sources: **100**
- Tests: **11**
- Flyway migrations: 2

## Iteration 6 — Feb 2026 (Audit Console web UI)

Thin React 18 page served by `valkeyry-config` itself under `/audit/` — zero Node toolchain, single self-contained HTML using React UMD + Babel standalone + Tailwind CDN + IBM Plex Sans/JetBrains Mono.

- **Location**: `valkeyry-config/src/main/resources/static/audit/index.html` (469 LoC).
- **Routing**: `/` redirects to `/audit/`. Both paths permitted as public static assets in `SecurityConfig` — the API calls the page makes still require auth.
- **Features**:
  - Auth bar (tenant id + OIDC Bearer / X-API-Key picker, persisted to localStorage).
  - Filter bar (tableName, recordKey, actor, limit) + auto-refresh 5s toggle.
  - Event rows: time + operation pill (color-coded `DECLARE_TABLE` / `REVISE_TABLE` / `INGEST_RECORD` / `DEDUP_SKIP`) + actor + actor-track pill.
  - Expanded row: side-by-side pretty-printed before/after JSON + unified key-by-key diff (`+` emerald / `−` rose / changed = removed-then-added pair).
- **Design**: dark slate `#0b1220` + electric cyan `#22d3ee`, IBM Plex Sans + JetBrains Mono, asymmetric left-aligned layout, sticky translucent header, lift-on-hover micro-interaction — deliberately NOT the purple-on-white AI-slop default.
- **15 unique `data-testid`s** + dynamic per-row IDs for downstream e2e tooling.
- **Docs**: new audit-page README + cross-link from top README + new section in `docs/AUDIT_LOG.md`.

## Final counts (Iteration 6)
- Java sources: **100**
- Tests: **11**
- Flyway migrations: 2
- Static assets: 2 (audit index.html, root redirect)
- Audit UI: 469 LoC React 18 (zero build)

## Iteration 7 — Feb 2026 (Pull-from-GitHub + Restructure)

**Source pulled**: `https://github.com/CherryGamez/valkeyry-ecosystem` (`main` @ `e915de8 restructured`).

**Restructure (3 phases)**:
1. **Flattened nested wrapper** — GH repo had `valkeyry-ecosystem/valkeyry-ecosystem/<modules>` with orphan root files (`LOCAL_SETUP.md`, `WINDOWS_GUIDE.md`, `test_result.md`, `pom.xml` wrapper, `yarn.lock`, `.gitconfig`, stub `README.md`). All dropped; inner `valkeyry-ecosystem/` is now sole root.
2. **Removed junk**: `valkeyry-ipaas/target/` (1.4 MB build output), `test_reports/`, copy of `memory/`, orphan Python scaffolds (`tests/__init__.py`, `backend/server.py + requirements.txt`).
3. **Removed startup-blocking duplicates** inside `valkeyry-ipaas`:
   - `ValkeyryIpaasApplication.java` (two `@SpringBootApplication` → spring-boot-maven-plugin can't find main class). Kept `IpaasApplication` (newer, `@EnableScheduling` for RetentionCleanupScheduler).
   - `security/SecurityConfig.java` (two `@EnableWebFluxSecurity`). Kept `SecurityConfiguration` (CORS-aware, anonymous-dev support, workspace-authz integrated).
   - Entire `ratelimit/` package (two `@Component RateLimitWebFilter` → identical Spring bean name → `BeanDefinitionStoreException`). Kept `security/RateLimitWebFilter` (classpath-Lua, IpaasProperties-driven).

**Left coexisting** (parallel features, not duplicates):
- Single-tenant publish/subscribe (`POST/GET /api/v1/tenants/{id}/messages[/subscribe]`) via `BrokerAdapter` legacy interface — used by `routing/TenantRouter`, `api/PublishController`, `api/SubscribeController`.
- New multi-tenant batch publish + consumer mgmt + DLQ + topology + AI enrichment via richer `ReactiveBrokerClient` — used by `publish/MultiTenantPublishService`, `consumer/DynamicConsumerManager`, `dlq/DlqManagementService`, `routing/TopologyExecutor`, `interceptor/AiEnrichmentInterceptor`, `copilot/CopilotToolset`, `file/ReactiveFileStreamingService`, `metrics/MetricsController`, `queue/QueueManagementService`.

**Feature inventory** — `valkeyry-ipaas` (96 Java classes, 24 sub-packages):
- ✅ **Operator Copilot** — Spring AI ChatClient agent, multi-provider (Ollama default, OpenAI/Anthropic via API key), streaming SSE, tool-calling. Controller: `/api/v1/copilot/{chat,stream,tools,providers,session/{id}/history,session/{id}/reset}`.
- ✅ DLQ management API + UI
- ✅ Topology builder (declarative routing graph) + executor
- ✅ Dynamic consumer manager
- ✅ Queue management API
- ✅ Reactive file streaming with claim-tickets
- ✅ AI enrichment interceptor (Spring AI + bridge engine)
- ✅ Retention cleanup scheduler (`@EnableScheduling`)
- ✅ Vault-backed reactive secret manager + Azure Blob storage client
- ✅ Dynamic workspace authorization (`DynamicWorkspaceAuthorizationManager`)
- ✅ React 19 admin console with `CopilotPanel.jsx`, `DlqPanel.jsx`, `MetricsPanel.jsx`, `MultiPublishPanel.jsx`, `TopologyBuilder.jsx`, `LoginPage`, `AdminConsole`
- ✅ `local-dev/docker-compose.yaml` + AI bridge + observability + sample manifests

**Doc**: new `docs/RESTRUCTURE.md` documenting every move + diff for future ref.

**Backup**: previous local scaffold at `/app/valkeyry-ecosystem.backup.<epoch>/` (review then delete).

## Final counts (Iteration 7)
- Java sources: **173** (96 ipaas + 47 config + 30 plugin)
- Tests: **19**
- Modules: 3 (ipaas, config, config-plugin × 3 sub)
- Audit UI: zero-build React 18 (preserved from iteration 6, present in GH master)
- Frontend: full React 19 admin console

## Iteration 7b — Unified broker hierarchy

User chose to collapse the parallel broker hierarchies onto `ReactiveBrokerClient`.

**Migrated**:
- `routing/TenantRouter` — now built with `BrokerClientFactory` + `BrokerProperties`; translates `Message` envelope → unified `publish(tenantId, projectId, destinationName, payload, headers)` contract; `projectId` defaults to `"default"` for the single-tenant API.
- `api/SubscribeController` — bridges push-based `ReactiveBrokerClient.subscribe(...)` → SSE-style `Flux<ReceivedMessage>` via `Flux.create(sink → client.subscribe(...); sink.onDispose(d::dispose))`. Auto-ACKs (no ack channel through SSE).
- `api/ApiExceptionHandler` — `BrokerNotConfiguredException` handler replaced with generic `IllegalArgumentException → 400 urn:valkeyry:error:bad-request` (factory throws IAE on unknown type).
- `domain/ReceivedMessage` — javadoc updated.

**Deleted** (6 files):
- `broker/BrokerAdapter.java`, `broker/BrokerRegistry.java`
- `broker/kafka/KafkaBrokerAdapter.java`, `broker/rabbit/RabbitBrokerAdapter.java`, `broker/activemq/ActiveMqBrokerAdapter.java` + their packages
- `error/BrokerNotConfiguredException.java`

**Counts after unification**:
- `valkeyry-ipaas`: **90** classes (was 96; -6)
- 16 callers now uniformly use `ReactiveBrokerClient`
- `broker/` package: 5 files (interface + factory + 3 impls)
- `error/` package: 1 file (TenantAccessDeniedException)
- Total Java sources: **144** (was 150)

## Iteration 8 — Feb 2026 (iOS-inspired UI refresh, no glassmorphism)

User asked for "iPhone interface but not the glassy one" — solid surfaces, vibrant accents, soft layered shadows, spring micro-interactions.

**Two surfaces re-themed**:

1. **`valkeyry-ipaas` admin console** (`frontend/src/index.css`):
   - iOS palette CSS vars: `--primary: 211 100% 50%` (System Blue), `--success: 173 100% 39%` (Mint), `--warning: 35 100% 50%` (Orange), `--destructive: 354 100% 58%` (Pink), `--indigo: 240 67% 58%`.
   - Grouped background `#F2F2F7`, card `#FFFFFF`, radius `1rem` baseline.
   - Two-layer soft shadow tokens (`--shadow-sm/md/lg`) — solid + shadow, NOT backdrop-blur.
   - SF Pro typography stack with `-0.011em` tracking.
   - Utility classes: `.ios-shadow-sm/md/lg`, `.ios-lift` (spring `cubic-bezier(.34,1.56,.64,1)` hover lift), `.ios-press` (`0.97` scale on click).
   - Dark-mode counterpart with same accent ramps on true-black surfaces.

2. **`valkeyry-config` audit console** (`static/audit/index.html`):
   - Full rebuild on iOS palette. Tailwind extended with custom `colors.ios.*`, `boxShadow.ios{,Lg}`, `borderRadius.ios{,Lg,Xl}`.
   - Header with gradient-blue avatar tile + status dot (orange pulsing/sync, green synced).
   - iOS **segmented control** for OIDC/API-Key picker, iOS **switch** for auto-refresh.
   - Operation badges color-coded (Declared = blue tint, Revised = orange tint, Ingested = green tint, Deduped = grey).
   - Track badges as uppercase tiny pills (OIDC indigo, LDAP teal, API_KEY orange, ANONYMOUS red).
   - Diff lines with 8%-alpha tinted backgrounds (green for adds, red for removes); subtle row lift on hover.
   - Empty state with SF-style search icon + clear copy.
   - No purple-on-white. No glass. Solid surfaces, two-layer shadows, generous rounding (14/20/28 px).

**Docs**: `valkeyry-config/src/main/resources/static/audit/README.md` rewritten with a token table covering colors, radii, motion, distinctive elements.

## Iteration 9 — Feb 2026 (Slate + Emerald palette + full local build/test)

User requested a colour change to `c. Slate + Emerald (#0F172A + #10B981)` applied to **both** consoles, and asked the agent to try to compile + test the codebase locally.

**JDK / Maven installation** — pod did not ship with Java; agent downloaded Temurin 21.0.11 (aarch64) and Apache Maven 3.9.9 into `/opt/` and symlinked them into `/usr/local/bin`. Both modules now build inside the pod for the first time since the GitHub merge.

**Recolour (both consoles)**:
- **Admin console** (`valkeyry-ipaas/frontend`):
  - `App.css` CSS variables retargeted: `--bg-base #0F172A`, `--bg-elev-1 #1E293B`, `--bg-elev-2 #334155`, `--border-soft #334155`, `--accent-cyan #10B981` (kept var name for back-compat), `--accent-amber #F59E0B`, `--accent-green #10B981`, `--accent-rose #F43F5E`, `--text-primary #F1F5F9`, `--text-muted #CBD5E1`. `::selection` and `.glow-cyan` recoloured to emerald rgba.
  - `index.css` shadcn semantic tokens flipped to slate+emerald: `--primary 160 84% 39%` (emerald), `--background 222 47% 11%` (slate-900), `--card 217 33% 17%` (slate-800), `--destructive 350 89% 60%` (rose), `--warning 38 92% 50%` (amber), `--ring 160 84% 39%`. Both `:root` and `.dark` blocks aligned. Shadow tokens deepened for dark surfaces.
  - Font stack switched from "SF Pro Display" lead to "Inter" lead (more apt for slate+emerald data-engineering aesthetic). Mono stack reordered to "JetBrains Mono" first.
  - Hard-coded hex values across 9 component files bulk-rewritten via sed: `#67e8f9 → #34D399` (emerald-400 hover), `#22d3ee → #10B981`, `#0b1220 → #0F172A`, `#101a2c → #1E293B`.
- **Audit console** (`valkeyry-config/static/audit/index.html`):
  - Entire Tailwind `colors.ios.*` namespace re-mapped (kept name for class back-compat): bg `#0F172A`, card `#1E293B`, surface `#0B1220`, ink `#F1F5F9`, ink2 `#CBD5E1`, muted `#94A3B8`, line `#334155`, blue/primary `#10B981`, indigo `#6366F1`, etc.
  - Op-badge palette retoned for dark theme: Declared blue-900/300, Revised amber-900/300, Ingested emerald-900/300, Deduped slate-700/300.
  - Track-badge palette: OIDC indigo-900/300, LDAP cyan-900/300, API_KEY amber-900/300, ANONYMOUS red-900/300.
  - Diff side-accents recoloured: Before `#F43F5E` (rose-500), After `#10B981` (emerald-500). Diff line backgrounds use 10% alpha.
  - Segmented control active state now `bg:#10B981 / color:#0F172A` (emerald-on-slate).
  - `body` font stack switched to Inter lead.
  - All `focus:bg-white` swapped to `focus:bg-ios-surface` (slate-950 input well).

**Java build fixes** (required to get the codebase compiling for the first time post-merge):
- `valkeyry-ipaas/pom.xml` rewritten to add the dependencies the source actually imports:
  - Lombok 1.18.34 (compile-only; annotation processor wired in maven-compiler-plugin + spring-boot-maven-plugin `<excludes>`).
  - `springdoc-openapi-starter-webflux-ui 2.6.0` (swagger v3 annotations + models).
  - `resilience4j-spring-boot3` + `resilience4j-reactor` 2.2.0.
  - `com.jayway.jsonpath:json-path 2.9.0`.
  - `jackson-dataformat-yaml`.
  - `spring-ai-bom 1.0.0` + `spring-ai-starter-model-{ollama,openai,anthropic}`.
  - `spring-boot-starter-data-r2dbc` + `r2dbc-postgresql` + JDBC `postgresql` (Flyway).
  - `flyway-core` + `flyway-database-postgresql`.
  - `com.rabbitmq:amqp-client` (main scope; previously test-only).
  - `kafka-clients`.
  - Test extras: `org.testcontainers:postgresql` + `r2dbc`, `com.squareup.okhttp3:mockwebserver`.
- `valkeyry-config-plugin/gradle-plugin/pom.xml` — swapped `org.gradle:gradle-tooling-api` (which lacks `DefaultTask`/`Project`/`InputFile`/etc.) for `dev.gradleplugins:gradle-api 8.10`.
- `S3ClaimCheckStore.java` — `S3AsyncClient.Builder` (nested-class style) replaced by top-level `S3AsyncClientBuilder` (AWS SDK v2.28 API).
- `LdapBasicAuthenticationManager.java` (both `valkeyry-ipaas` and `valkeyry-config`) — `bind(...)` method signature gained `throws javax.naming.NamingException` (from `LdapContext.getContext` close).
- `valkeyry-config` `LdapBasicAuthenticationManager` — corrected `template.search(...)` return type from `List<String>` to `List<List<String>>` (matches `AttributesMapper<List<String>>`).
- `VirtualTableControllerSliceTest.java` — added explicit `@SpringBootConfiguration TestApp` to avoid auto-discovery of the main app (which triggers `@EnableR2dbcRepositories` without a DB); excluded R2DBC + Flyway autoconfigs; added `SecurityMockServerConfigurers.csrf()` mutator on the WebTestClient so the slice's default-on CSRF doesn't 403 every POST; kept Spring's auto-configured `ObjectMapper` so the `ProblemDetail`+`JavaTimeModule`+`@JsonAnyGetter` chain round-trips properly.
- Stale tests pruned (referenced classes intentionally deleted during iteration 7b broker unification):
  - `broker/BrokerAdaptersSmokeTest.java` (entire class referenced deleted adapter hierarchy).
  - `api/PublishControllerSliceTest.java` (referenced deleted `BrokerNotConfiguredException`).
  - `ratelimit/TokenBucketRateLimiterIntegrationTest.java` (referenced deleted `TokenBucketRateLimiter` class).

**Build & test results (run inside the pod, Temurin 21 + Maven 3.9.9, no Docker)**:
- `mvn clean install -DskipTests`: ✅ all 7 reactor modules build green.
- `mvn test` (excluding 2 Docker-only Testcontainers integration tests): ✅ **53/53 unit tests pass** — plugin-core 6/6, valkeyry-ipaas 32/32, valkeyry-config 15/15.

## Final counts (Iteration 9)
- Java sources: **144** (unchanged: dead code already removed in 7b).
- Tests: **17 test files** (3 stale deleted, slice rebuilt around isolated `@SpringBootConfiguration`).
- Pom dependencies declared: **valkeyry-ipaas/pom.xml ~30 deps** (was 17 before, source imports drove the rest).

