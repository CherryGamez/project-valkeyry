# `valkeyry-config-plugin` — Worked Examples

Each numbered sub-folder is a **runnable plugin manifest** demonstrating one
feature of `valkeyry-config-plugin`. The data and schema files live next to
the YAML, and the same manifest is exercised by **both** the Maven and
Gradle wrapper projects under [`maven/`](./maven/) and
[`gradle/`](./gradle/) — so there is exactly one source of truth per example.

| # | Example                                                     | Demonstrates                                                                                                  |
|---|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| 1 | [`01-flat-feature-flags`](./01-flat-feature-flags/)         | Classic style — `schema:` points at a separate `.schema.json` + `data/*.json`.                                |
| 2 | [`02-inline-product-catalog`](./02-inline-product-catalog/) | New `schemaInline:` — JSON-Schema declared right in the YAML, including dropdown / multi-choice / numeric ranges. |
| 3 | [`03-multi-table`](./03-multi-table/)                       | One manifest declaring **multiple tables** with a mix of file and inline schemas.                             |
| 4 | [`04-env-driven`](./04-env-driven/)                         | Endpoint, tenant and API key pulled entirely from environment variables — the CI shape.                       |

All examples target the **mock preview backend** (`demo-tenant`,
`X-API-Key: plugin-test-key`, `http://localhost:8081`) by default; override
with env vars to point at a real server.

## Validated against the real server

Both the mock-backed regression suite (`test_examples.py`) **and** an end-to-end
flow against a real `valkeyry-config` Spring Boot instance + Postgres are
captured in [`validate_real_server.sh`](./validate_real_server.sh). The
script:

1. boots the fat-jar at `valkeyry-config/target/valkeyry-config-1.0.0-SNAPSHOT.jar`,
2. waits for the API to come up,
3. runs every Maven and Gradle example against it,
4. asserts the tables, schema versions and audit ledger entries appeared.

Re-run any time with:

```bash
./examples/validate_real_server.sh
```

## Runnable wrapper projects

|                                                | Build tool | Cross-platform                                       |
|------------------------------------------------|------------|------------------------------------------------------|
| [**`maven/`**](./maven/README.md)              | Maven 3.8+ | Windows / macOS / Linux — see the [Maven README](./maven/README.md).  |
| [**`gradle/`**](./gradle/README.md)            | Gradle 8.5+ | Windows / macOS / Linux — see the [Gradle README](./gradle/README.md). |

## Quick start

```bash
# 1. Install the plugin into your local Maven repo (one-time)
cd valkeyry-config-plugin
mvn clean install -DskipTests

# 2a. Run example 1 with Maven
cd examples/maven/01-flat-feature-flags
mvn valkeyry-config:push

# 2b. …or with Gradle
cd ../../gradle
gradle :01-flat-feature-flags:valkeyryConfigPush
```

## How the wrappers locate the manifest

Every wrapper points the plugin at the YAML in the sibling shared folder via
a relative path:

| Wrapper                                                       | Manifest                                              |
|---------------------------------------------------------------|-------------------------------------------------------|
| `examples/maven/01-flat-feature-flags/pom.xml`                | `../../01-flat-feature-flags/valkeyry-config.yaml`    |
| `examples/gradle/01-flat-feature-flags/build.gradle`          | `../../01-flat-feature-flags/valkeyry-config.yaml`    |

Schemas (`schema:`) and entry globs (`entries:`) inside the YAML are
resolved relative to the **manifest's own directory** — so
`schemas/feature_flags.schema.json` resolves to
`examples/01-flat-feature-flags/schemas/feature_flags.schema.json` regardless
of where you launch the build from.
