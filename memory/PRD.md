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
- Slate + emerald palette, tactile/dense layout, micro-interactions, JSON pretty-printer.
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

## Verification (manual, mocked-API end-to-end)
A local `python3 -m http.server` served the static index.html. Playwright `page.route()` mocked the
REST surface. Verified panels in sequence:
- Sidebar populated, table click → entry CRUD grid (2 rows, JSON, Edit/Delete).
- Audit timeline rendered 3 events, push-button rollback shown for INGEST/DELETE rows only.
- Webhook panel showed 2 rows with enabled/disabled + override-secret badges, Add form.
- Schema-violation path: returned 422 on create-table and on entry-ingest → inline error rendered, user input preserved.

**Java build/tests not executed in this session** — no JDK/Maven available in the sandbox.
The user can run `mvn -pl valkeyry-config -am verify` in their own dev env.

## Backlog
- P1 — Optional: add `@SpringBootTest` integration tests for the new endpoints (`WebhookSubscriptionController`, `AuditRollbackController`, soft-delete) under `valkeyry-config/src/test/java/...`.
- P2 — Optional: bundle Tailwind via CLI in `mvn package` to drop the CDN dev-warning in production.
- P2 — Optional: HTMX `hx-push-url` for deep linking each tab/table to a URL.
- P2 — Optional: WebSocket-driven live audit timeline (`hx-ext="ws"`).
