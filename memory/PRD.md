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
- P2 — Surface the schema revision history in the Edit-schema modal (so
  users can see what changed from v(n-1) → v(n)).

### 2026-02-15 — PL/SQL-style unified query (UI + REST)
**Problem**: query bar required pre-selecting a table by clicking it in the
sidebar; `recordKey` (the PK) wasn't even a field option; and there was
no single REST endpoint that mirrored the UI's WHERE behaviour.

**What shipped**
1. **Query bar redesigned** — six controls in PL/SQL order:
   `FROM (table) · SELECT (columns) · WHERE field · OPERATOR · VALUE · Run · Reset`.
   The FROM dropdown lists every table in the tenant; switching tables
   re-fetches the picked table's schema and repopulates the WHERE field
   dropdown with `recordKey` first, then every `data.<col>` key.
   Value auto-suggest follows the picked field.
2. **`POST /api/v1/tenants/{tenantId}/query`** — new unified REST endpoint
   (Java `UnifiedQueryController` + Python mock parity). Body:
   ```
   { "table": "users",
     "field": "recordKey" | "data.<col>",
     "op":    "equals" | "contains" | "startsWith",
     "value": <any JSON>,
     "fields": "recordKey,data.role"   // optional projection (CSV)
   }
   ```
   `recordKey + equals` → fast-path PK lookup (`GET /entries/{key}`).
   Anything else → `service.search(...)` with the existing JSONB engine.
3. **GUI fast-path** — `runQuery()` detects `recordKey + equals` client
   side and calls `GET /entries/{key}` directly (saves a hop); other
   combinations POST to the new `/query` endpoint.
4. **Endpoints catalog** updated to surface the new endpoint with a
   tenant-aware path.

**Testing performed**
- Curl against the live preview:
  - `POST /query {table:users, field:recordKey, op:equals, value:"alice@acme.io"}` →
    1 row, full payload returned. ✅
  - `POST /query {table:users, field:data.role, op:equals, value:"admin", fields:"recordKey,data.role"}` →
    1 row, projected. ✅
  - `POST /query {table:users, field:data.email, op:contains, value:"alice"}` →
    1 row. ✅
  - **Cross-table** `POST /query {table:feature_flags, field:data.enabled, op:equals, value:true}` →
    2 rows (`dark.mode`, `checkout.v2`). ✅
- Playwright: PK lookup from the GUI returned `200 · 1 row(s) · 70 ms · GET /entries/alice@acme.io`. ✅
- Switching FROM dropdown auto-repopulated the WHERE-field options
  with `feature_flags`'s columns. ✅

**Files**
- `valkeyry-config/src/main/java/io/valkeyry/config/api/UnifiedQueryController.java` (new)
- `valkeyry-config/src/main/resources/static/index.html` (Query card redesign + JS)
- `backend/server.py` (`POST /query`)
1. **Searchable sidebar** — `<input type="search">` below the "VIRTUAL TABLES" label runs a DOM substring filter (case-insensitive) on every row's `data-table-name`. Zero network calls. Survives sidebar refreshes via a one-shot `htmx:afterSettle` hook that re-applies the active filter. Inline `No tables match this filter.` when nothing matches.
2. **Value auto-suggest** in the Query bar — picking a field fetches up to 500 rows projected to just that column via `?fields=data.<key>`, then feeds a `<datalist>` with the top 50 distinct values. Per-(table,field) cached. Verified: `data.role` → `['admin','editor','viewer']`.
3. **Cursor pagination** — pager strip below entries: `page N · rows X–Y` status, `Rows/page` selector (25/50/100/200), `‹ Prev` and `Next ›`. Cursor lives in `window.__pageOffset` / `window.__pageSize`; resets on table switch. Mock backend's `list_entries` now accepts `offset: int`. Verified: page 1 → 2 rows, Next → page 2 → 1 row, status `page 2 · rows 3–4`.

**Files**: `valkeyry-config/src/main/resources/static/index.html`, `backend/server.py`.


---

## 2026-02-15 — Phase 1+2+3: Hybrid Auth, Admin Panel, Tools Converter

### Goal
Add a Camunda-Identity style login + admin experience on top of the headless schema registry:
1. Login page with **WebSSO** *and* **username/password** options.
2. Backend mints HS256 JWTs; verifies external OIDC when SSO is enabled.
3. Admin panel for Tenant + User CRUD.
4. Role-based gating: `reader` (default), `writer`, `admin`.
5. Local **admin bypass** via env vars so a fresh DB still admits the operator.
6. Independent **SSO** and **LDAP** toggles.
7. **Tools** tab for converting XLSX/CSV/DMN → JSON (ready-to-ingest shape).
8. Fix the persistent `InvalidBearerTokenException: Missing dot delimiter(s)` error.

### What shipped — Java (`/app/valkeyry-config/`)
- `db/migration/V4__admin_users_and_tenants.sql` — `tenant`, `app_user`, `app_user_tenant`.
- `domain/admin/{AdminTenant, AppUser, AppUserTenant}` + matching R2DBC repositories.
- `config/AuthProperties.java` — env-var driven toggles (`VALKEYRY_SSO_ENABLED`,
  `VALKEYRY_LDAP_ENABLED`, `VALKEYRY_ADMIN_USERNAME`, `VALKEYRY_ADMIN_PASSWORD`,
  `VALKEYRY_JWT_SECRET`, `VALKEYRY_JWT_ISSUER`, `VALKEYRY_JWT_TTL_SECONDS`).
- `config/AuthBootstrapConfig.java` — `PasswordEncoder` (BCrypt) bean,
  `ReactiveJwtDecoder` bean (composite local HS256 + optional external OIDC),
  one-shot `seedBuiltinAdmin` on `ApplicationReadyEvent`.
- `security/local/LocalJwtService.java` — HS256 mint with `valkeyry.role`, `valkeyry.tenants`,
  `valkeyry.source` claims.
- `security/local/CompositeReactiveJwtDecoder.java` — routes local vs external tokens by `iss`.
- `security/local/SafeBearerTokenAuthenticationConverter.java` — **fixes the
  "Missing dot delimiter(s)" bug** by rejecting non-3-segment bearers *before* the OIDC pipeline.
- `security/oidc/JwtTenantAuthoritiesConverter.java` — extended to emit
  `SCOPE_admin` + implicit `ROLE_VALKEYRY_WRITER` for admin role.
- `security/SecurityConfig.java` — rewrites the chain with the new bearer converter,
  `@EnableReactiveMethodSecurity`, admin-path gate, conditional LDAP filter.
- `api/AuthController.java` — `GET /config`, `POST /login`, `GET /me`, `POST /logout`.
- `api/AdminController.java` — `*/api/v1/admin/tenants`, `*/api/v1/admin/users`; class-level
  `@PreAuthorize("hasAuthority('SCOPE_admin')")`.
- `api/ToolsController.java` — `POST /api/v1/tools/convert/{xlsx|csv|dmn}` using
  Apache POI, OpenCSV, and Camunda DMN model.
- `pom.xml` — added `poi-ooxml 5.3.0`, `opencsv 5.9`, `camunda-engine-dmn 7.21.0`.
- `application.yml` — `spring.security.oauth2.resourceserver.jwt.issuer-uri` is now blank
  by default so autoconfig doesn't probe a missing OIDC issuer at startup.
- Unit tests:
  `SafeBearerTokenAuthenticationConverterTest`, `LocalJwtServiceTest`, `ToolsControllerTest`.

### What shipped — Static UI (HTMX)
- `static/login.html` — WebSSO button (hidden when `ssoEnabled=false`) + username/password form.
- `static/admin.html` — Tenant + User CRUD with `<dialog>` modals, role/source badges.
- `static/tools.html` — Drag/drop dropzone, kind selector, JSON viewer, copy/download buttons.
- `static/assets/auth.js` — `vk.*` helpers (`getAuth/setAuth/clearAuth/requireAuth/logout/fetch`)
  + `htmx:configRequest` hook injecting `Authorization: Bearer <jwt>`
  + `htmx:responseError` 401-redirect to `/login.html`.
- `static/index.html` — top-bar gains `Admin` (admin-only), `Tools`, `Logout` buttons;
  `bootApp()` enforces `vk.requireAuth()`; no longer auto-opens the tenant-connect modal
  (it trapped the topbar under the `<dialog>` backdrop and broke logout).

### What shipped — FastAPI mock (`/app/backend/server.py`)
For the Emergent preview pod (no JVM), the Python mock now mirrors:
- `/login.html`, `/admin.html`, `/tools.html`, `/assets/{name}` static routes.
- `/api/v1/auth/{config,login,me,logout}` with a 3-segment compact-JWS shaped fake token.
- `/api/v1/admin/{tenants,users}` full CRUD (in-memory).
- `/api/v1/tools/convert/csv` (csv module), `/dmn` (xml.etree). `xlsx` returns 501.

### Testing (iteration_1.json + mvn test)
- **Backend (FastAPI mock): 14/14 pytest cases passed** (auth flows, admin CRUD, role gating, tools converters).
- **Java backend (Maven): 33/33 unit + integration tests pass via `mvn test`** on JDK 21 (Temurin 21.0.5) — `BUILD SUCCESS` in 13.7s. Breakdown:
  - `CompositeReactiveJwtDecoderTest`: 7 (issuer-based routing: local HS256, external RS256, SSO-disabled rejection, forged-issuer attack, malformed token, JWKS round-trip, missing-iss safety)
  - `LdapBasicAuthenticationManagerTest`: 4 (embedded UnboundID directory: single-tenant, multi-tenant, wrong password, unknown user)
  - `SafeBearerTokenAuthenticationConverterTest`: 3 (JWT dot-delimiter guard)
  - `LocalJwtServiceTest`: 2 (HS256 mint with `valkeyry.role/tenants/source` claims)
  - `ToolsControllerTest`: 2 (CSV + DMN converters)
  - `VirtualTableControllerSliceTest`: 6 (regression — still green after security rewrite)
  - `AuditWebhookPublisherTest`: 5, `JsonSchemaValidatorServiceTest`: 2, `PayloadFingerprintTest`: 2

### Hybrid auth live: OpenLDAP + Keycloak sidecars
- `docker-compose.dev.yml` extended with `openldap` (Bitnami 2.6.8) and `keycloak` (25.0) services under the `hybrid` profile. App service gets `VALKEYRY_SSO_ENABLED=true`, `VALKEYRY_LDAP_ENABLED=true`, `VALKEYRY_OIDC_ISSUER=http://keycloak:8079/realms/valkeyry`, LDAP wiring matching the SDS.
- `db/ldap/bootstrap.ldif` — seeds `bob/secret` (ou=acme), `alice/s3cret` (acme+globex), `ops/opspw` (ops).
- `db/keycloak/realm-export.json` — realm `valkeyry`, public client `valkeyry-config`, users `alice/s3cret` (writer, tenants=[acme,globex]) and `admin-sso/sso-admin` (admin). Protocol mappers emit `valkeyry.role` + `valkeyry.tenants` claims.
- Quickstart: `docker compose -f docker-compose.dev.yml --profile hybrid up -d`.
- Validation curl flow lives in `WINDOWS_TEST_GUIDE.md` §3.5 and `MAC_TEST_GUIDE.md` §3.5.
- **Frontend: ~90%** initially. One UI bug found — the logout-btn on `/` was blocked by the
  auto-opening tenant-connect `<dialog>` backdrop. **Fixed** by not auto-opening that modal
  and adding inline `onclick` on the logout button. Verified end-to-end via Playwright:
  `login → admin → / → click logout → /login.html`.

### Tailwind production bundle
- Replaced `cdn.tailwindcss.com` runtime in `login.html`, `admin.html`, `tools.html`
  with a pre-built 12 KB minified `assets/tailwind.css` (Tailwind 3.4.17 CLI).
- Source kept committed at `assets/tailwind.config.cjs` + `assets/tailwind.src.css`;
  rebuild instructions in `assets/README.md`. No Node toolchain required for the


---

## 2026-02-15 — Multi-condition query builder + scrollable expand-in-place results

### Goal
1. Records list lives in a scrollable frame (vertical scrollbar); clicking a row expands inline.
2. WHERE clause supports multiple PL/SQL-style conditions joined by per-row AND/OR connectors.
3. Full operator set: `equals`, `notEquals`, `contains`, `notContains`, `startsWith`, `endsWith`, `regex`, `in`, `notIn`, `gt`, `gte`, `lt`, `lte`, `between`, `isNull`, `isNotNull`, `before`, `after`, `onDate`, `betweenDates`.

### Backend (Java)
- `service/query/PredicateCompiler.java` — turns a list of `{field, op, value, value2?, connector}` rows into a parameterised Postgres WHERE fragment over the `data` JSONB column. Injection-safe (named parameters only, field-name regex validation).
- `service/VirtualTableService.searchAdvanced()` — runs the compiled SQL via R2DBC `DatabaseClient` against `virtual_table_entry` with the tenant/table/is_latest guard.
- `api/UnifiedQueryController.queryAdvanced()` → `POST /api/v1/tenants/{tenantId}/query2` (new endpoint; legacy `/query` preserved).
- 19 unit tests in `PredicateCompilerTest` covering every operator + injection guard + invalid-connector / illegal-field-name / unsupported-op error paths.

### Backend (Python mock)
- `query_advanced()` in `/app/backend/server.py` mirrors all 19 ops including CSV-vs-array `in` value forms and per-row AND/OR connectors.

### Frontend (HTMX `index.html`)
- New dynamic condition-row builder (`addQueryCondition`, `updateQueryRowInputs`, `readQueryConditions`).
- `QUERY_OPS` array drives UI rendering: `pair:true` for between/betweenDates → second input; `dateType:true` → datetime-local picker; `needsValue:false` → input hidden with "no value needed" hint.
- Connector dropdown invisible on row 1, AND/OR on rows 2+.
- Entries grid now wrapped in `data-testid="entries-frame"` (`.ios-card` with `overflow-y-auto`, `max-height: 65vh`).
- Inline expand-on-click via `toggleEntryRow()` — one row open at a time, with chevron rotation, ring-2 styling, and a Copy-JSON button inside the expanded body.
- `copyQueryAsCurl()` emits a curl POSTing to `/query2` with the full conditions array.
- "Reset" clears all rows and resets `__qbRowSeq` so test IDs are deterministic.

### Testing
- **Java `mvn test`: 52/52** (was 33 → +19 new `PredicateCompilerTest`).
- **Iteration 2 testing agent**: Backend 14/14, Frontend 100% in-scope flows. Verified: 2-condition AND, OR connector, isNotNull, startsWith (case-insensitive), in (array + CSV), reset behavior, expand-collapse, only-one-expanded-at-a-time, Copy as cURL, backward-compat /query.
- Test report: `/app/test_reports/iteration_2.json`.

### Files changed
- New: `service/query/PredicateCompiler.java`, `service/query/PredicateCompilerTest.java`.
- Updated: `api/UnifiedQueryController.java`, `service/VirtualTableService.java`, `/app/backend/server.py`, `/app/valkeyry-config/src/main/resources/static/index.html`.
  Maven build — the CSS is shipped as a static resource.

### Credentials
- Built-in admin: `admin / admin` (env-overrideable). See `/app/memory/test_credentials.md`.

### Toggles (env vars)
| Var                          | Default | Effect                                       |
|------------------------------|---------|----------------------------------------------|
| `VALKEYRY_SSO_ENABLED`       | `false` | Mount the WebSSO button + external decoder  |
| `VALKEYRY_LDAP_ENABLED`      | `false` | Mount the LDAP Basic filter                 |
| `VALKEYRY_ADMIN_USERNAME`    | `admin` | Local admin username                        |
| `VALKEYRY_ADMIN_PASSWORD`    | `admin` | Local admin password                        |
| `VALKEYRY_JWT_SECRET`        | dev key | HS256 secret (≥32 bytes)                    |
| `VALKEYRY_OIDC_ISSUER`       | empty   | Required when SSO enabled                   |

### Setup-guide updates
- `valkeyry-config/WINDOWS_TEST_GUIDE.md` — new §3.4 covering admin/tools/login flow + bug fix.
- `valkeyry-config/MAC_TEST_GUIDE.md` — same §3.4.

### Files of reference (next session)
- `valkeyry-config/src/main/java/io/valkeyry/config/api/{Auth,Admin,Tools}Controller.java`
- `valkeyry-config/src/main/java/io/valkeyry/config/security/SecurityConfig.java`
- `valkeyry-config/src/main/java/io/valkeyry/config/security/local/{LocalJwtService,SafeBearerTokenAuthenticationConverter,CompositeReactiveJwtDecoder}.java`
- `valkeyry-config/src/main/resources/static/{login,admin,tools,index}.html`, `assets/auth.js`
- `backend/server.py` (mock parity)

### 2026-02-07 — Fix: native Basic Auth prompt loop when switching to console
**Bug:** After creating a tenant in the admin GUI and clicking **Console**, the user pressed
**Connect** and entered something like `admin/admin` (mistaking the Token field for a password
prompt). The HTMX call to `/api/v1/tenants/{id}/tables` failed with `401 Unauthorized` carrying
`WWW-Authenticate: Basic realm="Realm"`. Browsers intercept that header on XHR/fetch responses
and pop their native username/password modal, which then re-sends `Authorization: Basic …` to
the same failing endpoint — looping forever.

**Root cause:** `AuthenticationWebFilter`'s default `authenticationFailureHandler` is
`ServerAuthenticationEntryPointFailureHandler(HttpBasicServerAuthenticationEntryPoint)`. Both
Track-2 filters in `SecurityConfig` (API-key + optional LDAP basic) inherited this default and
therefore advertised a Basic challenge on every failure — even though `httpBasic` is disabled
on the chain.

**Fix:** `valkeyry-config/src/main/java/io/valkeyry/config/security/SecurityConfig.java`
- Added `SILENT_401 = ServerAuthenticationEntryPointFailureHandler(HttpStatusServerEntryPoint(401))`.
- Wired it on both `apiKeyFilter` and (when mounted) `basicFilter`.
- Result: bad API key / LDAP credentials now produce a plain 401 the SPA's `vk.fetch` handles
  (clears token → redirects to `/login.html`). No browser modal.

**Regression test:** `src/test/java/io/valkeyry/config/security/SecurityConfigBasicAuthChallengeTest.java`
asserts the silent handler sets 401 and emits no `WWW-Authenticate` header.

### 2026-02-07 — Fix: virtual-table entry form silently dropped user input ("not a valid email")
**Bug:** On the Console, adding any entry to a virtual table via the Form-mode editor failed
with `SCHEMA VALIDATION FAILED — $ — not a valid email` (or `required property missing`) even
when the user clearly typed a valid email like `test@gmx.com` or `test@test.com`. The 422
response showed the request body had `data.email = ""` — the form values weren't being read at
all.

**Root cause:** `index.html#ingestEntry()` always read from the hidden `#entry-data` textarea
which is seeded by `sampleFromSchema(schema)` — and that helper inserts `""` for every
required string. In Form mode, the live schema-driven widgets (`#entry-form-fields`) are never
synced back to the textarea, so the request submitted the empty seed rather than what the user
typed.

**Fix:** `valkeyry-config/src/main/resources/static/index.html`
- `ingestEntry()` now branches on `window.__entryMode`:
  - `json` → read from the textarea (unchanged, the textarea *is* the source of truth there)
  - default/`form` → call `collectFormValues(entry-form-fields)` so the user's typed values
    reach the API.
- `renderSchemaErrors()` now normalises violation shapes across all three backends
  (Python mock: `pointer`/`message`, Java networknt: `path`/`instanceLocation`, Java fallback:
  raw string). Previously every violation displayed pointer `$` because the UI only looked at
  `v.path` which the mock never sets.

**Verified** end-to-end on the running preview:
- `test@gmx.com` + form submit → 201 + toast "Entry 'newuser1' saved", row appears in list.
- Invalid email caught client-side by the native `<input type="email">` validator before submit.

