# Valkeyry Ecosystem — PRD

## Original problem statement
Fix the non-compilable Java Maven project on the `valkeyry-emergent` branch:
1. Update the directory structure so the project compiles.
2. Update the root `README.md` with appropriate `mvn` commands.

## Architecture (final)
Multi-module Maven reactor at repo root (`/app`):

```
/app/
├── pom.xml                      # reactor parent (io.valkeyry:valkeyry-ecosystem-parent:1.0.0-SNAPSHOT)
├── valkeyry-ipaas/              # Spring Boot 3 reactive messaging engine (+ React UI in frontend/)
├── valkeyry-config/             # Spring Boot 3 schema-registry engine
├── valkeyry-config-plugin/      # Aggregator
│   ├── plugin-core/             # Shared validation engine
│   ├── maven-plugin/            # Maven Mojo
│   └── gradle-plugin/           # Gradle plugin
├── deploy/                      # Helm + raw k8s manifests
├── docs/
├── README.md
├── LOCAL_SETUP.md
└── WINDOWS_GUIDE.md
```

Toolchain: JDK 21 (Temurin), Maven 3.8.7+ (3.9.x recommended), Docker for Testcontainers.

## What's been implemented
- **2026-05-28** — Promoted reactor from `/app/valkeyry-ecosystem/*` to `/app/` and deleted dummy root files (`pom.xml`, `backend/`, `frontend/`, `target/`, `yarn.lock`, `.txt`/`.bak` backups). Updated `.gitignore`.
- **2026-05-28** — Installed Temurin JDK 21 via Adoptium apt repo on the container.
- **2026-05-28** — Verified full reactor build with `mvn -B -ntp -DskipTests clean package` → BUILD SUCCESS (7 modules, ~20s).
- **2026-05-28** — README.md retained as-is per user request (already documents structure, JDK 21 / Maven prerequisites, full + per-module `mvn` commands, Docker/Helm, repo layout, links to `LOCAL_SETUP.md` and `WINDOWS_GUIDE.md`).

## Backlog
- P2 — None outstanding. User considered the task complete after README confirmation + re-verification.
