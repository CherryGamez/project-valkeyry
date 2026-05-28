# Valkeyry Ecosystem

A multi-module Maven reactor that ships three independently deployable products built on a shared schema-validation core:

| Module | Maven coordinates | Purpose |
| --- | --- | --- |
| [`valkeyry-ipaas/`](valkeyry-ipaas) | `io.valkeyry:valkeyry-ipaas` | Reactive iPaaS / messaging engine (Spring Boot 3, WebFlux, Kafka, RabbitMQ, ActiveMQ, Valkey, Postgres) |
| [`valkeyry-config/`](valkeyry-config) | `io.valkeyry:valkeyry-config` | Headless JSON-Schema registry engine (Spring Boot 3, Postgres, Valkey) |
| [`valkeyry-config-plugin/`](valkeyry-config-plugin) | `io.valkeyry:valkeyry-config-plugin-parent` | Build-time schema validators (aggregator) — see modules below |
| &nbsp;&nbsp;&nbsp;&nbsp;`plugin-core/` | `io.valkeyry:valkeyry-config-plugin-core` | Shared validation engine consumed by Maven/Gradle plugins and the registry |
| &nbsp;&nbsp;&nbsp;&nbsp;`maven-plugin/` | `io.valkeyry:valkeyry-config-maven-plugin` | Maven Mojo that validates configs at `compile` / `verify` |
| &nbsp;&nbsp;&nbsp;&nbsp;`gradle-plugin/` | `io.valkeyry:valkeyry-config-gradle-plugin` | Gradle plugin counterpart |

The reactor parent is `io.valkeyry:valkeyry-ecosystem-parent:1.0.0-SNAPSHOT` (see [`pom.xml`](pom.xml)).

---

## 1. Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| **JDK** | **21** (LTS) | `<release>21</release>` is enforced in the parent POM. Adoptium Temurin recommended. |
| **Maven** | **3.8.7+** (3.9.x recommended) | Verified with Maven 3.8.7. |
| **Docker** | 24+ | Required for Testcontainers-based integration tests *and* the local-dev compose stack. |
| **Node.js / Yarn** | Node 20+, Yarn 1.22+ | Only needed if you work on the React admin UI in `valkeyry-ipaas/frontend/`. |

Verify your toolchain:

```bash
java -version    # should report 21.x
mvn -version     # Maven 3.8.7+ running on Java 21
docker info      # daemon reachable
```

### Character encoding

The entire stack is UTF-8 end-to-end: source files (`project.build.sourceEncoding=UTF-8`), HTTP (Spring WebFlux + Jackson default to UTF-8), and static assets. To ensure German umlauts (`ä ö ü ß ÄÖÜ ẞ`) and other Unicode round-trip correctly through the database, the Postgres instance **must** be initialised with UTF-8. If you provision the database manually:

```sql
CREATE DATABASE ipaas
  WITH ENCODING 'UTF8'
       LC_COLLATE = 'en_US.UTF-8'
       LC_CTYPE   = 'en_US.UTF-8'
       TEMPLATE   = template0;

CREATE DATABASE valkeyry_config
  WITH ENCODING 'UTF8'
       LC_COLLATE = 'en_US.UTF-8'
       LC_CTYPE   = 'en_US.UTF-8'
       TEMPLATE   = template0;
```

The official `postgres:16` Docker image and the local-dev compose stack already use UTF-8 by default, so no extra configuration is needed there.

---

## 2. Build the entire reactor

All commands below are run from the **repo root** (where this README and the reactor `pom.xml` live).

### Quick compile (skip tests, fastest feedback)

```bash
mvn -B -ntp -DskipTests clean install
```

This is the canonical "does the project compile?" command. It:

- Cleans every module's `target/`
- Compiles all 7 reactor modules in dependency order
- Repackages the two Spring Boot fat-jars (`valkeyry-ipaas`, `valkeyry-config`)
- Installs every artifact into your local Maven repo (`~/.m2/repository`) so the Maven/Gradle plugins are usable from other projects

Expected reactor order:

```
[INFO] Valkeyry Ecosystem (Reactor Parent) ................ SUCCESS
[INFO] Valkeyry Config Plugin (Aggregator) ................ SUCCESS
[INFO] Valkeyry Config Plugin :: Core ..................... SUCCESS
[INFO] Valkeyry Config :: Maven Plugin .................... SUCCESS
[INFO] Valkeyry Config :: Gradle Plugin ................... SUCCESS
[INFO] Valkeyry iPaaS (Reactive Messaging Engine) ......... SUCCESS
[INFO] Valkeyry Config (Headless Schema Registry Engine) .. SUCCESS
[INFO] BUILD SUCCESS
```

### Full verify (runs unit + integration tests)

```bash
mvn -B -ntp clean verify
```

> Integration tests use **Testcontainers**, so a working Docker daemon must be reachable on the same machine.

### Package only (no install to local repo)

```bash
mvn -B -ntp -DskipTests clean package
```

### Run static analysis / formatter checks (if configured per-module)

```bash
mvn -B -ntp -DskipTests verify -P quality
```

---

## 3. Per-module Maven commands

### 3.1 Build a single module **and its dependencies** (`-am` = also-make)

```bash
mvn -B -ntp -pl valkeyry-ipaas -am -DskipTests clean install
mvn -B -ntp -pl valkeyry-config -am -DskipTests clean install
mvn -B -ntp -pl valkeyry-config-plugin/plugin-core -am -DskipTests clean install
mvn -B -ntp -pl valkeyry-config-plugin/maven-plugin -am -DskipTests clean install
mvn -B -ntp -pl valkeyry-config-plugin/gradle-plugin -am -DskipTests clean install
```

### 3.2 Run a Spring Boot module locally

```bash
# iPaaS messaging engine (default port 8081)
mvn -pl valkeyry-ipaas spring-boot:run

# Schema registry engine (default port 8080)
mvn -pl valkeyry-config spring-boot:run
```

Boot the local infrastructure (Postgres, Valkey, RabbitMQ, Kafka, ActiveMQ, MinIO, Vault) first:

```bash
docker compose -f valkeyry-ipaas/local-dev/docker-compose.yaml up -d
```

### 3.3 Tests

```bash
# Unit tests only (no Docker required)
mvn -pl valkeyry-ipaas -am test

# Integration tests (Testcontainers; Docker required)
mvn -pl valkeyry-ipaas -am verify

# A single test class
mvn -pl valkeyry-ipaas test -Dtest=YourTestClassName

# A single test method
mvn -pl valkeyry-ipaas test -Dtest=YourTestClassName#methodName
```

### 3.4 Run the executable Spring Boot fat-jars directly

```bash
java -jar valkeyry-ipaas/target/valkeyry-ipaas-1.0.0-SNAPSHOT.jar
java -jar valkeyry-config/target/valkeyry-config-1.0.0-SNAPSHOT.jar
```

### 3.5 Consume the Maven plugin from another project

After `mvn install` at the repo root the plugin is in your local repo:

```xml
<plugin>
  <groupId>io.valkeyry</groupId>
  <artifactId>valkeyry-config-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>validate</goal></goals>
    </execution>
  </executions>
</plugin>
```

---

## 4. Frontend (React admin console)

Located in `valkeyry-ipaas/frontend/` and managed with **Yarn**:

```bash
cd valkeyry-ipaas/frontend
yarn install
yarn start         # dev server on http://localhost:3000
yarn build         # production bundle in build/
yarn test          # CRA / Jest tests
```

The dev server proxies API calls to the iPaaS backend; ensure `valkeyry-ipaas` is running on port 8081 first (or override `REACT_APP_BACKEND_URL` in `valkeyry-ipaas/frontend/.env`).

---

## 5. Containers & deployment

### Build Docker images per module

```bash
# iPaaS
docker build -t valkeyry/ipaas:dev -f valkeyry-ipaas/Dockerfile valkeyry-ipaas

# Schema registry
docker build -t valkeyry/config:dev -f valkeyry-config/Dockerfile valkeyry-config
```

> Each `Dockerfile` is a multi-stage build that runs `mvn -DskipTests package` internally — you do **not** need to run Maven on the host first.

### Helm charts & raw Kubernetes manifests

Both products ship their own deployment artifacts under [`deploy/`](deploy):

```
deploy/
├── helm/
│   ├── valkeyry-ipaas/        # Helm chart for the iPaaS engine
│   └── valkeyry-config/       # Helm chart for the schema registry
└── k8s/                       # Raw manifests for quick demos
```

See [`deploy/README.md`](deploy/README.md) for installation, values, and upgrade flow.

---

## 6. Common errors & fixes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Fatal error compiling: invalid target release: 21` | You are running Maven on JDK ≤ 17 | Install JDK 21 and `export JAVA_HOME=/path/to/jdk-21` |
| `Could not find artifact io.valkeyry:valkeyry-config-plugin-core` | You ran a leaf module without `-am`, or skipped the reactor install | Run `mvn -DskipTests install` from the repo root once, or always pass `-am` |
| Testcontainers test fails with `Could not find a valid Docker environment` | Docker daemon not running / not reachable | `docker info`; on macOS/Windows start Docker Desktop; on Linux ensure your user is in the `docker` group |
| `spring-boot:run` exits with `Web server failed to start. Port 8081 was already in use` | Stale instance still running, or compose stack mapped the same port | `lsof -i :8081`, stop the process, or override `--server.port=...` |
| `mvn clean` deletes Spring Boot fat-jars that you wanted to ship | Expected — re-run `mvn -DskipTests package` (or `install`) | — |
| Gradle plugin module fails on Maven 3.9.x with "incompatible plugin metadata" | Mismatched `maven-plugin-plugin` cache | `rm -rf ~/.m2/repository/io/valkeyry` and rebuild |

---

## 7. Repository layout

```
project-valkeyry/                  ← repo root (this README)
├── pom.xml                        ← reactor parent
├── valkeyry-ipaas/                ← Spring Boot messaging engine + React UI
│   ├── pom.xml
│   ├── Dockerfile
│   ├── src/
│   ├── frontend/                  ← React admin console (Yarn)
│   ├── local-dev/                 ← docker-compose for Postgres/Kafka/etc.
│   └── load-tests/
├── valkeyry-config/               ← Spring Boot schema registry engine
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── valkeyry-config-plugin/        ← Aggregator (Maven/Gradle build-time validators)
│   ├── pom.xml                    ← aggregator only
│   ├── plugin-core/
│   ├── maven-plugin/
│   └── gradle-plugin/
├── deploy/
│   ├── helm/{valkeyry-ipaas,valkeyry-config}/
│   └── k8s/
├── docs/                          ← architecture, audit log, integration test guide
├── LOCAL_SETUP.md                 ← Linux/macOS local-dev quickstart
├── WINDOWS_GUIDE.md               ← Windows-specific notes
└── README.md                      ← you are here
```

---

## 8. Further reading

- Architecture overview — [`docs/architecture.md`](docs/architecture.md)
- Integration test guide — [`docs/INTEGRATION_TEST_GUIDE.md`](docs/INTEGRATION_TEST_GUIDE.md)
- API examples — [`docs/api-examples/`](docs/api-examples)
- Restructure log — [`docs/RESTRUCTURE.md`](docs/RESTRUCTURE.md)
- Audit log — [`docs/AUDIT_LOG.md`](docs/AUDIT_LOG.md)
- Module-specific READMEs:
  - [`valkeyry-ipaas/README.md`](valkeyry-ipaas/README.md)
  - [`valkeyry-config/README.md`](valkeyry-config/README.md)
  - [`valkeyry-config-plugin/README.md`](valkeyry-config-plugin/README.md)

---

## 9. License

Apache License 2.0.
