# Audit Console (web UI)

A thin, zero-build React 18 page that consumes the audit endpoint.
Served by `valkeyry-config` itself under `/audit/` — no Node toolchain.

```
http://localhost:8081/audit/
```

## Visual language — iOS-inspired (solid, not glassmorphism)

| Token | Value | Notes |
|-------|-------|-------|
| Surface | `#F2F2F7` grouped background, `#FFFFFF` cards | iOS grouped-table style |
| Primary | `#007AFF` (System Blue) | All CTAs, links, accents |
| Indigo  | `#5856D6` | Tenant/OIDC pill |
| Pink    | `#FF2D55` | Destructive |
| Red     | `#FF3B30` | Errors, removed lines |
| Orange  | `#FF9500` | Loading, warning |
| Mint/Green | `#00C7BE` / `#34C759` | Success, added lines, "on" switches |
| Teal    | `#5AC8FA` | Info |
| Typography | SF Pro Display / SF Pro Text + JetBrains Mono fallback | `-0.011em` letter-spacing |
| Radii  | `14px` standard, `20px` cards, `28px` hero | Generous iOS rounding |
| Shadows | Two-layer soft `0 1px 2px + 0 6px 18px` | **Not** glass; solid + shadow |
| Motion | Spring `cubic-bezier(.34,1.56,.64,1)` on hover lift, `0.97` scale on press | iOS spring feel |

Distinctive elements:

- **Segmented control** for OIDC/API-Key auth mode (iOS pill).
- **iOS switch** (sliding white knob) for auto-refresh toggle.
- **Status dot** in the header (orange pulsing while syncing, green when synced).
- **Operation badges** color-coded per audit operation (Declared/Revised/Ingested/Deduped).
- **Track badges** uppercase tiny pills for OIDC / LDAP / API_KEY / ANONYMOUS.
- **Diff lines** with subtle 8%-alpha tinted backgrounds (green for adds, red for removes).
- **Spring lift** on event rows (1px hover offset + shadow upgrade).

No purple-on-white gradient slop. No glass blur. Solid surfaces, vibrant accents, light layered shadows, generous spacing.

## Features

- Tenant + auth picker (OIDC Bearer / X-API-Key, persisted to `localStorage`).
- Filter bar: `tableName`, `recordKey`, `actor`, `limit` (1–500).
- Auto-refresh: optional 5-second polling (iOS switch).
- Color-coded per-event row with timestamp + actor + track.
- Expanded row: side-by-side pretty before/after JSON + unified key-by-key diff.

## Files

```
valkeyry-config/src/main/resources/static/
├── index.html             ← / → redirects to /audit/
└── audit/
    └── index.html          ← single-file React 18 app (Babel standalone, Tailwind CDN)
```

## Local run

```bash
cd valkeyry-config
mvn spring-boot:run
# open http://localhost:8081/audit/
```

Every interactive element carries a unique `data-testid` (`audit-tenant-input`,
`audit-connect-btn`, `audit-event-toggle-<id>`, `audit-autorefresh-toggle`, …) for
Playwright/Cypress instrumentation.
