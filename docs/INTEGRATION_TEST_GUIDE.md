# Integration Test Guide — Beginner Edition

This guide walks you (yes — even if you've never touched Spring Boot or Testcontainers) through
running the **end-to-end test** that exercises the full Valkeyry ecosystem on your laptop.

> ⏱  **Total time**: ~15 minutes the first time, ~3 minutes thereafter (Docker images cached).

---

## 0. Prerequisites

You need **three** things installed locally. Run each command — if it prints a version, you're good.

| Tool | Minimum | How to check |
|------|---------|-------------|
| Java JDK | 21 | `java -version` |
| Apache Maven | 3.9 | `mvn -v` |
| Docker (or Docker Desktop / Podman / Colima) | latest | `docker ps` |

> 💡 **macOS quick install** (Homebrew):
> ```bash
> brew install openjdk@21 maven
> brew install --cask docker  # then open Docker.app once
> ```
>
> 💡 **Linux quick install** (apt):
> ```bash
> sudo apt install openjdk-21-jdk maven docker.io
> sudo usermod -aG docker $USER && newgrp docker
> ```

Don't worry about Postgres, Valkey, or LDAP — Testcontainers will pull them automatically.

---

## 1. Get the code

```bash
cd /path/to/your/projects
# either clone or `cp -r` from your local checkout
cd valkeyry-ecosystem
ls
# parent pom + 3 module folders should appear
```

---

## 2. First-time build (offline-friendly)

```bash
mvn -DskipTests clean install
```

This compiles every module and registers the plugin-core JAR in your local `~/.m2/repository` so
that `valkeyry-config`'s test module can use it during integration tests.

> ⚠ The very first run downloads dependencies (~ 200 MB). That's normal.

---

## 3. Run the unified integration test

```bash
cd valkeyry-config
mvn -Dtest=ValkeyryConfigEcosystemIntegrationTest test
```

### What just happened?

Behind the scenes, the test framework started:

```
┌────────────────────────┐ ┌──────────────────────────┐ ┌──────────────────────┐
│ Postgres 16 (testcont) │ │ Mock-OAuth2 (in-process) │ │ Valkeyry-Config app  │
│   port: auto-assigned  │ │   issuer: /default       │ │   port: random       │
└────────────────────────┘ └──────────────────────────┘ └──────────────────────┘
                                       │
                                       ▼
                             ┌──────────────────────┐
                             │  plugin-core engine  │
                             │  (acts as build-bot) │
                             └──────────────────────┘
```

It then exercises the canonical lifecycle:

1. **Plugin push #1** — declare schema `customers`, ingest 3 records (alice, bob, carol).
   → Server stores them, returns `inserted=3, duplicates=0`.
2. **Plugin push #2** — same files, no edits. Idempotency Guard fires.
   → `inserted=0, duplicates=3`.
3. **Mutate alice's tier**, push again.
   → `inserted=1, duplicates=2`, and the server creates `version=2` for alice while keeping
   `version=1` in the audit trail.
4. **OIDC reads** — the test mints two JWTs against the in-process mock OAuth server:
   - One with `valkeyry.tenants: ["demo-tenant"]` → GET `/entries/alice` returns *platinum*,
     `version=2`. GET `/entries/alice/history` returns both versions.
   - One with `valkeyry.tenants: ["other-tenant"]` → server returns **403 Forbidden**.
5. **API-key batch ingest** — direct hit using the headless track. Returns `inserted=1`.
6. **Anonymous access** — returns **401 Unauthorized**.

If everything green-bars, you've successfully proven the ecosystem end-to-end.

---

## 4. Reading the output

A successful run ends with:

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

If anything fails, check the Surefire reports under
`valkeyry-config/target/surefire-reports/`. Each test gets its own `.txt` with the full
exception trace.

---

## 5. Common pitfalls

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Could not connect to Docker daemon` | Docker not running | Start Docker Desktop / `sudo systemctl start docker` |
| `port already in use` | Stale container leftover | `docker ps` then `docker stop <id>` |
| `OutOfMemoryError` during Postgres start | Docker memory < 2 GB | Raise Docker memory to ≥ 4 GB |
| Maven downloads forever | Slow mirror | Add a faster mirror in `~/.m2/settings.xml` |
| `JAVA_HOME` not set | Maven uses old JDK | `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS |

---

## 6. Going further

- Run the **plugin tests** alone:
  ```bash
  cd valkeyry-config-plugin/plugin-core
  mvn test
  ```
- Run the **rate-limiter** Valkey/Redis test:
  ```bash
  cd valkeyry-ipaas
  mvn -Dtest=TokenBucketRateLimiterIntegrationTest test
  ```
- Launch **`valkeyry-config` manually** for ad-hoc curl experiments:
  ```bash
  # Make sure Postgres is running on localhost:5432 with a `valkeyry_config` DB.
  cd valkeyry-config
  mvn spring-boot:run
  # Then in another terminal:
  curl -H "X-API-Key: dev-key" http://localhost:8081/api/v1/tenants/demo/tables
  ```

---

## 7. How to author your own `valkeyry-config.yaml`

```yaml
endpoint: https://valkeyry-config.acme.io    # required
tenant:   acme-prod                            # required
auth:
  type:     basic                              # or "api-key"
  username: build-bot
  password: ${VALKEYRY_BUILD_PASS}             # env-var resolution supported
tables:
  - name:    customers
    schema:  schemas/customers.schema.json
    entries: data/customers/*.json
  - name:    products
    schema:  schemas/products.schema.json
    entries: data/products/*.json
```

Run with:
```bash
# Maven
mvn io.valkeyry:valkeyry-config-maven-plugin:1.0.0-SNAPSHOT:push

# Gradle
./gradlew valkeyryConfigPush
```

That's it. Welcome to Valkeyry. 👋
