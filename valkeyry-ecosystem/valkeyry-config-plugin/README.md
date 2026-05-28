# `valkeyry-config-plugin` — Build-tool Automation

Three sub-modules:

| Artifact | Purpose |
|----------|---------|
| `valkeyry-config-plugin-core` | Spring-free orchestration: manifest parsing, hashing, JDK HttpClient |
| `valkeyry-config-maven-plugin` | Maven Mojo with `push` goal (binds to `deploy` phase) |
| `valkeyry-config-gradle-plugin` | Gradle Plugin with `valkeyryConfigPush` task |

Both wrappers delegate to the same `PluginEngine.run(PluginContext)` so behavior stays in lock-step.

## Manifest (`valkeyry-config.yaml`)

```yaml
endpoint: https://valkeyry-config.acme.io
tenant:   acme-prod
auth:
  type:     basic                # or "api-key"
  username: build-bot
  password: ${VALKEYRY_PASS}     # env-vars supported, ${VAR:default} too
tables:
  - name:    customers
    schema:  schemas/customers.schema.json
    entries: data/customers/*.json
```

For `api-key` auth use:
```yaml
auth:
  type:   api-key
  apiKey: ${VALKEYRY_BUILD_KEY}
```

`recordKey` is taken from the top-level `id` field of each entry JSON; if absent it falls back
to the file base-name (without extension). The schema file is uploaded verbatim — JSON Schema
Draft 2020-12 is assumed.

## Maven usage

```xml
<plugin>
  <groupId>io.valkeyry</groupId>
  <artifactId>valkeyry-config-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>push</goal></goals>
    </execution>
  </executions>
</plugin>
```

Run explicitly:
```bash
mvn io.valkeyry:valkeyry-config-maven-plugin:1.0.0-SNAPSHOT:push -Dvalkeyry.manifest=valkeyry-config.yaml
```

## Gradle usage

```kotlin
plugins {
  id("io.valkeyry.config") version "1.0.0-SNAPSHOT"
}

valkeyryConfig {
  manifestPath.set(file("valkeyry-config.yaml"))
}
```

Run:
```bash
./gradlew valkeyryConfigPush
```

## How idempotency works
1. Plugin canonicalises each entry JSON (sorted keys, no whitespace).
2. Computes SHA-256 → "fingerprint".
3. Server recomputes using **the same algorithm** and looks up
   `(tenant, table, recordKey, payloadHash)` in `virtual_table_entry`.
4. If a row with `is_latest=true` matches, the request is reported as a *duplicate* (no insert).

This guarantees that re-running the build any number of times never grows the database when
inputs are unchanged.
