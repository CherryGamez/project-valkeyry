# Valkeyry iPaaS — Windows Setup & Manual Test Guide

> A practical end-to-end walkthrough for running Valkeyry on a Windows
> laptop and manually exercising **every feature**, including the new
> Spring AI ChatClient, Operator Copilot, Kafka DLQ AdminClient peek,
> per-project Prometheus tags + Grafana, and Gatling load tests.

If you're on macOS or Linux, see [LOCAL_SETUP.md](LOCAL_SETUP.md) — the
commands are nearly identical, just swap `pwsh` for `bash`.

---

## 0. TL;DR (just run the thing)

```powershell
# From the repo root, in PowerShell, after installing the prereqs (Section 1):
cd valkeyry-ipaas\local-dev
docker compose up -d
cd ..\..
$env:VAULT_TOKEN = "dev-root-token"
.\valkeyry-ipaas\local-dev\bootstrap-vault.ps1      # see appendix A if you don't have it yet
mvn -B -ntp -DskipTests -pl valkeyry-ipaas -am spring-boot:run      # backend on :8080
# In a second terminal:
cd valkeyry-ipaas\frontend ; yarn install ; yarn start    # console on :3000
# Open http://localhost:3000 — log in, click around. Done.
```

> **First boot is slow** because Docker has to pull ~6 GB of images
> *and* Ollama has to download a ~5 GB language model for the Operator
> Copilot. Subsequent boots take <30 seconds.

---

## 1. Prerequisites

| Tool                | Version       | Install (PowerShell, Admin)                                                  |
|---------------------|---------------|-------------------------------------------------------------------------------|
| **Docker Desktop**  | 4.30+         | https://www.docker.com/products/docker-desktop/ — make sure **WSL2** backend is selected |
| **Java JDK**        | **21**        | `winget install EclipseAdoptium.Temurin.21.JDK`                              |
| **Maven**           | 3.9+          | `winget install Apache.Maven`                                                |
| **Node.js**         | 20 LTS+       | `winget install OpenJS.NodeJS.LTS`                                           |
| **Yarn**            | 1.22+         | `npm install -g yarn`                                                        |
| **Git for Windows** | latest        | `winget install Git.Git`                                                     |
| **jq**              | latest        | `winget install jqlang.jq`                                                   |

Verify everything (open a **new** PowerShell so PATH updates pick up):

```powershell
docker --version          # should print >= 27.x
java -version             # should print "openjdk version 21..."
mvn -version              # JDK must be the same 21 you just installed
node -v ; yarn -v
```

> **Important:** open Docker Desktop → Settings → Resources and give it
> at least **8 GB RAM** and **4 CPUs** — Ollama + Kafka + Postgres + ...
> won't fit in the default 4 GB.

---

## 2. First-time setup

### 2.1 Clone & boot the supporting stack

```powershell
git clone https://github.com/your-org/valkeyry.git
cd valkeyry\local-dev
docker compose up -d
docker compose ps           # everything should be "running" or "healthy"
```

Services started (all on `localhost`):

| Service       | URL                                                   | Credentials                  |
|---------------|-------------------------------------------------------|------------------------------|
| Postgres 16   | `postgresql://localhost:5432/ipaas`                   | `ipaas` / `ipaas`            |
| Valkey 8.0    | `redis://localhost:6379`                              | —                            |
| RabbitMQ      | AMQP `:5672` · Mgmt UI http://localhost:15672         | `guest` / `guest`            |
| Kafka         | `localhost:9092`                                      | —                            |
| ActiveMQ      | `tcp://localhost:61616` · UI http://localhost:8161    | `admin` / `admin`            |
| MinIO (S3)    | API `:9000` · Console http://localhost:9001           | `minioadmin` / `minioadmin`  |
| Vault dev     | http://localhost:8200                                 | token `dev-root-token`       |
| **Ollama**    | http://localhost:11434                                | —                            |
| Prometheus    | http://localhost:9090                                 | —                            |
| Grafana       | http://localhost:3001                                 | `admin` / `admin`            |
| Tempo         | http://localhost:3200                                 | —                            |

### 2.2 Inject dev secrets into Vault

PowerShell version of the bootstrap shell script:

```powershell
$env:VAULT_ADDR  = "http://localhost:8200"
$env:VAULT_TOKEN = "dev-root-token"
docker run --rm --network valkeyry_default `
  -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=dev-root-token `
  hashicorp/vault:1.18 vault kv put secret/s3/minio `
  access_key=minioadmin secret_key=minioadmin
```

### 2.3 Pull the Operator Copilot model

The `ollama-bootstrap` container in compose pulls `qwen2.5:7b` on first
launch. If you want a smaller / different model:

```powershell
docker compose exec ollama ollama pull qwen2.5:3b       # 2 GB, faster
docker compose exec ollama ollama list                   # verify
$env:AI_OLLAMA_MODEL = "qwen2.5:3b"
```

> The Spring AI ChatClient needs a model that supports **tool calling**.
> Verified working: `qwen2.5:3b`, `qwen2.5:7b`, `qwen2.5-coder:7b`,
> `llama3.1:8b`. Avoid `mistral` and `gemma:2b` — they don't support tools.

### 2.4 Start the Java backend

```powershell
cd ..
mvn spring-boot:run
```

The backend listens on **http://localhost:8080**. Tail the log until
you see `Started IpaasApplication in X.X seconds`.

### 2.5 Start the React Admin Console

In a second PowerShell:

```powershell
cd valkeyry\frontend
yarn install
yarn start
```

Open **http://localhost:3000**.

---

## 3. Configuration knobs you might want to flip

All flags can be set as environment variables in PowerShell **before**
`mvn spring-boot:run`. The big ones:

| Env var                     | Default                       | What it does                                  |
|-----------------------------|-------------------------------|-----------------------------------------------|
| `ALLOW_ANONYMOUS`           | `true`                        | Disable for OIDC-only mode                    |
| `AI_ENABLED`                | `false`                       | Master switch for the in-pipeline AI interceptor |
| `AI_ENGINE`                 | `SPRING_AI`                   | `SPRING_AI` or `BRIDGE` (legacy Python sidecar) |
| `AI_SPRING_PROVIDER`        | `ollama`                      | `ollama` / `openai` / `anthropic`             |
| `AI_OLLAMA_MODEL`           | `qwen2.5:7b`                  | Any tool-capable Ollama model                 |
| `OPENAI_API_KEY`            | *(blank)*                     | Enables OpenAI ChatModel auto-config          |
| `ANTHROPIC_API_KEY`         | *(blank)*                     | Enables Anthropic ChatModel auto-config       |
| `COPILOT_ENABLED`           | `true`                        | Toggle the Operator Copilot endpoints         |
| `COPILOT_CONFIRM`           | `true`                        | Require `confirm=true` for write tools        |
| `OLLAMA_BASE_URL`           | `http://localhost:11434`      | If you run Ollama elsewhere                   |

PowerShell example:

```powershell
$env:AI_ENABLED = "true"
$env:AI_SPRING_PROVIDER = "ollama"
$env:AI_OLLAMA_MODEL = "qwen2.5:7b"
mvn spring-boot:run
```

### 3.1 Character encoding on Windows (UTF-8 / German umlauts)

Windows is the only platform where the toolchain doesn't default to UTF-8, so a couple of one-time tweaks save a lot of pain when working with German text (`ä ö ü ß ÄÖÜ ẞ`).

**PowerShell terminal** — switch the active code page to UTF-8 (CP 65001) so `curl`, `psql` and log output render umlauts correctly instead of `Ã¤`:

```powershell
chcp 65001                             # one-shot, current session only
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
[Console]::InputEncoding  = [Text.UTF8Encoding]::new()
```

To make it permanent, add the three lines above to your PowerShell profile (`notepad $PROFILE`). Avoid the legacy `cmd.exe` for Unicode work — it defaults to code page 1252 and will mangle non-ASCII characters even when the application emits perfectly valid UTF-8.

**Java runtime** — JDK 18+ already defaults to UTF-8 for `file.encoding` (JEP 400), so no `-Dfile.encoding=UTF-8` flag is needed. Just make sure you're on JDK 21 (`java -version`).

**Postgres on Windows** — the official `postgres:16` Docker image (used by `local-dev\docker-compose.yml`) creates UTF-8 databases automatically. If you installed Postgres natively via the EnterpriseDB installer instead, create the databases as UTF-8 from `psql`:

```sql
CREATE DATABASE ipaas
  WITH ENCODING 'UTF8' TEMPLATE template0
       LC_COLLATE = 'German_Germany.1252'  -- or 'C' if the locale is unavailable
       LC_CTYPE   = 'German_Germany.1252';

CREATE DATABASE valkeyry_config
  WITH ENCODING 'UTF8' TEMPLATE template0
       LC_COLLATE = 'German_Germany.1252'
       LC_CTYPE   = 'German_Germany.1252';
```

> The Windows port of Postgres requires Windows-style locale names (`German_Germany.1252`), but the storage encoding stays UTF-8 — the locale only affects collation/sorting. Use `'C'` if you don't need locale-aware sorting.

Verify:

```powershell
psql -h localhost -U ipaas -d ipaas -c "SHOW server_encoding;"     # → UTF8
psql -h localhost -U ipaas -d ipaas -c "SHOW client_encoding;"     # → UTF8
```

**`curl` from PowerShell** — when sending JSON with umlauts inline, save the body to a UTF-8 file (no BOM) and reference it with `--data-binary "@body.json"`. Inline strings via `-d "..."` can be re-encoded by PowerShell's argument parser into Windows-1252 before they ever leave the shell.

---

## 4. Manual test playbook

> All examples assume a freshly-booted stack with `ALLOW_ANONYMOUS=true`
> (the default). For OIDC see Section 4.10.

### 4.1 Smoke test — backend is alive

```powershell
curl http://localhost:8080/actuator/health | jq
# → {"status":"UP", ...}
```

Expected: `status: UP`. If you see `DOWN`, check Section 5 (Troubleshooting).

---

### 4.2 Catalog-driven lazy provisioning

```powershell
# Declare a queue via the catalog
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/queues `
  -H "Content-Type: application/json" `
  -d '{"destinationName":"orders","brokerType":"RABBITMQ","processingMode":"QUEUE"}' | jq

# Publish (lazy: provisions on the fly if missing)
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/ingress/orders `
  -H "Content-Type: application/json" `
  -d '{"orderId":42,"total":1999}'
```

**Verify** in the RabbitMQ UI → http://localhost:15672 →
`Queues` tab. You should see `acme-corp.payments-prod.orders` with 1 message ready.

---

### 4.3 DLQ inspection & retry

Force a failure to populate the DLQ, then peek:

```powershell
# Send a malformed message that the consumer will reject
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/ingress/orders `
  -H "Content-Type: application/json" `
  -H "x-fail-once: true" `
  -d '{"bad":"payload"}'

# Wait ~5s for the DLQ to be populated, then peek
curl "http://localhost:8080/api/v1/acme-corp/payments-prod/dlq/orders/messages?limit=10" | jq

# NEW: Kafka AdminClient summary (Kafka-only; shows partition-level windows)
curl http://localhost:8080/api/v1/acme-corp/payments-prod/dlq/orders/summary | jq
# → { "brokerType":"KAFKA", "partitionCount":1, "approximateDepth":1, "partitions":[{...}] }

# Bulk retry
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/dlq/orders/retry `
  -H "Content-Type: application/json" `
  -d '[{"messageId":"<paste id from peek>"}]' | jq
```

**Verify in the Admin Console:** `DLQ Inspector` tab → `Refresh` → message should be gone.

---

### 4.4 Topology Builder

```powershell
# Save a 1→N topology
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/topologies `
  -H "Content-Type: application/json" `
  --data-binary "@valkeyry-ipaas/local-dev/sample-integration-manifest.yaml"

# List
curl http://localhost:8080/api/v1/acme-corp/payments-prod/topologies | jq

# Deploy
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/topologies/orders-fanout/deploy
```

**Verify in the Admin Console:** `Topology Builder` tab → topology shows as `deployed: true`.

---

### 4.5 Multi-tenant fan-out publish

```powershell
curl -X POST http://localhost:8080/api/v1/multi-publish `
  -H "Content-Type: application/json" `
  -d @- <<EOF
{
  "targets": [
    {"tenantId":"acme-corp","projectId":"payments-prod","destination":"events.fanout"},
    {"tenantId":"acme-corp","projectId":"reporting-prod","destination":"events.fanout"},
    {"tenantId":"globex-eu","projectId":"billing-dev","destination":"events.fanout"}
  ],
  "payload": "{\"k\":\"v\"}"
}
EOF
```

**Verify in the Admin Console:** `Multi-Tenant Publish` tab. Expected:
3 results, all `accepted=true` (or `denied` if RBAC trips — try with
different slugs).

---

### 4.6 AI Enrich & Decide (Spring AI ChatClient — DEFAULT)

```powershell
# Turn on the interceptor
$env:AI_ENABLED = "true"
$env:AI_MODE    = "ENRICH"
$env:AI_ENGINE  = "SPRING_AI"
# (restart `mvn spring-boot:run` so the env vars take effect)

# Publish a message — the interceptor calls the local Ollama model and the
# enriched payload lands on the broker.
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/ingress/orders `
  -H "Content-Type: application/json" `
  -d '{"orderId":1,"total":4500,"customerTier":"gold"}'
```

Inspect the message in the RabbitMQ UI (`acme-corp.payments-prod.orders`)
— you should see `x-ai-enriched: true` and the body augmented with model-inferred fields.

#### Decide mode (routing header)

```powershell
$env:AI_MODE = "DECIDE"
# restart backend, then:
curl -X POST http://localhost:8080/api/v1/acme-corp/payments-prod/ingress/orders `
  -H "Content-Type: application/json" `
  -H "x-ai-options: primary,secondary,dlq" `
  -d '{"orderId":99,"riskScore":0.9}'
# The published message will carry x-ai-decision header.
```

#### Switch to the legacy Python ai-bridge

```powershell
$env:AI_ENGINE = "BRIDGE"
$env:AI_BRIDGE_URL = "http://localhost:8090"
# Make sure `valkeyry-ipaas/local-dev/ai-bridge` is running (uvicorn on :8090).
```

#### Per-message overrides

| Header           | Effect                                              |
|------------------|-----------------------------------------------------|
| `x-ai-engine`    | `SPRING_AI` or `BRIDGE`                             |
| `x-ai-provider`  | `ollama` / `openai` / `anthropic` (Spring AI only)  |
| `x-ai-model`     | Override model name                                 |
| `x-ai-mode`      | `ENRICH` / `DECIDE` / `OFF`                         |
| `x-ai-options`   | Comma-separated routing options for `DECIDE` mode   |

---

### 4.7 **Operator Copilot** (the headline feature)

Open **http://localhost:3000** → log in → click the **Operator Copilot**
tab. The UI auto-detects which LLM providers are wired.

Try these prompts (the LLM will call backend tools and return real data):

* `List queues for acme-corp/payments-prod.`
* `What's the current depth of orders in acme-corp/payments-prod?`
* `Peek the first 5 DLQ messages of orders.`
* `Which LLM providers are currently wired?`
* `Retry every DLQ message on orders.` → Copilot will ask for confirmation
  before executing (because `COPILOT_CONFIRM=true`).

Or hit the API directly:

```powershell
# Streaming chat
curl -N -X POST http://localhost:8080/api/v1/copilot/stream `
  -H "Content-Type: application/json" `
  -d '{"sessionId":"my-session","message":"snapshot acme-corp/payments-prod"}'

# Single-shot
curl -X POST http://localhost:8080/api/v1/copilot/chat `
  -H "Content-Type: application/json" `
  -d '{"sessionId":"my-session","message":"which providers are wired?"}' | jq

# Tool catalog
curl http://localhost:8080/api/v1/copilot/tools | jq

# Reset memory
curl -X POST http://localhost:8080/api/v1/copilot/session/my-session/reset
```

**Switching providers without restart:**

* In the UI: `Provider` dropdown above the chat thread.
* Per request: add `"provider":"anthropic"` to the JSON body (requires
  `ANTHROPIC_API_KEY` env var to be set at boot).

---

### 4.8 Live SSE metrics

In the Admin Console open the `Live Metrics` tab — sparklines update in
real time via Server-Sent Events. Or hit the stream directly:

```powershell
curl -N http://localhost:8080/api/v1/acme-corp/payments-prod/metrics/stream
```

---

### 4.9 Grafana dashboards (incl. per-tenant)

1. http://localhost:3001 → log in `admin` / `admin`.
2. Sidebar → **Dashboards** → you'll see two:
   * **Valkeyry iPaaS — Overview** (existing platform-wide view)
   * **Valkeyry iPaaS — Per Tenant / Project** *(new in this iteration)*
3. Open the per-tenant dashboard. Use the `Tenant` / `Project` template
   variables at the top to slice on:
   * `acme-corp / payments-prod`
   * `acme-corp / reporting-prod`
   * `globex-eu / billing-dev`
4. Generate some traffic (Section 4.2 or run Gatling — Section 4.11) and
   watch p95 latency, RPS, 5xx rate, 429s, and queue depth update per tenant.

> **What changed under the hood:** Spring's WebFlux `http.server.requests`
> metric is now tagged with `tenant_id` + `project_id` (extracted from the
> request path). Custom counters declared by the broker / consumer layers
> use the same tags via `MetricsTagConfig.workspaceTags(tenant, project)`.
> A cardinality guard caps tag values at 200 tenants × 500 projects.

---

### 4.10 OIDC mode (optional)

```powershell
docker compose --profile auth up -d keycloak
$env:ALLOW_ANONYMOUS = "false"
$env:OIDC_ISSUER_URI = "http://localhost:8081/realms/valkeyry"
mvn spring-boot:run
```

The Admin Console will redirect to Keycloak. Create a user with the
`workspace:acme-corp:payments-prod:publisher` claim to publish.

---

### 4.11 Load testing with **Gatling**

```powershell
# 1. Make sure Valkeyry is running on :8080.
# 2. From repo root:
mvn -f load-tests/pom.xml clean test                                  # compile only
mvn -f load-tests/pom.xml gatling:test `
    -Dgatling.simulationClass=io.valkeyry.loadtests.PublishSimulation  # one
mvn -f load-tests/pom.xml gatling:test                                 # all four
```

| Simulation                    | Goal                          | Pass/fail SLOs                    |
|-------------------------------|-------------------------------|-----------------------------------|
| `PublishSimulation`           | 10→500 RPS, single tenant     | p(95) < 200 ms, err < 1%          |
| `MultiTenantPublishSimulation`| 5→150 RPS, 3-target fan-out   | p(95) < 400 ms, err < 2%          |
| `FileStreamingSimulation`     | 1 MiB body uploads            | p(95) < 1500 ms, err < 2%         |
| `CopilotChatSimulation`       | Chat workload through Ollama  | p(95) < 10 s, err < 5%            |

HTML reports land in `load-tests/target/gatling/<sim>-<timestamp>/index.html`.
Open one in your browser — the **Assertions** section gives a green/red
verdict, and the **Stats** section breaks down p95/p99 per request type.

While the test is running, leave Grafana **Per-Tenant** dashboard open
on the second screen — you'll see RPS / latency / 429s react live.

---

### 4.12 Tempo distributed traces

1. Hit any endpoint a few times (Section 4.2).
2. Grafana → **Explore** → datasource **Tempo** → query `{service.name="valkeyry-ipaas"}`.
3. Click any trace → you'll see the full span tree: HTTP → interceptor chain →
   broker publish → (Copilot) tool call → ChatClient.

---

## 5. Troubleshooting

| Symptom                                                          | Fix                                                                                          |
|------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `mvn` uses Java 17 instead of 21                                 | `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21..."` then restart the shell        |
| `docker compose up -d` fails with `bind: address already in use` | Another local service is on the same port. Stop it or edit `docker-compose.yaml` port mapping |
| Backend log shows `Connection refused: localhost:11434`          | Ollama isn't running. `docker compose start ollama` and re-pull the model                    |
| Copilot replies say *"Operator Copilot is disabled"*             | `$env:COPILOT_ENABLED = "true"` and restart backend                                          |
| Copilot replies say *"No ChatModel available"*                   | Ollama not reachable AND no OPENAI/ANTHROPIC key set. Start Ollama or set a key              |
| `qwen2.5` model says it *can't use tools*                        | Wrong model. Try `qwen2.5:7b` (NOT `qwen2.5:7b-instruct-q4_0`) or `llama3.1:8b`              |
| Gatling test fails with `connection refused`                     | Backend not running, or `-Dvalkeyry.base-url` points to the wrong host                       |
| Grafana panels are empty                                         | Scrape lag (~30 s). Also confirm Prometheus targets are UP: http://localhost:9090/targets    |
| Front-end shows `Network error` for every call                   | `REACT_APP_BACKEND_URL` mismatch — for local dev keep `frontend/.env.local` at `http://localhost:8080` |

Backend logs: `target/spring-boot.log` (created by `mvn spring-boot:run`).

To increase Ollama logs verbosity:

```powershell
docker compose logs -f ollama
```

---

## 6. What's where (cheat sheet)

| Layer              | Path                                                                |
|--------------------|---------------------------------------------------------------------|
| Java backend       | `src/main/java/io/valkeyry/ipaas/**`                                |
| Spring AI config   | `src/main/java/io/valkeyry/ipaas/config/SpringAiConfig.java`        |
| AI interceptor     | `valkeyry-ipaas/src/main/java/io/valkeyry/ipaas/interceptor/AiEnrichmentInterceptor.java` |
| Copilot            | `valkeyry-ipaas/src/main/java/io/valkeyry/ipaas/copilot/**`                        |
| Kafka AdminClient  | `valkeyry-ipaas/src/main/java/io/valkeyry/ipaas/broker/KafkaBrokerClient.java`     |
| Metrics tags       | `valkeyry-ipaas/src/main/java/io/valkeyry/ipaas/metrics/MetricsTagConfig.java`     |
| Grafana dashboards | `valkeyry-ipaas/local-dev/observability/grafana/provisioning/dashboards/`          |
| Gatling tests      | `valkeyry-ipaas/load-tests/`                                                       |
| React console      | `valkeyry-ipaas/frontend/src/`                                                     |
| Copilot UI         | `valkeyry-ipaas/frontend/src/components/admin/CopilotPanel.jsx`                    |
| Docker compose     | `valkeyry-ipaas/local-dev/docker-compose.yaml`                                     |
| App config         | `valkeyry-ipaas/src/main/resources/application.yml`                                |

---

## Appendix A — `bootstrap-vault.ps1`

If you don't have it, save the following as `valkeyry-ipaas/local-dev/bootstrap-vault.ps1`:

```powershell
# Idempotent: seeds dev secrets so the platform boots without external creds.
$env:VAULT_ADDR  = "http://localhost:8200"
$env:VAULT_TOKEN = "dev-root-token"
docker run --rm --network valkeyry_default `
  -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=dev-root-token `
  hashicorp/vault:1.18 vault kv put secret/s3/minio `
  access_key=minioadmin secret_key=minioadmin
docker run --rm --network valkeyry_default `
  -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=dev-root-token `
  hashicorp/vault:1.18 vault kv put secret/webhook/default hmac_key=dev-hmac-key
Write-Host "Vault seeded."
```

Run once after compose-up:

```powershell
.\local-dev\bootstrap-vault.ps1
```

---

Happy shipping. ⚡
