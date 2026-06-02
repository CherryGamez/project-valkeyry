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
