# Valkeyry iPaaS — Local Setup Guide

Step-by-step walkthrough to run the full stack on a Windows / macOS / Linux
laptop and exercise every feature end-to-end.

> **Running on Windows?** Use the dedicated [**WINDOWS_GUIDE.md**](WINDOWS_GUIDE.md) —
> it has PowerShell snippets, troubleshooting matrix, and per-feature manual test
> checklists including the new Operator Copilot, Spring AI ChatClient, Kafka
> AdminClient DLQ peek, per-tenant Grafana, and Gatling load tests.

---

## 1. Prerequisites

| Tool                  | Version       | Install hint                                                |
|-----------------------|---------------|-------------------------------------------------------------|
| **Docker Desktop**    | 4.x +         | https://www.docker.com/products/docker-desktop/             |
| **Java JDK**          | **21**        | `winget install EclipseAdoptium.Temurin.21.JDK` (Windows) · `brew install --cask temurin@21` (mac) · `apt install openjdk-21-jdk` (Linux) |
| **Maven**             | 3.8 +         | `winget install Apache.Maven` · `brew install maven` · `apt install maven` |
| **Node.js**           | 20 +          | https://nodejs.org/                                          |
| **Yarn**              | 1.22 +        | `npm install -g yarn`                                        |
| **curl** / **jq**     | any           | usually pre-installed; `winget install jqlang.jq` if needed  |

> **Windows users:** Use **WSL2** or PowerShell. The compose stack works on either; the shell snippets below are bash, easily adapted to PowerShell.

Verify:

```bash
docker --version
java -version           # must print 21.x
mvn -version            # must use the JDK 21 you installed
node -v && yarn -v
```

---

## 2. Boot the infrastructure stack

```bash
cd /path/to/valkeyry/valkeyry-ipaas/local-dev
docker compose up -d
./bootstrap-vault.sh         # injects dummy S3 creds into Vault dev
```

What's running now (all on `localhost`):

| Service       | URL                                               | Credentials                  |
|---------------|---------------------------------------------------|------------------------------|
| Postgres 16   | `postgresql://localhost:5432/ipaas`               | `ipaas` / `ipaas`            |
| Valkey 8.0    | `redis://localhost:6379`                          | —                            |
| RabbitMQ      | AMQP `:5672` · UI http://localhost:15672          | `guest` / `guest`            |
| Kafka         | `localhost:9092`                                  | —                            |
| ActiveMQ      | `tcp://localhost:61616` · UI http://localhost:8161 | `admin` / `admin`           |
| MinIO (S3)    | API `:9000` · console http://localhost:9001       | `minioadmin` / `minioadmin`  |
| Vault dev     | http://localhost:8200                             | token `dev-root-token`       |
| AI-Bridge     | http://localhost:8090                             | Emergent Universal Key (env) |
| Prometheus    | http://localhost:9090                             | —                            |
| Grafana       | http://localhost:3001                             | `admin` / `admin`            |
| Tempo         | OTLP `:4318` · http://localhost:3200              | —                            |

Optional (only when validating OIDC login):

```bash
docker compose --profile auth up -d keycloak
# http://localhost:8081  admin / admin
```

---

## 3. Build and run the Spring backend

```bash
cd ..                       # back to the repo root (where the reactor pom.xml lives)
mvn -B -ntp -DskipTests -pl valkeyry-ipaas -am clean package
ALLOW_ANONYMOUS=true java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar
```

The `ALLOW_ANONYMOUS=true` env var disables OIDC + RBAC for local testing.
Backend is now listening on **http://localhost:8080**.

Smoke-test:

```bash
curl -s http://localhost:8080/actuator/health | jq .
# {"status":"UP"}
curl -s http://localhost:8080/v3/api-docs | jq '.info'
```

To enable AI enrichment in the consumer pipeline:

```bash
AI_ENABLED=true AI_MODE=ENRICH AI_PROVIDER=openai AI_MODEL=gpt-4.1-mini \
ALLOW_ANONYMOUS=true java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar
```

---

## 4. Run the React Admin Console

```bash
cd frontend
yarn install
yarn start             # http://localhost:3000
```

> The frontend lives at `valkeyry-ipaas/frontend/`. If you came from the repo
> root, use `cd valkeyry-ipaas/frontend` instead.

On first visit you land on the **Sign in** page. Use:

| Username   | Password   | Role     |
|------------|------------|----------|
| `admin`    | `admin`    | ADMIN    |
| `operator` | `operator` | OPERATOR |

For OIDC instead, uncomment these in `frontend/.env`:

```bash
REACT_APP_OIDC_AUTHORITY=http://localhost:8081/realms/ipaas
REACT_APP_OIDC_CLIENT_ID=valkeyry-console
REACT_APP_OIDC_REDIRECT_URI=http://localhost:3000/
```

Then a "**Continue with SSO**" button appears on the login page.

---

## 5. Walk through every feature

### 5.1 — Service-catalog declare a queue (RabbitMQ)

UI: load the sample tenant/project from the top bar, then declare via API:

```bash
TENANT=acme-corp
PROJECT=payments-prod

curl -s -X POST http://localhost:8080/api/v1/$TENANT/$PROJECT/queues/declare \
  -H 'Content-Type: application/json' \
  -d '{"destinationName":"orders","brokerType":"RABBITMQ","processingMode":"QUEUE"}' | jq .
```

In RabbitMQ UI (http://localhost:15672) you will see queues
`<tenant>.<project>.orders` and `<tenant>.<project>.orders.dlq`.

### 5.2 — Lazy self-healing ingress publish

```bash
curl -s -X POST http://localhost:8080/api/v1/$TENANT/$PROJECT/ingress/brand-new-queue \
  -H 'Content-Type: application/json' \
  -d '{"hello":"world"}' | jq .
# {"destination":"brand-new-queue","brokerType":"RABBITMQ","provisioningMode":"LAZY_PROVISIONED","status":"ACCEPTED"}
```

### 5.3 — Topology builder (UI)

1. Click **Topology Builder** tab.
2. Leave the defaults (`sample-fanout`, `ONE_TO_MANY`, RabbitMQ).
3. Notice the YAML preview updates live as you edit.
4. Click **Save & Deploy** — the topology is registered + wired up in-memory.
5. Publish to `ingress.events` (5.2 pattern) and watch the three downstream queues fill in the RabbitMQ UI.

### 5.4 — Live SSE metrics

1. **Live Metrics** tab → **Start stream**.
2. Per-queue cards appear at 1-second tick (message count, broker type).

### 5.5 — DLQ inspector

1. Force a failing dispatch (set the consumer's target webhook to `http://localhost:9` so it 5xx's).
2. **DLQ Inspector** tab → enter `orders` → **Peek DLQ**.
3. Select messages, optionally edit payload, **Bulk retry**.

### 5.6 — Multi-tenant publish

1. **Multi-Tenant Publish** tab → fill row 0 with the sample slugs.
2. **+ Add target** for a second `(tenantId, projectId, destination)`.
3. **Fan-out publish** — per-target outcomes are colour-coded
   (`ACCEPTED` = green, `DENIED` = amber, `ERROR` = rose).

Or via curl:

```bash
curl -s -X POST http://localhost:8080/api/v1/multi-publish \
  -H 'Content-Type: application/json' \
  -d '{
    "targets":[
      {"tenantId":"'$TENANT'","projectId":"'$PROJECT'","destination":"ingress.events"},
      {"tenantId":"globex-eu","projectId":"billing-dev","destination":"ingress.events"}
    ],
    "payload":"{\"orderId\":42}"
  }' | jq .
```

### 5.7 — AI enrichment / decide

Direct call to the sidecar:

```bash
curl -s -X POST http://localhost:8090/enrich \
  -H 'Content-Type: application/json' \
  -d '{"payload":{"orderId":42,"total":4500},"provider":"openai","model":"gpt-4.1-mini"}' | jq .

curl -s -X POST http://localhost:8090/decide \
  -H 'Content-Type: application/json' \
  -d '{"payload":{"customer":"VIP","total":99999},"options":["primary","priority-lane","manual-review"]}' | jq .
```

When `AI_ENABLED=true` in the Spring app, every in-flight consumer message is enriched / decided automatically.

### 5.8 — File streaming (Claim Check Pattern)

```bash
# Register storage config (Vault path resolves dummy MinIO creds we injected in step 2)
curl -s -X POST http://localhost:8080/api/v1/$TENANT/$PROJECT/queues/declare \
  -H 'Content-Type: application/json' \
  -d '{"destinationName":"file-tickets","brokerType":"RABBITMQ"}'

# (storage configuration upsert endpoint would go here — see DynamicStorageFactory)

# Stream-upload a file -> ClaimTicket -> queue
curl -s -X POST http://localhost:8080/api/v1/$TENANT/$PROJECT/files/upload \
  -F storageName=local-minio \
  -F targetDestination=file-tickets \
  -F objectKey=report.csv \
  -F 'file=@/path/to/report.csv;type=text/csv' | jq .
```

### 5.9 — Grafana dashboards + Tempo traces

1. Open **http://localhost:3001** (admin/admin).
2. **Valkeyry → Valkeyry iPaaS Overview** — HTTP RPS, p95, JVM, Resilience4j CB state, retry rate, queue depth.
3. **Valkeyry → Distributed Traces** — search `{service.name=~"valkeyry-.*"}` to see Java + AI-bridge spans stitched via W3C `traceparent`.
4. *Explore* → **Tempo** → search by service for raw traces.

### 5.10 — Vault auto-renewal

Spring app logs `Vault token renewed; lease_duration=...` every 30 min when running against a renewable token. Dev root tokens print a `debug` line (non-renewable by design).

---

## 6. Run the test suite

```bash
mvn test                # 21 unit tests, no Docker needed
mvn verify              # full Testcontainers integration test (needs Docker)
```

---

## 7. Tear down

```bash
cd valkeyry-ipaas/local-dev && docker compose down -v
# kill the Spring jar (Ctrl-C in its terminal)
# kill the React dev server (Ctrl-C in its terminal)
```

---

## 8. Troubleshooting

- **`Connection refused` on port 5432/6379/5672** — wait ~10 s after `docker compose up -d`, the containers boot in parallel.
- **`401 Unauthorized` from Spring** — you forgot `ALLOW_ANONYMOUS=true`, or you're using a real OIDC token whose `sub` has no row in `user_access_policies`. Insert a row, or restart with the env var.
- **AI-Bridge `502 LLM enrich error`** — `EMERGENT_LLM_KEY` is missing in `local-dev/.env`, or the network is firewalled.
- **Grafana dashboards empty** — the Spring app emits Prometheus metrics on `/actuator/prometheus`. Make sure Prometheus container can reach `host.docker.internal:8080` (configured by default; on Linux this needs `--add-host=host.docker.internal:host-gateway`, already wired in compose).
- **Tempo "no traces found"** — Spring app needs `OTLP_ENDPOINT=http://localhost:4318/v1/traces` (default in `application.yml`); restart it after starting Tempo.
- **OIDC redirect loop** — the `redirect_uri` you registered with your IdP must match `REACT_APP_OIDC_REDIRECT_URI` *exactly*, trailing slash included.

---

Happy shipping. ✦
