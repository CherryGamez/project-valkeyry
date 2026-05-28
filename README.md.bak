# Valkeyry Ecosystem

> A multi-module, fully reactive **iPaaS + headless schema-registry + build-tool plugin** platform built on **Spring Boot 3.3.5 · Java 21 · Project Reactor · R2DBC · WebFlux**.

Repository: `CherryGamez/valkeyry-ecosystem`  ·  Branch: `valkeyry-ecosystem`
License: **Apache License 2.0**
Maven coordinates: `io.valkeyry:valkeyry-ecosystem-parent:1.0.0-SNAPSHOT`

---

## Table of contents

1. [What this is](#1-what-this-is)
2. [Repository layout](#2-repository-layout)
3. [Architecture at a glance](#3-architecture-at-a-glance)
4. [Modules — what each one does](#4-modules--what-each-one-does)
5. [Prerequisites](#5-prerequisites)
6. [Building the project (Maven cheat-sheet)](#6-building-the-project-maven-cheat-sheet)
7. [Running the project](#7-running-the-project)
8. [Testing the project](#8-testing-the-project)
9. [Configuration reference (environment variables)](#9-configuration-reference-environment-variables)
10. [REST API surface](#10-rest-api-surface)
11. [Local development stack (docker-compose)](#11-local-development-stack-docker-compose)
12. [Container images & Kubernetes / Helm deployment](#12-container-images--kubernetes--helm-deployment)
13. [Using the Maven & Gradle plugins downstream](#13-using-the-maven--gradle-plugins-downstream)
14. [Observability](#14-observability)
15. [Project conventions & build order](#15-project-conventions--build-order)
16. [Troubleshooting](#16-troubleshooting)
17. [Further reading](#17-further-reading)

---

## 1. What this is

Valkeyry is composed of **three deployable products** that share one Maven reactor:

| Product | What it does | Port |
|---|---|---|
| **`valkeyry-ipaas`** | Multi-tenant **reactive messaging engine**: polyglot publish/subscribe over **Kafka / RabbitMQ / ActiveMQ**, **Valkey/Redis** rate-limited WebFilter, **S3 / Azure Blob** claim-check pattern for large payloads, **Vault** secret management, **OIDC + LDAP dual auth**, **Spring AI** chat-driven Operator Copilot, Resilience4j circuit-breakers, SSE live-metrics. | **8080** |
| **`valkeyry-config`** | **Headless schema registry**: EAV-JSONB virtual-table store on PostgreSQL, SHA-256 idempotent dedup, version trail, JSON-Schema validation, audit ledger with signed webhook fan-out, ROLE_VALKEYRY_WRITER RBAC. | **8081** |
| **`valkeyry-config-plugin`** | **Build-tool automation** to push `valkeyry-config.yaml` manifests into the registry: a Maven Mojo (`valkeyry-config:push`, bound to the `deploy` phase) and a Gradle plugin (`valkeyryConfigPush` task), sharing a Spring-free `plugin-core` library. | n/a |

Plus an **operator-facing React 18 Admin Console** (CRA + craco + Tailwind + shadcn primitives) and a **Gatling 3.13** load-test harness.

---

## 2. Repository layout

```
.
├── pom.xml                                  ← Standalone (legacy) build for ipaas-platform
├── README.md
├── LOCAL_SETUP.md                           ← Step-by-step local walkthrough
├── WINDOWS_GUIDE.md                         ← Windows / PowerShell variant
├── docs/
│   ├── architecture.md
│   ├── AUDIT_LOG.md
│   ├── INTEGRATION_TEST_GUIDE.md
│   ├── RESTRUCTURE.md
│   └── api-examples/{valkeyry-config.http, valkeyry-core.http}
├── deploy/
│   ├── README.md
│   ├── helm/{valkeyry-ipaas, valkeyry-config}/   ← Helm charts (Chart.yaml + values + templates)
│   └── k8s/{valkeyry-ipaas.yaml, valkeyry-config.yaml}
└── valkeyry-ecosystem/                      ← ★ The Maven reactor root ★
    ├── pom.xml                              (groupId io.valkeyry, packaging pom, 3 modules)
    ├── valkeyry-config-plugin/              (aggregator, packaging pom)
    │   ├── plugin-core/                     (jar, no Spring) — ManifestLoader, PayloadFingerprint, ValkeyryConfigClient
    │   ├── maven-plugin/                    (maven-plugin) — PushMojo, goalPrefix `valkeyry-config`
    │   └── gradle-plugin/                   (jar) — ValkeyryConfigPlugin + PushTask + Extension
    ├── valkeyry-ipaas/                      (Spring Boot fat jar)
    │   ├── Dockerfile                       (multi-stage, Temurin 21 JRE Alpine, non-root, ZGC)
    │   ├── src/main/java/io/valkeyry/ipaas/ (api, broker, claimcheck, config, consumer,
    │   │                                     copilot, dlq, domain, error, file, interceptor,
    │   │                                     metrics, publish, queue, repository, retention,
    │   │                                     routing, secret, security, storage, vault)
    │   ├── src/main/resources/{application.yml, db/migration/V1__init_schema.sql}
    │   ├── frontend/                        ← React 18/19 Admin Console
    │   ├── load-tests/                      ← Gatling — 4 simulations
    │   └── local-dev/                       ← docker-compose stack + ai-bridge + observability
    └── valkeyry-config/                     (Spring Boot fat jar)
        ├── Dockerfile
        ├── src/main/java/io/valkeyry/config/  (api, config, domain, error, repo, security,
        │                                       service, validation)
        └── src/main/resources/{application.yml, db/migration/V1__…sql, V2__audit_log.sql,
                               static/{index.html, audit/index.html}}
```

> ⚠️ The repo contains **two `pom.xml` files**: the top-level one is a legacy single-module Spring Boot project (`ipaas-platform`). The **canonical, current build** is the reactor at **`valkeyry-ecosystem/pom.xml`** — **all Maven commands below should be run from that directory** unless stated otherwise.

---

## 3. Architecture at a glance

```
                          ┌────────────────────────────┐
                          │  Humans (UI / CLI / curl)  │
                          └─────────────┬──────────────┘
                                        │ OIDC Bearer JWT
                                        ▼
        ┌──────────────────────────────────────────────────────────┐
        │                  valkeyry-config                          │
        │  WebFlux + R2DBC + Flyway + JSON-Schema validator         │
        │  VirtualTableRegistry  ←→  VirtualTableEntry (JSONB)      │
        │  • schema versions   • SHA-256 dedup   • is_latest trail  │
        └────────┬────────────────────────────────────────┬─────────┘
                 │ HTTP Basic / API-key                   │ R2DBC
                 ▼                                        ▼
        ┌──────────────────────┐                  ┌──────────────┐
        │ Build-tool plugin    │                  │ PostgreSQL   │
        │ Maven Mojo /         │                  │ JSONB + GIN  │
        │ Gradle Task          │                  └──────────────┘
        └──────────────────────┘

                          ┌────────────────────────────┐
                          │  Producers (services/UIs)  │
                          └─────────────┬──────────────┘
                                        │ OIDC / Basic
                                        ▼
        ┌──────────────────────────────────────────────────────────┐
        │                  valkeyry-ipaas                          │
        │  WebFlux + Reactor-Kafka + Reactor-RabbitMQ + JMS        │
        │  + Reactive Redis (Valkey) token-bucket WebFilter        │
        │  + S3 / Azure-Blob Claim-Check externalisation           │
        │  + Spring AI Copilot (Ollama / OpenAI / Anthropic)       │
        └────────┬────────────────┬────────────────┬───────────────┘
                 ▼                ▼                ▼
            ┌─────────┐      ┌─────────┐      ┌──────────┐
            │  Kafka  │      │ RabbitMQ│      │ ActiveMQ │
            └─────────┘      └─────────┘      └──────────┘
```

Full diagrams and design notes live in **[`docs/architecture.md`](docs/architecture.md)**.

---

## 4. Modules — what each one does

| Module | Artifact | Packaging | Description |
|---|---|---|---|
| Reactor parent | `io.valkeyry:valkeyry-ecosystem-parent:1.0.0-SNAPSHOT` | `pom` | Aggregates the three top-level modules; centralises Java 21 toolchain + dependency-management BOMs (Spring Boot, Testcontainers, AWS SDK). |
| Plugin aggregator | `io.valkeyry:valkeyry-config-plugin:1.0.0-SNAPSHOT` | `pom` | Aggregator for the plugin trio. |
| Plugin core | `io.valkeyry:valkeyry-config-plugin-core:1.0.0-SNAPSHOT` | `jar` | Zero-Spring shared lib: `ManifestLoader`, `PayloadFingerprint` (SHA-256 canonical), `AuthStrategy`, `ValkeyryConfigClient` (JDK HttpClient), `PluginEngine`. 6 unit tests. |
| Maven plugin | `io.valkeyry:valkeyry-config-maven-plugin:1.0.0-SNAPSHOT` | `maven-plugin` | `PushMojo` exposing goal **`valkeyry-config:push`**, bound to the `deploy` phase. Requires Maven 3.9.0+. |
| Gradle plugin | `io.valkeyry:valkeyry-config-gradle-plugin:1.0.0-SNAPSHOT` | `jar` | `ValkeyryConfigPlugin` + `PushTask` + `ValkeyryConfigExtension`. |
| iPaaS engine | `io.valkeyry:valkeyry-ipaas:1.0.0-SNAPSHOT` | `jar` (Spring Boot) | The runnable reactive messaging engine. **~144 Java sources, ~32 unit tests across 9 packages.** Runs on **port 8080**. |
| Schema registry | `io.valkeyry:valkeyry-config:1.0.0-SNAPSHOT` | `jar` (Spring Boot) | Runnable headless registry. ~15 unit tests. Runs on **port 8081** (`VALKEYRY_CONFIG_PORT`). |
| Load tests (standalone) | `io.valkeyry:valkeyry-load-tests:1.0.0-SNAPSHOT` | `jar` | Gatling 3.13.4 — 4 simulations: `PublishSimulation`, `MultiTenantPublishSimulation`, `FileStreamingSimulation`, `CopilotChatSimulation`. **Not part of the reactor.** |

### iPaaS engine — sub-packages

```
api/         REST: PublishController, SubscribeController, MultiPublishController, ApiExceptionHandler
broker/      ReactiveBrokerClient + Kafka/RabbitMQ/ActiveMQ impls + BrokerClientFactory
claimcheck/  S3ClaimCheckStore (AWS SDK v2 async) + ClaimCheckService
config/      IpaasProperties, BrokerProperties, ClaimCheckProperties, SpringAiConfig,
             MetricsTagConfig, RateLimitProperties, VaultConfig
consumer/    DynamicConsumerManager (Disposable registry, idempotency, ACK/DLQ)
copilot/     Spring AI ChatClient + @Tool methods + REST controllers + ChatModelRegistry
dlq/         DlqManagementService + DlqController (peek + bulk-retry)
domain/      Message, ClaimCheckRef, DispatchResult, Tenant, Project, QueueAsset,
             ConsumerConfiguration, StorageConfiguration, TopologyConfig, FieldMapping,
             UserAccessPolicy, MessageLog, ReceivedMessage
file/        ReactiveFileStreamingService + ClaimTicket (chunked Flux<DataBuffer>)
interceptor/ MessageInterceptorChain + AiEnrichmentInterceptor (Spring AI or Bridge engine)
metrics/    MetricsController (SSE 1s), MetricsTagConfig (per-tenant Prom tags)
publish/    MultiTenantPublishService (RBAC-aware fan-out)
queue/      QueueManagementService (catalog + lazy provisioning)
repository/ R2DBC: 8 reactive repos
retention/  RetentionCleanupScheduler (@Scheduled)
routing/    TenantRouter, TopologyExecutor, DynamicTransformationRoutingEngine,
             DeclarativeTopologyParser
secret/      ReactiveSecretManager + ReactiveVaultSecretManager
security/    SecurityConfiguration, DynamicWorkspaceAuthorizationManager,
             TenantAccessGuard, LdapBasicAuthenticationManager,
             RateLimitWebFilter (Valkey Lua atomic bucket)
storage/     DynamicStorageFactory, ReactiveStorageClient (S3 / Azure Blob)
vault/       VaultTokenRenewer, VaultSecretProvider
```

### Schema-registry — sub-packages

```
api/         VirtualTableController, AuditController, ApiExceptionHandler
config/      R2DBC + Security wiring
domain/      VirtualTableRegistry, VirtualTableEntry, ConfigAuditEntry
error/       IdempotentDuplicateException, SchemaValidationException,
             VirtualTableNotFoundException, TenantAccessDeniedException
repo/        R2DBC reactive repos
security/    OIDC + LDAP + ApiKey + TenantAccessGuard + RoleResolver
             (ROLE_VALKEYRY_WRITER)
service/     VirtualTableService, ConfigAuditService, AuditWebhookPublisher
validation/  JsonSchemaValidatorService, PayloadFingerprint (SHA-256 canonical)
```

---

## 5. Prerequisites

| Tool | Required version | Why | Install |
|---|---|---|---|
| **JDK 21** (Temurin) | 21 | `<release>21</release>` enforced by `maven-compiler-plugin`; reactor uses ZGC at runtime | `winget install EclipseAdoptium.Temurin.21.JDK` · `brew install --cask temurin@21` · `apt install openjdk-21-jdk` |
| **Maven** | **3.9.0+** | `valkeyry-config-maven-plugin` declares `<prerequisites><maven>3.9.0</maven></prerequisites>` | `winget install Apache.Maven` · `brew install maven` · `apt install maven` |
| **Docker** | 20+ | Required for `mvn verify` (Testcontainers) and the `local-dev` compose stack | docker.com / Docker Desktop |
| **Node.js** | 20+ | React Admin Console only | nodejs.org |
| **Yarn** | 1.22+ | Frontend dep manager | `npm i -g yarn` |
| **curl / jq** | any | Smoke-testing the REST API | usually pre-installed |
| **Gradle** | 8.10+ (optional) | Only if you want to consume the Gradle plugin variant | gradle.org |

Verify:

```bash
java -version          # OpenJDK 21.x
mvn -version           # Apache Maven 3.9.x — uses JDK 21
docker info            # daemon running
node -v && yarn -v
```

---

## 6. Building the project (Maven cheat-sheet)

> ✅ **All commands below assume `cwd = valkeyry-ecosystem/`** (the reactor root containing `<packaging>pom</packaging>` and 3 modules).

### 6.1 Compile only (fastest sanity check)

```bash
mvn -DskipTests clean compile
```

### 6.2 Full reactor build, produces runnable fat jars (skip tests)

```bash
mvn -DskipTests clean package
```

Artifacts after success:

```
valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar          ← Spring Boot fat jar (runnable)
valkeyry-config/target/valkeyry-config-1.0.0-SNAPSHOT.jar        ← Spring Boot fat jar (runnable)
valkeyry-config-plugin/plugin-core/target/*.jar
valkeyry-config-plugin/maven-plugin/target/*.jar                 ← maven-plugin (installable)
valkeyry-config-plugin/gradle-plugin/target/*.jar
```

### 6.3 Install everything to `~/.m2` (required before using `valkeyry-config:push`)

```bash
mvn -DskipTests clean install
```

### 6.4 Run unit tests (no Docker needed)

```bash
mvn test
```

### 6.5 Run unit + integration tests (Testcontainers — **Docker daemon required**)

```bash
mvn verify
```

Spins up Postgres, Valkey/Redis, RabbitMQ, Kafka, and Vault containers per the `*IT.java` integration suites.

### 6.6 Build a single module (with its upstream dependencies)

```bash
# Only the iPaaS engine
mvn -pl valkeyry-ipaas -am -DskipTests clean package

# Only the schema registry
mvn -pl valkeyry-config -am -DskipTests clean package

# Only the plugin trio
mvn -pl valkeyry-config-plugin -am -DskipTests clean install
```

Flags:
- `-pl` / `--projects` → target a specific module
- `-am` / `--also-make` → also build its upstream reactor dependencies

### 6.7 Skip / exclude a module

```bash
mvn -pl '!valkeyry-config-plugin/gradle-plugin' -DskipTests clean package
```

### 6.8 Parallel build

```bash
mvn -T 1C clean package -DskipTests    # 1 thread per CPU core
```

### 6.9 Other useful build flags

```bash
mvn dependency:tree -pl valkeyry-ipaas      # resolved dependency graph
mvn versions:display-dependency-updates     # list outdated deps
mvn -o clean package                        # offline mode (uses cached ~/.m2)
mvn -X clean package                        # debug logging
mvn help:effective-pom -pl valkeyry-ipaas   # print fully-resolved POM
```

### 6.10 Build container images

```bash
# From the reactor root (Dockerfiles do a fresh in-container Maven build)
docker build -f valkeyry-ipaas/Dockerfile  -t valkeyry-ipaas:local  .
docker build -f valkeyry-config/Dockerfile -t valkeyry-config:local .
```

Both images use a two-stage build: Maven on `maven:3.9-eclipse-temurin-21`, runtime on `eclipse-temurin:21-jre-alpine`, non-root `valkeyry` user, ZGC enabled (`-XX:+UseZGC -XX:MaxRAMPercentage=75`), Docker HEALTHCHECK against `/actuator/health`.

---

## 7. Running the project

### 7.1 Spring Boot apps via Maven (no jar needed)

```bash
# iPaaS engine on http://localhost:8080
ALLOW_ANONYMOUS=true mvn -pl valkeyry-ipaas -am spring-boot:run

# Schema registry on http://localhost:8081
mvn -pl valkeyry-config -am spring-boot:run
```

### 7.2 Production-style — from the packaged fat jar

```bash
java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar
java -jar valkeyry-config/target/valkeyry-config-1.0.0-SNAPSHOT.jar
```

For zero-friction local dev (disables OIDC + RBAC):

```bash
ALLOW_ANONYMOUS=true java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar
```

Smoke tests:

```bash
curl -s http://localhost:8080/actuator/health      # iPaaS engine
curl -s http://localhost:8081/actuator/health      # schema registry
curl -s http://localhost:8080/v3/api-docs | jq .info
```

Swagger UI: **http://localhost:8080/swagger-ui.html**

### 7.3 React Admin Console

```bash
cd valkeyry-ipaas/frontend
yarn install
yarn start                                   # http://localhost:3000
```

Default local credentials (LDAP-mock seeded by `local-dev`):

| Username | Password | Role |
|---|---|---|
| `admin` | `admin` | ADMIN |
| `operator` | `operator` | OPERATOR |

For OIDC, set in `frontend/.env`:

```env
REACT_APP_OIDC_AUTHORITY=http://localhost:8081/realms/ipaas
REACT_APP_OIDC_CLIENT_ID=valkeyry-console
REACT_APP_OIDC_REDIRECT_URI=http://localhost:3000/
```

### 7.4 Enabling AI enrichment in the consumer pipeline

```bash
AI_ENABLED=true \
AI_ENGINE=SPRING_AI \
AI_SPRING_PROVIDER=openai \
OPENAI_API_KEY=sk-... \
ALLOW_ANONYMOUS=true \
java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar
```

`AI_ENGINE=SPRING_AI` (default) talks to **Ollama / OpenAI / Anthropic** via Spring AI starters. `AI_ENGINE=BRIDGE` routes through the FastAPI sidecar in `local-dev/ai-bridge` (requires an Emergent Universal Key).

---

## 8. Testing the project

| Goal | Command | Needs Docker? |
|---|---|---|
| Unit tests only | `mvn test` | No |
| All tests in a single module | `mvn -pl valkeyry-ipaas test` | No |
| Integration tests (Testcontainers) | `mvn verify` | **Yes** |
| Integration tests for a single module | `mvn -pl valkeyry-config -am verify` | **Yes** |
| Single test class | `mvn -pl valkeyry-ipaas test -Dtest=PublishControllerTest` | depends |
| Single test method | `mvn -pl valkeyry-ipaas test -Dtest=PublishControllerTest#publishesSuccessfully` | depends |
| Gatling load tests, all simulations | `mvn -f valkeyry-ipaas/load-tests/pom.xml clean gatling:test` | No (but iPaaS must be running) |
| Gatling, one simulation | `mvn -f valkeyry-ipaas/load-tests/pom.xml gatling:test -Dgatling.simulationClass=io.valkeyry.loadtests.PublishSimulation` | No |
| Gatling against a remote target | `mvn -f valkeyry-ipaas/load-tests/pom.xml gatling:test -Dipaas.base-url=https://staging.example.com` | No |
| Skip tests during a build | append `-DskipTests` (skip run) or `-Dmaven.test.skip=true` (skip compile too) | — |

Surefire 3.5.1 runs unit tests (`*Test.java`); **Failsafe 3.5.1** runs `*IT.java` integration tests during the `integration-test` / `verify` phases.

Test counts (approximate):

- `plugin-core`: 6 tests · `valkeyry-ipaas`: ~32 across 9 packages · `valkeyry-config`: ~15
- **~53 total unit tests**, plus Testcontainers-backed integration suites.

Detailed integration playbook: **[`docs/INTEGRATION_TEST_GUIDE.md`](docs/INTEGRATION_TEST_GUIDE.md)**.

---

## 9. Configuration reference (environment variables)

All settings are externalised — defaults are in `application.yml` and overridable via env vars.

### 9.1 iPaaS engine (`valkeyry-ipaas/src/main/resources/application.yml`)

#### Core / server

| Env var | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP listen port |
| `ALLOW_ANONYMOUS` | `false` | Skip OIDC + RBAC (local dev only) |
| `FRONTEND_ORIGIN` | `http://localhost:3000` | CORS allowed origin |
| `PROCESSING_MODE` | `QUEUE` | `QUEUE` (manual ack) or `STREAMING` (consumer groups, offset replay) |
| `DEFAULT_BROKER` | `RABBITMQ` | `RABBITMQ` · `KAFKA` · `ACTIVEMQ` |

#### Databases & cache

| Env var | Default | Description |
|---|---|---|
| `R2DBC_URL` | `r2dbc:postgresql://localhost:5432/ipaas` | Reactive Postgres |
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/ipaas` | Flyway (blocking) |
| `R2DBC_USER` / `R2DBC_PASSWORD` | `ipaas` / `ipaas` | DB credentials |
| `VALKEY_HOST` / `VALKEY_PORT` | `localhost` / `6379` | Valkey (Redis-compat) for rate limit + idempotency |

#### Brokers

| Env var | Default |
|---|---|
| `RABBIT_HOST` · `RABBIT_PORT` · `RABBIT_USER` · `RABBIT_PASSWORD` | `localhost · 5672 · guest · guest` |
| `KAFKA_BOOTSTRAP` | `localhost:9092` |
| `ACTIVEMQ_URL` · `ACTIVEMQ_USER` · `ACTIVEMQ_PASSWORD` | `tcp://localhost:61616 · admin · admin` |

#### Security & secrets

| Env var | Default | Notes |
|---|---|---|
| `OIDC_ISSUER_URI` | `http://localhost:8080/realms/ipaas` | Keycloak / Auth0 / Okta / Cognito |
| `VAULT_URI` / `VAULT_TOKEN` | `http://localhost:8200` / `dev-root-token` | HashiCorp Vault dev mode |
| `VAULT_RENEW` / `VAULT_RENEW_MS` | `true` / `1800000` (30 min) | Token auto-renewal |

#### AI / Copilot

| Env var | Default | Description |
|---|---|---|
| `AI_ENABLED` | `false` | Master switch for AI enrichment of in-flight messages |
| `AI_ENGINE` | `SPRING_AI` | `SPRING_AI` (in-process) or `BRIDGE` (FastAPI sidecar) |
| `AI_MODE` | `ENRICH` | `ENRICH` · `DECIDE` · `OFF` |
| `AI_SPRING_PROVIDER` | `ollama` | `ollama` · `openai` · `anthropic` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Local-by-default |
| `AI_OLLAMA_MODEL` | `qwen2.5:7b` | |
| `OPENAI_API_KEY` | _empty_ | Required to activate the OpenAI starter |
| `AI_OPENAI_MODEL` | `gpt-4o-mini` | |
| `ANTHROPIC_API_KEY` | _empty_ | Required to activate the Anthropic starter |
| `AI_ANTHROPIC_MODEL` | `claude-3-5-sonnet-latest` | |
| `AI_BRIDGE_URL` | `http://localhost:8090` | FastAPI sidecar (BRIDGE engine) |
| `COPILOT_ENABLED` | `true` | Operator Copilot REST endpoints |
| `COPILOT_PROVIDER` | `ollama` | Default provider for chat sessions |
| `COPILOT_CONFIRM` | `true` | Require confirmation for destructive @Tool calls |

#### Operations

| Env var | Default | Description |
|---|---|---|
| `OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OpenTelemetry OTLP HTTP collector (Tempo) |
| `ADMIN_ALERT_WEBHOOK` | `http://localhost:9999/admin/alerts` | Outbound admin alerts |

### 9.2 Schema registry (`valkeyry-config/src/main/resources/application.yml`)

| Env var | Default | Description |
|---|---|---|
| `VALKEYRY_CONFIG_PORT` | `8081` | HTTP port |
| `VALKEYRY_CONFIG_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/valkeyry_config` | |
| `VALKEYRY_CONFIG_JDBC_URL` | `jdbc:postgresql://localhost:5432/valkeyry_config` | Flyway |
| `VALKEYRY_CONFIG_DB_USER` / `VALKEYRY_CONFIG_DB_PASS` | `valkeyry` / `valkeyry` | |
| `VALKEYRY_OIDC_ISSUER` | `http://localhost:8079/default` | |
| `VALKEYRY_LDAP_URL` | `ldap://localhost:1389` | |
| `VALKEYRY_LDAP_BASE` | `dc=valkeyry,dc=io` | |
| `VALKEYRY_LDAP_USER_DN` | `uid={0},ou=people,dc=valkeyry,dc=io` | |
| `VALKEYRY_LDAP_TENANT_ATTR` | `ou` | LDAP attribute mapped to tenantId |
| `VALKEYRY_LDAP_MANAGER_DN` / `VALKEYRY_LDAP_MANAGER_PW` | `cn=admin,dc=valkeyry,dc=io` / `admin` | |
| `VALKEYRY_API_KEYS` | _empty_ | CSV of `key:tenantA,tenantB` (disable in prod — use LDAP) |
| `VALKEYRY_AUDIT_WEBHOOK_ENABLED` | `false` | |
| `VALKEYRY_AUDIT_WEBHOOK_URLS` | _empty_ | CSV of webhook URLs |
| `VALKEYRY_AUDIT_WEBHOOK_SECRET` | _empty_ | HMAC-SHA256 signing secret |
| `VALKEYRY_AUDIT_WEBHOOK_TIMEOUT_MS` | `5000` | |
| `VALKEYRY_AUDIT_WEBHOOK_MAX_RETRIES` | `3` | |
| `VALKEYRY_AUDIT_WEBHOOK_BACKOFF_MS` | `200` | Initial back-off (exponential) |

Audit webhook signature schema → **[`docs/AUDIT_LOG.md`](docs/AUDIT_LOG.md)**.

---

## 10. REST API surface

### 10.1 iPaaS engine — base `http://localhost:8080`

| Method & path | Controller |
|---|---|
| `POST /api/v1/tenants/{tenantId}/messages` | `PublishController` |
| `GET  /api/v1/tenants/{tenantId}/messages/subscribe` (SSE) | `SubscribeController` |
| `POST /api/v1/multi-publish` | `MultiTenantPublishController` (RBAC-aware fan-out) |
| `POST /api/v1/{tenantId}/{projectId}/queues/declare` | `QueueManagementController` |
| `GET  /api/v1/{tenantId}/{projectId}/queues` | `QueueManagementController` |
| `POST /api/v1/{tenantId}/{projectId}/ingress/{destination}` | `QueueManagementController` (lazy provision) |
| `POST /api/v1/{tenantId}/{projectId}/consumers` | `ConsumerController` |
| `GET  /api/v1/{tenantId}/{projectId}/consumers` | `ConsumerController` |
| `POST /api/v1/{tenantId}/{projectId}/consumers/{consumerId}/{activate\|deactivate}` | `ConsumerController` |
| `POST /api/v1/{tenantId}/{projectId}/topologies` | `TopologyController` |
| `GET  /api/v1/{tenantId}/{projectId}/topologies` | `TopologyController` |
| `POST /api/v1/{tenantId}/{projectId}/topologies/{name}/{deploy\|undeploy}` | `TopologyController` |
| `POST /api/v1/{tenantId}/{projectId}/files/upload` (multipart, claim-check) | `FileStreamingController` |
| `GET  /api/v1/{tenantId}/{projectId}/dlq/{queueName}/messages` | `DlqManagementController` |
| `GET  /api/v1/{tenantId}/{projectId}/dlq/{queueName}/summary` | `DlqManagementController` |
| `POST /api/v1/{tenantId}/{projectId}/dlq/{queueName}/bulk-retry` | `DlqManagementController` |
| `GET  /api/v1/{tenantId}/{projectId}/metrics/stream` (SSE) | `MetricsController` |
| `POST /api/v1/copilot/chat` · `POST /api/v1/copilot/stream` | `OperatorCopilotController` |
| `GET  /api/v1/copilot/{tools,providers}` | `OperatorCopilotController` |
| `GET  /api/v1/copilot/session/{id}/history` · `POST /reset` | `OperatorCopilotController` |
| `GET  /actuator/{health,info,metrics,prometheus}` | Actuator |
| `GET  /swagger-ui.html` · `GET /v3/api-docs` | OpenAPI |

### 10.2 Schema registry — base `http://localhost:8081`

| Method & path | Controller |
|---|---|
| `GET  /api/v1/tenants/{tenantId}/tables` | `VirtualTableController` |
| `POST /api/v1/tenants/{tenantId}/tables` | Create / version a virtual table |
| `GET  /api/v1/tenants/{tenantId}/tables/{name}` | Latest schema |
| `POST /api/v1/tenants/{tenantId}/tables/{name}/entries` | Upsert single entry (idempotent by SHA-256) |
| `POST /api/v1/tenants/{tenantId}/tables/{name}/entries:batch` | Batch upsert |
| `GET  /api/v1/tenants/{tenantId}/tables/{name}/entries/{recordKey}` | Latest entry |
| `GET  /api/v1/tenants/{tenantId}/tables/{name}/entries/{recordKey}/history` | Version trail |
| `GET  /api/v1/tenants/{tenantId}/tables/{name}/entries` | List entries (latest) |
| `POST /api/v1/tenants/{tenantId}/tables/{name}/search` | JSON-Path search |
| `GET  /api/v1/tenants/{tenantId}/audit` | Audit ledger query |

Ready-to-run examples live in **[`docs/api-examples/valkeyry-core.http`](docs/api-examples/valkeyry-core.http)** and **[`valkeyry-config.http`](docs/api-examples/valkeyry-config.http)** — open in JetBrains/REST clients or `httpyac`.

---

## 11. Local development stack (docker-compose)

The `valkeyry-ipaas/local-dev/` folder spins up every external dependency.

```bash
cd valkeyry-ipaas/local-dev
docker compose up -d
./bootstrap-vault.sh             # seeds Vault dev with dummy S3 credentials
```

| Service | Endpoint | Credentials |
|---|---|---|
| Postgres 16 | `postgresql://localhost:5432/ipaas` | `ipaas` / `ipaas` |
| Valkey 8 | `redis://localhost:6379` | — |
| RabbitMQ | AMQP `:5672`, UI http://localhost:15672 | `guest` / `guest` |
| Kafka (+ Zookeeper) | `localhost:9092` | — |
| ActiveMQ | `tcp://localhost:61616`, UI http://localhost:8161 | `admin` / `admin` |
| MinIO (S3 API) | `:9000`, console http://localhost:9001 | `minioadmin` / `minioadmin` |
| Vault dev | http://localhost:8200 | token `dev-root-token` |
| Ollama | http://localhost:11434 | — |
| AI-Bridge (FastAPI) | http://localhost:8090 | reads `EMERGENT_LLM_KEY` |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3001 | `admin` / `admin` |
| Tempo (OTLP) | OTLP `:4318`, UI http://localhost:3200 | — |
| Keycloak (opt-in) | http://localhost:8081 — `--profile auth` | `admin` / `admin` |

Tear down:

```bash
docker compose down -v
```

The full feature-by-feature walkthrough (queue declare, lazy provision, topology builder, DLQ inspector, multi-tenant publish, AI enrichment, claim-check upload, Grafana dashboards, Vault renewal) is in **[`LOCAL_SETUP.md`](LOCAL_SETUP.md)** — and **[`WINDOWS_GUIDE.md`](WINDOWS_GUIDE.md)** for PowerShell users.

---

## 12. Container images & Kubernetes / Helm deployment

### 12.1 Build & push images

```bash
docker build -f valkeyry-config/Dockerfile -t ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT .
docker build -f valkeyry-ipaas/Dockerfile   -t ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT   .
docker push ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT
docker push ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT
```

### 12.2 Plain Kubernetes manifests

```bash
kubectl apply -f deploy/k8s/valkeyry-config.yaml
kubectl apply -f deploy/k8s/valkeyry-ipaas.yaml
```

Both assume namespace `valkeyry`. The config manifest creates it. **Edit the secrets** — defaults are placeholders.

### 12.3 Helm charts (recommended)

```bash
helm install vk-config deploy/helm/valkeyry-config \
    --namespace valkeyry --create-namespace \
    --set-string postgres.password=$(openssl rand -base64 32) \
    --set-string ldap.managerPass=$(openssl rand -base64 32)

helm install vk-core deploy/helm/valkeyry-ipaas \
    --namespace valkeyry \
    --set-string claimcheck.s3.accessKey=$AWS_ACCESS_KEY_ID \
    --set-string claimcheck.s3.secretKey=$AWS_SECRET_ACCESS_KEY
```

For production secrets, use **external-secrets**, **sealed-secrets**, or the **CSI secret-store** driver — **never** commit real values to Git.

### 12.4 Required upstream services

These charts **consume** but do not provision the following — bring your own or use one of these operators:

| Service | Suggested operator / chart |
|---|---|
| Postgres | zalando/postgres-operator · CrunchyData |
| Kafka | strimzi/strimzi-kafka-operator |
| RabbitMQ | rabbitmq/cluster-operator |
| ActiveMQ Classic | apache/activemq-artemis-operator |
| Valkey | upstream `valkey/valkey` StatefulSet or Bitnami chart |
| OpenLDAP | `bitnami/openldap` |
| MinIO | `minio/operator` |
| OIDC | Keycloak Operator · Auth0 · Okta |

Full deployment notes: **[`deploy/README.md`](deploy/README.md)**.

---

## 13. Using the Maven & Gradle plugins downstream

### 13.1 Install into your local repository first

```bash
mvn -DskipTests clean install        # at the reactor root
```

### 13.2 Maven consumer

In any downstream `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.valkeyry</groupId>
      <artifactId>valkeyry-config-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <executions>
        <execution>
          <phase>deploy</phase>
          <goals><goal>push</goal></goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Drop a `valkeyry-config.yaml` manifest next to your `pom.xml`, then:

```bash
mvn valkeyry-config:push       # explicit goal
mvn deploy                     # also pushes (bound to deploy phase)
```

Authentication options (driven by `plugin-core`'s `AuthStrategy`):

- **API key** — `-Dvalkeyry.apiKey=<key>` or `VALKEYRY_API_KEY` env
- **LDAP** — `-Dvalkeyry.user=<u> -Dvalkeyry.password=<p>` (HTTP Basic on the server)
- **OIDC** — set `VALKEYRY_OIDC_TOKEN`

### 13.3 Gradle consumer

```kotlin
plugins {
  id("io.valkeyry.config") version "1.0.0-SNAPSHOT"
}
valkeyryConfig {
  url      = "https://config.valkeyry.io"
  apiKey   = System.getenv("VALKEYRY_API_KEY")
  manifest = file("valkeyry-config.yaml")
}
```

Then:

```bash
./gradlew valkeyryConfigPush
```

---

## 14. Observability

| Surface | Where | Notes |
|---|---|---|
| **Health** | `GET /actuator/health` | Includes R2DBC `up` check |
| **Prometheus scrape** | `GET /actuator/prometheus` | Per-tenant tags via `MetricsTagConfig` |
| **Live SSE metrics** | `GET /api/v1/{tenant}/{project}/metrics/stream` | 1-second push to the Admin Console |
| **OpenTelemetry traces** | OTLP HTTP → `OTLP_ENDPOINT` (Tempo at `4318`) | Spring + AI-bridge spans stitched via W3C `traceparent` |
| **Grafana dashboards** | `local-dev` provisions them under `Valkeyry → …` | iPaaS Overview, Distributed Traces |
| **Logging** | Pattern: `%5p [traceId=%X{traceId:-},spanId=%X{spanId:-}]` | `io.valkeyry` at DEBUG in dev |
| **Resilience4j** | Circuit-breaker `outboundWebhook` (sliding window 20, fail-rate 50 %, 30 s open) + Retry (3 attempts, exponential ×2) | Exposed on `/actuator/metrics` |

---

## 15. Project conventions & build order

- **Reactor build order** (enforced by `<modules>` ordering):
  ```
  valkeyry-config-plugin/plugin-core
    → valkeyry-config-plugin/maven-plugin
    → valkeyry-config-plugin/gradle-plugin
    → valkeyry-ipaas
    → valkeyry-config              (plugin-core is a test-scope dep of its E2E IT)
  ```
- The reactor parent **does not** inherit from `spring-boot-starter-parent` — the build-tool sub-modules must not pull Spring Boot's `pluginManagement` (it would clash with `maven-plugin` / `gradle-api` packaging). Each Spring Boot module declares its own `<parent>spring-boot-starter-parent</parent>`.
- **`maven-plugin`** declares `<prerequisites><maven>3.9.0</maven></prerequisites>` — older Maven will refuse to build the reactor.
- **Lombok** is `provided`/`optional` and is excluded from the Spring Boot repackaged fat jars. Enable annotation processing in your IDE.
- **Flyway migrations**:
  - iPaaS: `valkeyry-ipaas/src/main/resources/db/migration/V1__init_schema.sql`
  - Config: `V1__schema_registry_init.sql`, `V2__audit_log.sql`
- **Spring profiles**: `valkeyry-config` defines a `test` profile that pre-loads an API key (`plugin-test-key:demo-tenant`) for the headless plugin integration test.

---

## 16. Troubleshooting

| Symptom | Fix |
|---|---|
| `mvn -version` shows Java < 21 | Set `JAVA_HOME` to a JDK 21 install; re-open shell |
| `Plugin requires Maven version 3.9.0` | Upgrade Maven (`brew upgrade maven` / `winget upgrade Apache.Maven`) |
| `Cannot find symbol` in IDE only | Enable **Lombok** annotation processing |
| `mvn verify` hangs pulling images | Ensure Docker daemon is up; pre-pull: `docker pull postgres:16 confluentinc/cp-kafka:7.6.0 rabbitmq:3.13-management valkey/valkey:8` |
| `401 Unauthorized` from `/api/v1/...` | Start iPaaS with `ALLOW_ANONYMOUS=true`, or insert a row into `user_access_policies` for your OIDC `sub` |
| `mvn valkeyry-config:push` "plugin not found" | Run `mvn install` at the reactor root first so the plugin lands in `~/.m2` |
| Gatling reports "Connection refused" | Start `valkeyry-ipaas` on `localhost:8080` before `gatling:test` |
| Port already in use (8080 / 5432 / 6379 / …) | Stop the conflicting service or edit `local-dev/docker-compose.yaml` |
| AI-Bridge `502 LLM enrich error` | Set `EMERGENT_LLM_KEY` in `local-dev/.env`, check network egress |
| Grafana dashboards empty | Prometheus must reach `host.docker.internal:8080` — already wired in compose; on Linux ensure `--add-host=host.docker.internal:host-gateway` |
| Tempo "no traces found" | Confirm `OTLP_ENDPOINT=http://localhost:4318/v1/traces` and Tempo is running |
| OIDC redirect loop on the console | `redirect_uri` registered with the IdP must match `REACT_APP_OIDC_REDIRECT_URI` **exactly** (trailing slash included) |

---

## 17. Further reading

- **[`docs/architecture.md`](docs/architecture.md)** — full architecture, sequence diagrams, RBAC model
- **[`docs/AUDIT_LOG.md`](docs/AUDIT_LOG.md)** — audit ledger schema, webhook fan-out, HMAC verification
- **[`docs/INTEGRATION_TEST_GUIDE.md`](docs/INTEGRATION_TEST_GUIDE.md)** — Testcontainers playbook
- **[`docs/RESTRUCTURE.md`](docs/RESTRUCTURE.md)** — iteration-7 reactor merge log
- **[`docs/api-examples/`](docs/api-examples/)** — `.http` request collections (JetBrains / REST clients)
- **[`LOCAL_SETUP.md`](LOCAL_SETUP.md)** — every feature walked through end-to-end
- **[`WINDOWS_GUIDE.md`](WINDOWS_GUIDE.md)** — Windows / PowerShell variant with troubleshooting matrix
- **[`deploy/README.md`](deploy/README.md)** — Kubernetes / Helm deployment

---

## Appendix — daily-driver command list

```bash
# Build everything, skip tests
mvn -DskipTests clean package

# Build everything + install plugins into ~/.m2
mvn -DskipTests clean install

# Run all unit tests
mvn test

# Run unit + integration tests (Docker required)
mvn verify

# Run a single test class / method
mvn -pl valkeyry-ipaas test -Dtest=PublishControllerTest
mvn -pl valkeyry-ipaas test -Dtest=PublishControllerTest#publishesSuccessfully

# Build & run the iPaaS engine
mvn -pl valkeyry-ipaas -am -DskipTests clean package
ALLOW_ANONYMOUS=true java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar

# Build & run the schema registry
mvn -pl valkeyry-config -am -DskipTests clean package
java -jar valkeyry-config/target/valkeyry-config-1.0.0-SNAPSHOT.jar

# Spring Boot dev mode (no jar)
mvn -pl valkeyry-ipaas  -am spring-boot:run
mvn -pl valkeyry-config -am spring-boot:run

# Gatling
mvn -f valkeyry-ipaas/load-tests/pom.xml gatling:test
mvn -f valkeyry-ipaas/load-tests/pom.xml gatling:test -Dgatling.simulationClass=io.valkeyry.loadtests.PublishSimulation

# Docker images
docker build -f valkeyry-ipaas/Dockerfile  -t valkeyry-ipaas:local  .
docker build -f valkeyry-config/Dockerfile -t valkeyry-config:local .

# React Admin Console
( cd valkeyry-ipaas/frontend && yarn install && yarn start )

# Local infra
( cd valkeyry-ipaas/local-dev && docker compose up -d && ./bootstrap-vault.sh )
```

Happy shipping.  ✦
