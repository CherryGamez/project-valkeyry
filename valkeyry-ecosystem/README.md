# Valkeyry Ecosystem

A production-ready, multi-tenant, **reactive** ecosystem split across three independently
deployable Java 21 + Spring Boot 3 modules.

```
valkeyry-ecosystem/                        ← Maven reactor parent
├── valkeyry-ipaas             ← Reactive Messaging & Streaming engine   (independently deployable)
├── valkeyry-config            ← Headless Schema Registry (Postgres JSONB) (independently deployable)
└── valkeyry-config-plugin     ← Maven & Gradle automation plugin         (independently publishable)
```

> Each runtime module owns its own `pom.xml`, `application.yml`, port, Dockerfile, K8s
> manifest, and Helm chart. They share dependency-version management through the parent POM
> but have **zero runtime cross-dependencies** — you can build, version, and deploy them on
> entirely separate schedules.

## What's in each module?

### `valkeyry-ipaas` — Messaging & Streaming Engine
- Reactive WebFlux publish API
- Pluggable **`BrokerAdapter`** with three implementations:
  - **Kafka** (streaming, idempotent producer)
  - **RabbitMQ** (queueing, Reactor-RabbitMQ)
  - **ActiveMQ** (queueing, JMS bridge)
- **Claim-Check** externalisation to S3/MinIO above a configurable byte threshold
- **Valkey/Redis** token-bucket rate limiter (Lua-atomic) implemented as a WebFilter
- Dual-track security (OIDC Bearer for tenants, HTTP Basic over LDAP for headless)
- **Vault** integration for secret resolution (optional, fails gracefully)

### `valkeyry-config` — Headless Schema Registry
- Stores **virtual tables** in a single hybrid EAV-JSONB layout — no runtime DDL
- JSON-Schema (Draft 2020-12) validation on every write
- Deterministic SHA-256 fingerprint → **Idempotency Guard** (skip duplicate payloads)
- Full version trail per logical record (`is_latest` flag flipped on every write)
- Three authentication tracks: OIDC, LDAP-Basic, `X-API-Key`
- Postgres JSONB containment search (`@>`) + GIN index

### `valkeyry-config-plugin` — Build-tool Automation
- `plugin-core`: zero-Spring shared engine (manifest parsing, hashing, JDK HttpClient)
- `valkeyry-config-maven-plugin`: Maven Mojo bound to `deploy` phase (`mvn valkeyry-config:push`)
- `valkeyry-config-gradle-plugin`: Gradle Task `valkeyryConfigPush`

## Build (your machine)
This codebase ships *compile-ready Maven sources only* — the platform pod here does **not** run
the build. On your dev machine:

```bash
# Prereqs: JDK 21+, Maven 3.9+, Docker (for Testcontainers)
cd valkeyry-ecosystem

# Build the whole reactor
mvn clean verify

# Or build / test / package each module on its own (each is a self-contained Spring Boot app)
mvn -pl valkeyry-ipaas   -am package           # builds plugin-core too if needed; no, ipaas has no plugin dep
mvn -pl valkeyry-config  -am package           # pulls in plugin-core as a test-scope dep

# Run one module locally (others can be off)
mvn -pl valkeyry-ipaas   spring-boot:run
mvn -pl valkeyry-config  spring-boot:run
```

## Independent deployment

| Module             | Default port | Image                                            | Helm chart                 |
|--------------------|--------------|--------------------------------------------------|----------------------------|
| `valkeyry-ipaas`   | 8082         | `ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT` | `deploy/helm/valkeyry-ipaas`  |
| `valkeyry-config`  | 8081         | `ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT`| `deploy/helm/valkeyry-config` |

Build + push the images independently:

```bash
docker build -f valkeyry-ipaas/Dockerfile  -t ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT  .
docker build -f valkeyry-config/Dockerfile -t ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT .
docker push ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT
docker push ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT
```

Deploy whichever subset you need:

```bash
helm install vk-ipaas  deploy/helm/valkeyry-ipaas  --namespace valkeyry --create-namespace
helm install vk-config deploy/helm/valkeyry-config --namespace valkeyry
```

You can release one module without redeploying the other — their lifecycles are decoupled.

## Documentation
| File | Purpose |
|------|---------|
| [`docs/INTEGRATION_TEST_GUIDE.md`](docs/INTEGRATION_TEST_GUIDE.md) | Step-by-step beginner guide for running the full ecosystem test |
| [`docs/architecture.md`](docs/architecture.md) | Module boundaries, data-flow, and security model |
| [`docs/AUDIT_LOG.md`](docs/AUDIT_LOG.md) | Audit ledger schema + REST + tamper-resistance guidance |
| [`valkeyry-config/README.md`](valkeyry-config/README.md) | Schema-registry deep-dive |
| [`valkeyry-config/src/main/resources/static/audit/README.md`](valkeyry-config/src/main/resources/static/audit/README.md) | Audit Console (web UI) — `http://<host>:8081/audit/` |
| [`valkeyry-ipaas/README.md`](valkeyry-ipaas/README.md) | Messaging engine deep-dive |
| [`valkeyry-config-plugin/README.md`](valkeyry-config-plugin/README.md) | Plugin authoring guide |
| [`deploy/README.md`](deploy/README.md) | Docker + K8s + Helm deployment |

## License
Apache License 2.0
