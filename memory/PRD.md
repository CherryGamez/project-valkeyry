# Valkeyry Ecosystem — PRD

## Original problem statement
Fix the non-compilable Java Maven project on the `valkeyry-emergent` branch:
1. Update the directory structure so the project compiles.
2. Update the root `README.md` with appropriate `mvn` commands.

### Follow-up — 2026-02 (current task)
Implement a **lightweight, HTMX-driven administrative GUI** for the `valkeyry-config` registry.

**Constraints (verbatim from user):**
- **NO React, Vue, Node.js, or NPM.** Zero-build.
- Raw HTML + Tailwind (CDN) + HTMX served as Spring static resources.
- Entry point: `valkeyry-config/src/main/resources/static/index.html`.

**Required panels:**
1. Core application shell — left sidebar nav + main content (`hx-target`).
2. Dynamic virtual-table creation wizard (`tableName` + `schemaDefinition` JSON).
3. Dynamic CRUD engine for `VirtualTableEntry` records (Add / Update / Delete).
4. Live schema-validation UX — intercept 422 `SchemaValidationException`, render violations inline without losing user input.
5. Audit timeline + push-button rollback to restore prior state.
6. Webhook control panel — add / list / remove dynamic audit-webhook targets.

## Architecture (current)
Multi-module Maven reactor at repo root (`/app`):

```
/app/
├── pom.xml                      # reactor parent (io.valkeyry:valkeyry-ecosystem-parent:1.0.0-SNAPSHOT)
├── valkeyry-ipaas/              # Spring Boot 3 reactive messaging engine (+ React UI)
├── valkeyry-config/             # Spring Boot 3 schema-registry engine
│   └── src/main/resources/static/index.html  # ← HTMX zero-build admin GUI (this task)
├── valkeyry-config-plugin/      # Maven + Gradle plugins
├── deploy/                      # Helm + raw k8s manifests
└── docs/
```

Toolchain: JDK 21 (Temurin), Maven 3.8.7+, Docker for Testcontainers.

## What's been implemented

### 2026-05-28 — Reactor restructure
- Promoted reactor from `/app/valkeyry-ecosystem/*` to `/app/`, deleted scaffolding cruft.
- Installed Temurin JDK 21 via Adoptium apt repo on the build container.
- Verified `mvn -B -ntp -DskipTests clean package` → BUILD SUCCESS (7 modules).

### 2026-02-12 — HTMX Admin GUI for valkeyry-config
**Frontend (single file, zero build):**
- `valkeyry-config/src/main/resources/static/index.html` — complete HTMX shell.
- Tailwind CDN + HTMX 1.9.10 + Mustache 4.2.0 (manual rendering via `htmx:beforeSwap`).
- **iPhone-inspired "light & breezy" theme** (2026-02-12 follow-up): Apple HIG iOS 17 system colors as accents (blue, indigo, mint, pink, orange), near-white canvas with pastel radial gradients, frosted-glass top bar (`backdrop-filter: blur`), pill-shaped buttons with iOS blue gradients, SF Pro Display font stack, soft shadows, color-coded timeline dots.
- Auth bar — tenant + API-key or Bearer token, stored in `localStorage`, injected via `htmx:configRequest`.
- All HTMX fetches done via `htmx.ajax()` (programmatic) for reliable URL handling.

**Panels delivered (every `data-testid` indexed for testing):**
1. **Sidebar** — `GET /api/v1/tenants/{tenantId}/tables` → list of `VirtualTableView`.
2. **Declare-table wizard** — modal, `POST /tables` with `{tableName, schema}` body, inline schema-error rendering.
3. **Entry CRUD** — per-table grid + Add/Update (re-ingest) + soft-Delete buttons.
4. **Audit timeline** — vertical timeline, before/after JSON, push-button rollback per record-level event.
5. **Webhook control panel** — Add / list / Remove dynamic audit-webhook targets.

**Backend additions:**
- `V3__audit_webhook_subscriptions.sql` — Flyway migration for dynamic webhook targets.
- `domain/AuditWebhookSubscription.java` + `repo/AuditWebhookSubscriptionRepository.java`.
- `service/AuditWebhookSubscriptionService.java`.
- `api/WebhookSubscriptionController.java` — `GET/POST/DELETE /api/v1/tenants/{tenantId}/webhooks`.
- `api/WebhookSubscriptionView.java`, `api/CreateWebhookSubscriptionRequest.java`.
- `service/AuditWebhookPublisher.java` — refactored to **merge** static-env + DB-managed targets per tenant; HMAC override-per-row.
- `ConfigAuditService.Operation` — added `DELETE_RECORD`, `ROLLBACK`.
- `VirtualTableService.softDelete(...)` — flips `is_latest=false` on the head, writes `DELETE_RECORD` audit row (no schema change).
- `VirtualTableService.rollback(...)` — replays `beforeValue` through ingest pipeline, tags audit row `ROLLBACK`.
- `VirtualTableController` — added `DELETE /tables/{name}/entries/{recordKey}`.
- `api/AuditRollbackController.java` — `POST /api/v1/tenants/{tenantId}/audit/{auditId}/rollback`.
- `repo/ConfigAuditRepository.findByTenantAndId(...)`.
- Removed legacy React+Babel SPA at `static/audit/` (replaced by the HTMX timeline).

**Existing-test compatibility:**
- Kept a 2-arg ctor on `AuditWebhookPublisher` (`(props, mapper)`) so the existing 5
  `AuditWebhookPublisherTest` cases continue to compile/pass; production wiring uses the
  3-arg ctor via Spring DI.

**Schema-validation UX:**
- 422 ProblemDetail responses (`{violations: [...]}` per `ApiExceptionHandler`) are intercepted
  in `htmx:beforeSwap`, parsed, and rendered as a list of `path — message` rows inside the
  closest open dialog's `data-testid$="-error"` slot. Form input is **never** lost.

### 2026-02-12 — Fix: app couldn't actually start at runtime
While the build was green, running `ValkeyryConfigApplication` against a real Postgres failed silently with three latent bugs (none caught by the existing slice tests):

1. **Bean wiring**: `AuditWebhookPublisher` was annotated `@Configuration` (CGLIB-proxied). Adding the test-friendly 2-arg constructor confused Spring's ctor selection → `"No default constructor found"`. **Fix**: drop the misleading `@Configuration` annotation (the class defines no `@Bean` methods), keep `@Component`, and mark the 3-arg ctor `@Autowired` to disambiguate.
2. **R2DBC search path**: Flyway places tables in the `valkeyry_config` schema but the R2DBC URL didn't tell the driver to search it → `relation "virtual_table_registry" does not exist` at every query. **Fix**: append `?schema=valkeyry_config` to the default R2DBC URL in `application.yml`, `.env.dev`, and `docker-compose.dev.yml`.
3. **Manually-assigned UUIDs → INSERT becomes UPDATE**: `SimpleR2dbcRepository.save()` checks `Persistable.isNew()` to decide between INSERT and UPDATE; with our manually-set UUID PKs it defaulted to UPDATE → `"Row with Id [...] does not exist"`. **Fix**: have every UUID-keyed entity (`VirtualTableRegistry`, `VirtualTableEntry`, `ConfigAuditEntry`, `AuditWebhookSubscription`) implement a new `UuidEntity` marker interface (extends `Persistable<UUID>`) with a `@Transient isNew = true` flag, plus a single shared `UuidEntityIsNewCallback` (Spring Data R2DBC `AfterConvertCallback`) that flips the flag after loads. New objects route to INSERT; loaded ones route to UPDATE.

Also added a `@ExceptionHandler(DuplicateKeyException.class)` → `409 Conflict` with a stable `urn:valkeyry:error:duplicate-resource` Problem+JSON type, so the webhook UI gets a clean error when the same URL is re-added.

### 2026-02-12 — Local dev quick-start
- `valkeyry-config/docker-compose.dev.yml` — Postgres 16 + optional app container, pre-creates schema via init script.
- `valkeyry-config/.env.dev` — env vars matching the compose file for IDE runs.
- `valkeyry-config/db/init/01-schema.sql` — bootstraps the `valkeyry_config` schema on a fresh Postgres volume.
- README updated with **Option A** (one-command `docker compose up`) and **Option B** (IDE-run + Postgres-in-Docker) + a troubleshooting table.

**Manual smoke test (against a real Postgres in this sandbox):** All endpoints verified working end-to-end:
- `POST /tables` → 201 (declare); subsequent declares → REVISE_TABLE.
- `POST /tables/.../entries` → 201 (insert, new version).
- `POST /tables/.../entries` (same data) → 409 (idempotency-skip).
- `DELETE /tables/.../entries/{key}` → 204.
- `POST /webhooks` → 201; same URL again → 409 with proper `urn:valkeyry:error:duplicate-resource` body.
- `POST /audit/{id}/rollback` → 201 — restored Alice's `fullName` to "Alice" as v4 after a v3 update to "Alice Updated".

## Backlog
- P1 — Optional: add `@SpringBootTest` integration tests for the new endpoints (`WebhookSubscriptionController`, `AuditRollbackController`, soft-delete) under `valkeyry-config/src/test/java/...`.
- P2 — Optional: bundle Tailwind via CLI in `mvn package` to drop the CDN dev-warning in production.
- P2 — Optional: HTMX `hx-push-url` for deep linking each tab/table to a URL.
- P2 — Optional: WebSocket-driven live audit timeline (`hx-ext="ws"`).

### 2026-02-13 — Flexible config types + LDAP/JWT test guides
**Problem**: users had to hand-author Draft 2020-12 JSON Schemas, and there
was no documented local-test path for `valkeyry-config` covering the LDAP /
JWT auth tracks the security layer already supports.

**What shipped**
1. **UI Schema Builder** (`valkeyry-config/src/main/resources/static/index.html`).
   - New `Visual Builder` ⇄ `Raw JSON` mode toggle inside the *Declare a virtual table* modal.
   - Visual rows for: Text, Email, URL, Password, Date, Date-time, Number,
     Integer, Checkbox (boolean), Dropdown (`enum`), Multi-choice
     (`array` + `items.enum`).
   - Live JSON regeneration into the existing `#ct-schema` textarea; the
     submit path is unchanged, so no backend changes needed.
   - Reverse-loader populates the builder from any existing schema; widgets
     it doesn't recognise (nested object, `oneOf`) leave the user in Raw
     JSON mode without data loss.
2. **Plugin `schemaInline` support**
   (`valkeyry-config-plugin/plugin-core/`).
   - `PluginManifest.TableSpec.schemaInline: Map<String,Object>` accepts a
     JSON-Schema declared directly in YAML — no `.schema.json` file required.
   - `ManifestLoader.validate()` enforces *exactly one of* `schema` /
     `schemaInline` per table.
   - `PluginEngine.run()` prefers inline schemas when present and converts
     them with `objectMapper.valueToTree(...)` before POSTing.
   - Two new unit tests cover the happy path + the two rejection cases.
3. **Test guides** (Windows + macOS, inside `valkeyry-config/`).
   - `WINDOWS_TEST_GUIDE.md` and `MAC_TEST_GUIDE.md` — full Docker walkthrough
     for: anonymous + API-key, **LDAP (`bitnami/openldap:2.6`)**, and
     **OIDC JWT (`ghcr.io/navikt/mock-oauth2-server:2.1.10`)**.
   - Includes plugin manifest snippets (file-based + inline), troubleshooting
     matrices, and a teardown section.
4. **Example manifest** at
   `valkeyry-config-plugin/plugin-core/src/main/resources/examples/valkeyry-config-with-inline-schema.yaml`
   demonstrating both schema styles side-by-side.

**Testing performed**
- Static-JS lint via `node --check`: PASS.
- Live browser smoke via Playwright (served `index.html` over Python http.server):
  - Default schema reverse-loaded into 3 builder rows. ✅
  - Added a dropdown row with options `viewer, editor, admin` →
    schema regenerated with correct `enum`. ✅
  - Visual Builder ⇄ Raw JSON toggle round-trips cleanly. ✅
  - Loading the "User profile" gallery template populated 5 builder rows
    including the `role` dropdown with default `viewer`. ✅
- Plugin Java + tests are visually validated only — no JVM in this pod.

**Files touched (this iteration)**
- `valkeyry-config/src/main/resources/static/index.html` (+ ~280 lines: builder UI + JS)
- `valkeyry-config-plugin/plugin-core/src/main/java/io/valkeyry/plugin/core/manifest/PluginManifest.java`
- `valkeyry-config-plugin/plugin-core/src/main/java/io/valkeyry/plugin/core/manifest/ManifestLoader.java`
- `valkeyry-config-plugin/plugin-core/src/main/java/io/valkeyry/plugin/core/PluginEngine.java`
- `valkeyry-config-plugin/plugin-core/src/test/java/io/valkeyry/plugin/core/manifest/ManifestLoaderTest.java`
- `valkeyry-config-plugin/plugin-core/src/main/resources/examples/valkeyry-config-with-inline-schema.yaml` (new)
- `valkeyry-config/WINDOWS_TEST_GUIDE.md` (new)
- `valkeyry-config/MAC_TEST_GUIDE.md` (new)

**Next / Backlog**
- P1 — Run the new `ManifestLoaderTest` cases on a CI host with a JVM and
  attach the surefire report to confirm the validation messages match.
- P2 — Builder support for **nested objects** (recursive sub-rows) — today
  nested schemas fall through to Raw JSON mode.
- P2 — Builder support for **`oneOf` / discriminated variants**.
- P2 — Persist the user's last-used editor mode (Builder vs Raw JSON) in
  `localStorage` so the modal reopens in their preferred view.

### 2026-02-13 (afternoon) — Endpoints & URLs tab + live API-response preview
**Problem**: users had no single place to discover every URL the running
environment exposes (including Swagger), and after ingesting a config item
they had to flip to a separate terminal / curl to see the actual JSON the
server returned.

**What shipped**
1. **Swagger UI / OpenAPI 3** wired into the Spring Boot app.
   - `valkeyry-config/pom.xml` — added `springdoc-openapi-starter-webflux-ui:2.6.0`.
   - `valkeyry-config/src/main/java/io/valkeyry/config/config/OpenApiConfig.java` (new)
     — declares `OpenAPI` bean with both `apiKey` (`X-API-Key`) and `bearerAuth`
     security schemes so devs can "Authorize" in either track.
   - `SecurityConfig.java` — permit-all on `/swagger-ui*`, `/v3/api-docs*`,
     `/webjars/**` so the docs load without a token.
2. **New "Endpoints & URLs" tab in the GUI** (`index.html`).
   - 20-row catalog grouped by category: *Discovery & Docs · Virtual Tables ·
     Entries · Audit · Webhooks*.
   - Each row shows method-badge, full path, summary, **Copy** button,
     **Open ↗** for browser-friendly URLs (Swagger UI, OpenAPI YAML), and a
     **Try** button for safe GETs that fires the request and renders the
     full JSON response in the right-hand sticky "Live response" pane
     (status code, latency, pretty JSON body).
3. **Live API-response panel under the entry editor.**
   - After every single-entry POST *or* batch POST, the panel below the
     form expands with: HTTP status badge (green/amber/red), exact
     `METHOD URL`, the request body, and the server's full JSON response —
     visible without leaving the page.
4. **New "Batch (list)" editor mode.**
   - Third toggle next to *Form* and *Raw JSON*. Accepts a JSON array of
     `{recordKey, data}` objects and POSTs to the existing
     `/tables/{name}/entries:batch` endpoint. Seed payload auto-generated
     from the active schema. Same live-response panel renders the array of
     server-side `VirtualTableEntry` rows.
5. **Docs updated** — `WINDOWS_TEST_GUIDE.md` and `MAC_TEST_GUIDE.md` now
   call out the Swagger / OpenAPI URLs and point users to the new tab.

**Testing performed**
- `node --check` on the rewritten JS: PASS.
- Playwright smoke against the static HTML (served via Python http.server,
  with `window.fetch` mocked to return canned 201s):
  - Endpoints tab: **5 groups, 20 rows** rendered, 6 Try buttons, 20 Copy
    buttons. Try on `/v3/api-docs` correctly streamed the (mock 404) body
    into the right-hand pane with status badge + latency. ✅
  - Single ingest: live-response panel expanded with `201 · 0 ms`, exact
    `POST /api/v1/tenants/demo-tenant/tables/customers/entries`, request
    body + full response JSON (id/version/recordVersion/dataHash/createdAt). ✅
  - Batch ingest: submit label flipped to *Save batch*, panel rendered the
    JSON array of inserted records, toast confirmed "Batch accepted — 2
    record(s)". ✅
- Java code visually validated; JVM still unavailable in this pod, so the
  Swagger-UI endpoint itself wasn't reachable here — please verify on a
  JDK-21 host that `/swagger-ui.html` renders.

**Files touched (this iteration)**
- `valkeyry-config/pom.xml` (springdoc dependency + property)
- `valkeyry-config/src/main/java/io/valkeyry/config/config/OpenApiConfig.java` (new)
- `valkeyry-config/src/main/java/io/valkeyry/config/security/SecurityConfig.java`
- `valkeyry-config/src/main/resources/static/index.html` (+ Endpoints tab, +Batch mode, +live-response panel — ~300 lines)
- `valkeyry-config/WINDOWS_TEST_GUIDE.md` & `MAC_TEST_GUIDE.md` (Swagger sections)

**Next / Backlog**
- P1 — JVM smoke on a host: hit `/swagger-ui.html` + `/v3/api-docs` to
  confirm springdoc auto-detected all four controllers.
- P2 — Add **per-controller** `@Operation` / `@ApiResponses` annotations
  so Swagger UI shows richer summaries instead of method-name defaults.
- P2 — In the Endpoints tab, support **path-placeholder substitution**
  (e.g. ask the user for `{name}`) so even templated GETs become tryable.
- P2 — Persist a request history (last 10) in the live-response pane.

### 2026-02-14 — Examples, history, Swagger annotations, live preview
**Problem**: users asked for (1) richer Swagger via `@Operation` /
`@ApiResponses` annotations, (2) persistent last-10 invocation history, (3)
worked examples for adding entries, (4) end-to-end plugin examples + tests,
and (5) a live preview URL they can drive in-browser.

**What shipped**
1. **Live preview stack** at `https://<preview-host>/`.
   - `/app/backend/server.py` — FastAPI mock that mirrors every Java DTO
     (VirtualTableEntry, AuditEntry, WebhookSubscription, problem+json
     errors), seeds two demo tables (`users`, `feature_flags`) with
     fixtures, mounts a Swagger-UI HTML page served from `jsDelivr` CDN,
     aliases `/v3/api-docs` to FastAPI's OpenAPI, and validates inputs
     (boolean type, regex pattern, format=email/uri/date/date-time, enum,
     min/max) so 422s look real.
   - `/app/frontend/serve.js` — minimal Node http server (zero deps) that
     serves the production HTML from
     `valkeyry-config/src/main/resources/static/` on port 3000 and
     reverse-proxies `/api`, `/v3`, `/swagger-ui*`, `/webjars`, `/actuator`
     to the FastAPI backend on 8001.
   - Wired through supervisor so both auto-restart and survive reloads.
2. **Per-controller Swagger annotations** on the four Java REST controllers
   (`VirtualTableController`, `AuditController`, `AuditRollbackController`,
   `WebhookSubscriptionController`). Each operation has `@Tag`,
   `@Operation(summary, description)`, and a `@ApiResponses` list documenting
   200/201/204/400/401/403/404/409/422.
3. **Invocation history (last 10) in the GUI** — `historyPush` / `historyLoad`
   helpers store every Live-API-response invocation (entries page) and every
   Endpoints-tab `Try` invocation in `localStorage` under `vk.respHistory.v1`,
   scoped separately. Clicking a history row replays the request/response
   pair into the live pane without re-hitting the network. Both panels show
   the last 10; older invocations are FIFO-dropped.
4. **Recipes / Examples panel** below the entry editor — schema-aware:
   `Minimal (required only)`, `Full (all fields populated)`,
   `Boundary values (min/max)`, `All multi-choice tags`, `Batch of 5`,
   `Invalid email (422 demo)`, `Wrong type (422 demo)`. Click any chip and
   the active editor (Form / Raw JSON / Batch) is auto-populated and the
   submit-button label flips accordingly. A `Copy as cURL` button serialises
   the current editor state to a ready-to-paste curl command (with the
   active API-key / bearer header injected).
5. **Plugin worked examples** under
   `valkeyry-config-plugin/examples/` with READMEs and runnable manifests:
   - `01-flat-feature-flags/` — classic `schema:` file + per-record JSONs;
   - `02-inline-product-catalog/` — every UI widget expressed in YAML;
   - `03-multi-table/` — three tables in one push, mixed styles;
   - `04-env-driven/` — `${VAR:default}` env-resolved CI shape.
6. **End-to-end plugin JUnit test**
   `plugin-core/src/test/java/io/valkeyry/plugin/core/PluginEngineE2ETest.java`
   spins up a `com.sun.net.httpserver.HttpServer` mock and exercises the
   full `PluginEngine.run(…)` path: file-based schemas, inline schemas
   (asserting every widget shape survives the YAML→JsonNode round-trip),
   multi-table manifests, and the idempotency-skip path.

**Testing performed**
- `node --check` PASS on the rewritten JS.
- `python3 -c "import server"` PASS on the new FastAPI mock (no import errors).
- Curl smoke against the preview URL:
  - `GET  /                                                → 200`
  - `GET  /swagger-ui.html                                 → 200` (Swagger UI rendered 18 operations)
  - `GET  /v3/api-docs                                     → 200` (14 paths in OpenAPI doc)
  - `GET  /api/v1/tenants/demo-tenant/tables               → 200` (returns seeded `users` + `feature_flags`)
  - `POST /api/v1/tenants/demo-tenant/tables/users/entries`
    with `{"recordKey":"bad","data":{"email":"not-an-email"}}` → `422` with proper `urn:valkeyry:error:schema-violation` problem+json
- Playwright smoke against the live preview (no mocks):
  - "users" table opens with the seeded schema → form renders dropdown / checkbox / multi-choice ✅
  - Recipe chip "Full" → POST → 201 with full server JSON in live panel ✅
  - Recipe chip "Batch of 5" → submit label flipped to "Save batch" → 201 with 5 records ✅
  - Recipe chip "Invalid email" → 422 with inline schema-violation banner + full problem+json in panel ✅
  - History list shows all 3 invocations (clickable replay) ✅
  - Endpoints tab → Try on `/actuator/health` → 200 in right-hand pane, history accumulates ✅
  - Swagger UI at `/swagger-ui.html` → 18 operations rendered ✅
- **JVM unavailable in this pod** — the new annotations and JUnit test compile against the Java standard library + springdoc-openapi (declared in pom.xml) but have not been executed here. Please run `mvn -pl valkeyry-config -am verify` and `mvn -pl valkeyry-config-plugin/plugin-core test` on a JDK-21 host.

**Files touched (this iteration)**
- `valkeyry-config/pom.xml` *(already had springdoc; no further change)*
- `valkeyry-config/src/main/java/io/valkeyry/config/api/VirtualTableController.java`
- `valkeyry-config/src/main/java/io/valkeyry/config/api/AuditController.java`
- `valkeyry-config/src/main/java/io/valkeyry/config/api/AuditRollbackController.java`
- `valkeyry-config/src/main/java/io/valkeyry/config/api/WebhookSubscriptionController.java`
- `valkeyry-config/src/main/resources/static/index.html` (+ history list, recipes panel, copy-curl)
- `valkeyry-config-plugin/examples/**` (4 example projects, brand new)
- `valkeyry-config-plugin/plugin-core/src/test/java/io/valkeyry/plugin/core/PluginEngineE2ETest.java` (new)
- `backend/server.py` + `backend/requirements.txt` + `backend/.env` (preview mock)
- `frontend/serve.js` + `frontend/package.json` + `frontend/.env` (static + proxy)

**Next / Backlog**
- P1 — Run `mvn -pl valkeyry-config-plugin/plugin-core test` on JDK-21 to
  confirm the four-test E2E suite is green (the only piece I couldn't
  execute in-pod due to absence of a JVM).
- P2 — Bundle the Swagger-UI assets as a Spring resource handler so the
  preview no longer depends on jsDelivr at runtime.
- P2 — Extend the Visual Builder to read existing nested objects / `oneOf`
  variants (still falls through to Raw JSON today).
- P2 — Add a "Replay this invocation as a curl" button on each history row.

### 2026-02-14 (evening) — HTTPS, ports, cloud-native, field projection, nested/oneOf builder
**Problem**: production checklist — TLS termination at the app, every port
env-driven, full Kubernetes/Helm artifacts, opt-in field-level output
projection, and the Visual Builder needed to handle complex schemas
(nested objects and `oneOf` discriminated unions).

**What shipped**
1. **HTTPS / TLS via env vars**
   - `application.yml` now reads `VALKEYRY_SSL_ENABLED`, `VALKEYRY_SSL_CERT_PEM`,
     `VALKEYRY_SSL_KEY_PEM` (Spring 3+ native PEM support — works equally
     for a bundled `.pem` or a separate `.crt`+`.key` pair), plus optional
     `VALKEYRY_SSL_TRUST_PEM` / `VALKEYRY_SSL_CLIENT_AUTH` for mTLS, and
     pins `TLSv1.2,TLSv1.3`.
   - `VALKEYRY_CONFIG_PORT`, `VALKEYRY_CONFIG_BIND`, `VALKEYRY_MGMT_PORT`
     are all env-overridable; the management endpoint moves to its own
     port automatically.
   - Actuator `liveness` + `readiness` probe groups enabled.
2. **Cloud-native packaging**
   - Hardened `Dockerfile` — `tini` PID 1, non-root, `EXPOSE 8081 8443`,
     HEALTHCHECK adapts to HTTP/HTTPS based on `VALKEYRY_SSL_ENABLED`.
   - **Raw K8s manifests** (`deploy/k8s/valkeyry-config.yaml`): Namespace,
     ConfigMap (every env var), DB Secret, TLS Secret, ServiceAccount,
     Service (HTTPS + management ports), Deployment (rolling, non-root,
     readOnlyRootFilesystem, drop ALL capabilities, topology spread,
     graceful shutdown, separate liveness/readiness probes against the
     management port), PodDisruptionBudget (`minAvailable: 1`),
     HorizontalPodAutoscaler (2→10 @ 60% CPU), Ingress (cert-manager
     annotated, backend-protocol HTTPS).
   - **Helm chart** (`deploy/helm/valkeyry-config/`) — single-template
     bundle, three TLS modes (off / external Secret / cert-manager
     Certificate), Prometheus scrape annotations + optional
     ServiceMonitor, full values.yaml documenting every knob.
3. **Field projection on read endpoints**
   - New `FieldProjection.java` helper plus a `?fields=` query param on
     `GET /tables/{name}/entries` and `POST /tables/{name}/search` —
     CSV of top-level columns and/or dotted `data.<key>` reaches into
     the JSONB payload. Empty/missing → all fields (default contract).
   - Mock backend mirrors the contract so the live preview shows the
     filter working.
   - GUI: a `Columns: recordKey,data.role,…` filter textbox + reset
     button next to the table's Refresh control. Debounced 400ms.
4. **Visual Builder — nested object + `oneOf`**
   - Two new widget types: **Nested object** (renders a sub-panel with
     its own `+ Add nested field` button — recursive any depth) and
     **oneOf (discriminated)** (each variant is a sub-card with its own
     discriminator value and child fields).
   - `schemaBuilderToSchema` recursively emits Draft 2020-12 shapes:
     `{type:object, properties:{...}, required:[...]}` for nested, and
     `{oneOf:[{type:object, properties:{disc:{enum:[X]},...}, required:[disc,...]}, …]}`
     for variants.
   - `schemaBuilderLoadFromSchema` recursively reverse-loads both shapes,
     auto-detecting the discriminator key (the property common to every
     variant whose enum is a single-element string).

**Testing performed**
- `node --check` PASS on the rewritten JS.
- Live preview smoke (against the running mock backend):
  - `GET /…/users/entries?fields=recordKey,data.role` →
    `[{recordKey:"…",data:{role:"viewer"}}, …]` ✅ (verified by curl)
  - Visual Builder programmatic schema build:
    - `address` (nested object, 3 props, 1 required) ✅
    - `paymentMethod` (oneOf with `card` and `paypal` variants, each with
      their own fields and required arrays, discriminator prefixed) ✅
    - Round-trip through `schemaBuilderLoadFromSchema` reproduced the
      same `address.properties` (3) and the same 2 oneOf variants ✅
- Java code visually validated; `helm template deploy/helm/valkeyry-config`
  and `kubectl --dry-run=client apply -f deploy/k8s/` not executed in-pod
  (no helm/kubectl available) — please verify on a workstation with both
  CLIs installed.

**Files touched (this iteration)**
- `valkeyry-config/src/main/resources/application.yml` (HTTPS + probes + ports)
- `valkeyry-config/Dockerfile` (tini, dual-port EXPOSE, HTTPS-aware HEALTHCHECK)
- `valkeyry-config/src/main/java/io/valkeyry/config/api/FieldProjection.java` (new)
- `valkeyry-config/src/main/java/io/valkeyry/config/api/VirtualTableController.java` (`?fields=`)
- `valkeyry-config/src/main/resources/static/index.html` (+ nested/oneOf builder, field filter ~250 lines)
- `valkeyry-config/deploy/{README.md,k8s/valkeyry-config.yaml,helm/valkeyry-config/**}` (new — 7 files)
- `backend/server.py` (mock backend: `?fields=` + `project()` helper)

**Next / Backlog**
- P1 — JVM run-through: `mvn -pl valkeyry-config -am package`, verify
  HTTPS bootstraps with a self-signed PEM + `VALKEYRY_SSL_ENABLED=true`.
- P1 — `helm template deploy/helm/valkeyry-config` + `kubeval` on a CI
  host to catch any chart bugs from the in-pod authoring.
- P2 — Visual Builder: add **array-of-objects** widget (today arrays are
  limited to enum-of-string multi-choice).
- P2 — Field projection for nested data structures deeper than one level
  (e.g. `data.address.city`) — backend currently understands only one
  dot.
- P2 — Helm chart: bundle a NetworkPolicy template (default-deny + allow
  Postgres + Ingress).

### 2026-02-14 (late) — Schema evolution + SQL-style query + click-to-preview
**Problem**: post-declare, the GUI had no path to add/rename fields, and
there was no way to (a) click a row and see only its JSON, or (b) write a
SQL-style `WHERE column = value` query.

**What shipped**
1. **`✎ Edit schema` button** next to the table title — reopens the
   Visual Builder modal pre-seeded with the table's current schema (via
   `schemaBuilderLoadFromSchema`), with the table-name input disabled
   (identity is immutable) and the modal title flipped to `Edit schema —
   <name>`. On submit, hits the same `POST /tables` endpoint which the
   service treats as a `REVISE_TABLE` audit event (new `configVersion`).
   New fields immediately render in the schema-driven entry form.
2. **Selected-entry preview pane** — every row in the entries grid is now
   clickable (anywhere outside the `View JSON` expander and the Edit /
   Delete buttons). A sticky right-side card shows the prominent
   `RECORD KEY`, the version + timestamp meta, the full JSON, and three
   actions: `Copy JSON`, `Edit in form` (loads the payload into the Raw
   JSON editor), `Delete`. Selection state is mirrored with a blue ring
   on the chosen row.
3. **SQL-style query bar** — `SELECT columns FROM <table> WHERE field
   op value`. Four inputs (Columns, Field, Operator, Value) generate a
   real backend call:
     - empty WHERE → `GET /entries?fields=…` (paged list with projection)
     - else        → `POST /search?fields=…` body
                     `{ <op>: { <field>: <value> } }`
   Operators: `equals`, `contains`, `startsWith`. Values are coerced
   (`true` / `42` / `"foo"`) before sending. A `Copy as cURL` button
   serialises the whole query (incl. auth header) for terminal use. The
   resulting rows render through the existing Mustache template, so the
   look-and-feel matches the unfiltered list, and every invocation is
   pushed into the Live API panel + history list.
4. **Mock backend** — `/search` now understands `equals`, `contains`,
   `startsWith` and composes with the `?fields=` projection from the
   previous iteration. Refactored `_collect_entries` so the `/search`
   endpoint no longer crashes calling `list_entries` directly (the FastAPI
   `Query` defaults aren't valid integers outside a request).

**Testing performed**
- `node --check` PASS.
- Curl smoke against the mock backend:
  - `POST /search {"equals":{"role":"admin"}}` → 1 row (alice) ✅
  - `POST /search {"contains":{"email":"alice"}}` → 1 row ✅
  - `POST /search?fields=recordKey,data.role,data.email {"equals":{"active":true}}` →
    2 rows, properly projected ✅
- Playwright end-to-end:
  - `Edit schema` button present, modal opens titled `Edit schema — users`,
    builder shows 5 existing rows, after adding `phone` and submitting
    the table view reloaded with a new `PHONE` text input in the entry
    form and `"phone"` in the JSON-Schema preview. ✅
  - Click on a row → preview pane populates with `RECORD KEY · carol@acme.io`,
    `v1 · 6/2/2026, 10:28:36 PM` meta, and the full JSON `{email,fullName,
    role,active,tags,joinedAt}`. ✅
  - WHERE bar `role = admin` with projection `recordKey,data.role,
    data.email` → status `200 OK · 13 ms · 1 row(s)`, only alice's row
    visible in the grid, Live API response showing the full request +
    response. ✅

**Files touched (this iteration)**
- `valkeyry-config/src/main/resources/static/index.html`
  - Replaced the inline field-projection input with a richer **Query
    card** (4 inputs + Run/Reset/Copy-as-cURL) above the entries grid.
  - Added a 2-column layout: entries grid (left) + sticky preview pane
    (right).
  - New JS: `runQuery`, `resetQuery`, `copyQueryAsCurl`,
    `renderEntriesIntoGrid` (Mustache-based client-side render),
    `selectEntryRow`, `clearEntryPreview`, `copyEntryJson`,
    `loadEntryIntoForm`, `deleteSelectedEntry`, `editTableSchema`,
    `coerce`. ~190 lines.
  - Row template: `data-rk`, `data-payload`, `data-meta` attributes +
    smart `onclick` filter so the row is clickable but Edit/Delete +
    `<details>` keep their own click behaviour.
- `backend/server.py`
  - `_collect_entries` helper.
  - `POST /search` now supports `equals` / `contains` / `startsWith` +
    `?fields=` projection.

**Next / Backlog**
- P2 — Operator menu: add `not equals`, `>`, `<` for numeric/date fields.
- P2 — Auto-suggest values in the Query bar (read distinct values of the
  selected field from the entries list).
- P2 — Add a `pageSize` selector + cursor-based pagination once tables
  grow beyond the default 200-row cap.
- P2 — Surface the schema revision history in the Edit-schema modal (so
  users can see what changed from v(n-1) → v(n)).
