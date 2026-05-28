# Valkeyry iPaaS — Local Development Sandbox

End-to-end runnable on a Windows laptop with Docker Desktop / WSL2.

## 1. Boot the stack

```bash
cd local-dev
docker compose up -d         # picks up .env automatically
./bootstrap-vault.sh
```

Services exposed on `localhost`:

| Service       | URL / Port                                                        |
|---------------|-------------------------------------------------------------------|
| Postgres      | `postgresql://localhost:5432/ipaas` (ipaas/ipaas)                 |
| Valkey 8.0    | `redis://localhost:6379`                                          |
| RabbitMQ      | AMQP `localhost:5672`, UI http://localhost:15672 (guest/guest), Prometheus `:15692` |
| Kafka         | `localhost:9092`                                                  |
| ActiveMQ      | `tcp://localhost:61616`, console http://localhost:8161 (admin/admin) |
| MinIO (S3)    | API http://localhost:9000, console http://localhost:9001 (minioadmin/minioadmin) |
| Vault dev     | http://localhost:8200 (token `dev-root-token`)                    |
| **AI-Bridge** | http://localhost:8090 (Spring-AI-style enrichment via Emergent Universal Key) |
| **Prometheus**| http://localhost:9090                                             |
| **Grafana**   | http://localhost:3001 (admin/admin — dashboard auto-provisioned)  |

## 2. Build & run the app

```bash
mvn -DskipTests package
java -jar target/ipaas-platform-1.0.0.jar
```

Enable AI enrichment by setting env vars before launch (or override `application.yml`):

```bash
export AI_ENABLED=true
export AI_MODE=ENRICH                  # ENRICH | DECIDE | OFF
export AI_PROVIDER=openai              # openai | anthropic | gemini
export AI_MODEL=gpt-4.1-mini
export AI_BRIDGE_URL=http://localhost:8090
java -jar target/ipaas-platform-1.0.0.jar
```

Per-message overrides (set as broker headers): `x-ai-provider`, `x-ai-model`,
`x-ai-mode`, `x-ai-options` (comma-separated, DECIDE mode only).

## 3. Smoke-test: ONE_TO_MANY routing

```bash
TENANT="acme-corp"
PROJECT="payments-prod"

# 1) declare the 3 downstream queues (RabbitMQ default)
for q in downstream.analytics downstream.audit downstream.ml-features; do
  curl -s -X POST "http://localhost:8080/api/v1/$TENANT/$PROJECT/queues/declare" \
       -H 'Content-Type: application/json' \
       -d "{\"destinationName\":\"$q\",\"brokerType\":\"RABBITMQ\",\"processingMode\":\"QUEUE\"}"
done

# 2) upload the topology manifest
MANIFEST=$(sed 's/"/\\"/g; s/$/\\n/' sample-integration-manifest.yaml | tr -d '\n')
curl -s -X POST "http://localhost:8080/api/v1/$TENANT/$PROJECT/topologies" \
     -H 'Content-Type: application/json' \
     -d "{\"topologyName\":\"sample-fanout\",\"topologyType\":\"ONE_TO_MANY\",\"manifestYaml\":\"$MANIFEST\"}"

# 3) deploy
curl -s -X POST "http://localhost:8080/api/v1/$TENANT/$PROJECT/topologies/sample-fanout/deploy"

# 4) publish — fans out to all three downstream queues
curl -s -X POST "http://localhost:8080/api/v1/$TENANT/$PROJECT/ingress/ingress.events" \
     -H 'Content-Type: application/json' \
     -d '{"orderId":42,"total":1999}'
```

## 4. Smoke-test: AI enrichment

```bash
# Hit the bridge directly:
curl -s -X POST http://localhost:8090/enrich \
  -H 'Content-Type: application/json' \
  -d '{"payload":{"orderId":42,"total":4500},"provider":"openai","model":"gpt-4.1-mini"}'

# Routing decision:
curl -s -X POST http://localhost:8090/decide \
  -H 'Content-Type: application/json' \
  -d '{"payload":{"customer":"VIP","total":99999},"options":["primary","priority-lane","manual-review"]}'
```

Once the app is started with `AI_ENABLED=true`, every in-flight message in `DynamicConsumerManager`
runs through the interceptor automatically.

## 5. Kafka DLQ peek (new — feature parity with RabbitMQ)

```bash
curl -s "http://localhost:8080/api/v1/$TENANT/$PROJECT/dlq/orders/messages?limit=20"
```

Implemented via Kafka AdminClient + manual `assign()`/`seek()` without committing offsets.

## 6. Grafana dashboards + distributed traces (Tempo)

- **Grafana**: open http://localhost:3001 → **Valkeyry** folder:
  - *Valkeyry iPaaS Overview* — HTTP RPS, p95 latency, JVM heap, Reactor Netty, Resilience4j CB state, retry rate, RabbitMQ queue depth.
  - *Valkeyry iPaaS — Distributed Traces* — recent Java + AI-bridge spans in one pane, plus a service-map node graph (`Java → AI-bridge → upstream provider`).
- **Tempo** (OTLP ingest at `:4318` / search at `:3200`): both the Spring app and the AI-bridge ship spans here via OTLP HTTP. W3C `traceparent` propagation keeps the full trace contiguous across language boundaries.

## 7. Multi-tenant fan-out publish

```bash
curl -s -X POST http://localhost:8080/api/v1/multi-publish \
  -H 'Content-Type: application/json' \
  -d '{
    "targets":[
      {"tenantId":"acme-corp","projectId":"payments-prod","destination":"ingress.events"},
      {"tenantId":"globex-eu","projectId":"billing-dev","destination":"ingress.events"}
    ],
    "payload": "{\"orderId\":42}"
  }'
```
Each target is RBAC-checked independently (`PROJECT_WRITE`). Denied targets are reported
in the response — they do not block the rest of the fan-out.

## 8. Admin Console (React UI)

The repo already ships a React admin console under `/app/frontend`. Point its
`REACT_APP_IPAAS_BASE_URL` env var at the Spring app:

```bash
cd ../frontend
yarn install
echo "REACT_APP_IPAAS_BASE_URL=http://localhost:8080" >> .env
yarn start    # http://localhost:3000
```

Tabs: **Live Metrics** (SSE @1s), **DLQ Inspector** (peek + bulk-retry w/ payload edit),
**Topology Builder** (visual ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY composer with live
YAML preview), **Multi-Tenant Publish** (cross-tenant fan-out form).

For local dev without OIDC set on the Spring app, enable `ipaas.security.allow-anonymous=true`
(or `ALLOW_ANONYMOUS=true` env var) — that skips JWT + RBAC across the board.

### 8a. Enable OIDC sign-in (Keycloak / Auth0 / Okta / Cognito / Google)

When you want a real OIDC flow instead of the paste-the-bearer-token UX, set
three env vars in `frontend/.env` and the **Sign in** button appears in the top bar:

```bash
REACT_APP_OIDC_AUTHORITY=http://localhost:8081/realms/ipaas      # any spec-compliant issuer URI
REACT_APP_OIDC_CLIENT_ID=valkeyry-console
REACT_APP_OIDC_REDIRECT_URI=http://localhost:3000/
REACT_APP_OIDC_SCOPE=openid profile email
```

Flow: PKCE Authorization-Code via `react-oidc-context`. On successful login the
access_token is pushed into `localStorage["valkeyry.bearer-token"]` so every
`fetch()` in `ipaasClient.js` automatically picks it up — including the SSE
metrics stream when the Spring app runs with the JWT resource server enabled.

For a fully self-contained local test of the flow, spin up the bundled Keycloak:

```bash
docker compose --profile auth up -d keycloak
# UI: http://localhost:8081  (admin / admin)
# Realm + client setup steps: see Keycloak admin → Create realm "ipaas",
# create public client "valkeyry-console" with redirect URI http://localhost:3000/.
```

## 9. Run integration tests

```bash
mvn test          # 15 unit tests
mvn verify        # full Testcontainers integration test
```
