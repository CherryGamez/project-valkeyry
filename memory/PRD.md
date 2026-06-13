# Valkeyry Ecosystem — Internal PRD (handoff memory)

## Original problem statement
Prepare a Product Requirement Document (PRD), Technical Requirement Document
(TRD), and App Flow document for `valkeyry-config` and `valkeyry-ipaas`.
Integrate these documents as an additional tab in the GUI.

Reference repo: https://github.com/CherryGamez/project-valkeyry

## User choices (confirmed)
- Per-service documents (6 docs total: 3 for `valkeyry-config`, 3 for `valkeyry-ipaas`).
- Markdown files + client-side renderer (marked.js / react-markdown).
- Docs exposed only inside the GUI tab (no standalone URLs in UI nav).
- Source material: local `/app` codebase only.
- Docs placement: per-service `valkeyry-*/docs/`.

## What's been implemented (2026-02-12)

### Authored Markdown documents (6 files)
- `/app/valkeyry-config/docs/PRD.md`
- `/app/valkeyry-config/docs/TRD.md`
- `/app/valkeyry-config/docs/App_Flow.md`
- `/app/valkeyry-ipaas/docs/PRD.md`
- `/app/valkeyry-ipaas/docs/TRD.md`
- `/app/valkeyry-ipaas/docs/App_Flow.md`

Docs are written from the local codebase (controllers, services, security
config, Flyway migrations, React panels, READMEs). Cross-linked between
PRD ↔ TRD ↔ App Flow within each service.

### Doc assets shipped with each GUI
- `valkeyry-config` static: copied to `src/main/resources/static/docs/{config,ipaas}/*.md`
- `valkeyry-ipaas` React `public/docs/{config,ipaas}/*.md` (auto-bundled into
  the production build under `build/docs/...`)

### GUI integration
- `valkeyry-config` (`index.html`):
  - New tab button `data-testid="tab-docs"` next to "Endpoints & URLs"
  - Tab pane with product switcher (config / ipaas) and doc switcher (PRD / TRD / App Flow)
  - Download button + source-path indicator
  - Markdown rendered client-side via `marked@12` CDN
  - `.docs-prose` typography styles for tables, code, blockquotes
  - `loadDocs()` JS function hooked into `switchTab('docs')`
- `valkeyry-ipaas` React (`AdminConsole.jsx`):
  - New `<TabsTrigger value="docs">` next to "Operator Copilot"
  - New `DocsPanel` component at `components/admin/DocsPanel.jsx`
  - Uses `marked@12` + `dompurify@3` for sanitised rendering
  - `.docs-prose` dark-theme styles added to `index.css`

### Security & serving
- `valkeyry-config` `SecurityConfig.java`: `/docs/**` added to the public allow-list.
- `/app/frontend/serve.js`: added `.md` MIME type (`text/markdown; charset=utf-8`).

### Verification
- All 6 markdown files reachable at `/docs/{product}/{doc}.md` (HTTP 200, ~7–11 KB each).
- React production build (`yarn build`) passes; bundle ships `build/docs/...`.
- Static HTML contains the Docs tab button, pane, switchers, and JS handler.

## Backlog / next tasks
- P2: Add Mermaid diagrams to App Flow docs once a renderer is approved.
- P2: Mirror per-service docs into central `/app/docs/` for GitHub display.
- P2: Optional sub-doc TOC/anchor list on the Docs tab right rail.
- P2: Export-to-PDF button next to the Markdown download link.
- P2: Smoke screenshot via testing agent once the screenshot tool's
  coroutine bug is fixed (currently blocking visual verification).

## Notes for next agent
- The screenshot tool currently raises
  `'coroutine' object is not subscriptable` regardless of script — verification
  was performed via curl + HTML grep + production build.
- The login page uses `admin/admin` per the on-page hint, but the FastAPI mock
  in the preview pod may not accept it — for E2E click-through tests, inject
  `localStorage['vk.auth.v1']` with `{ token, role: 'admin' }` before hitting `/`.
- The Maven reactor still works unchanged — the new docs are purely additive.
