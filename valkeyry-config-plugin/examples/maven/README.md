# Runnable Maven examples

Each sub-folder under here is a **self-contained Maven project** that applies
the `valkeyry-config-maven-plugin` to one of the canonical manifests under
[`../`](../) (`01-flat-feature-flags`, `02-inline-product-catalog`,
`03-multi-table`, `04-env-driven`).

The data and schema files live with the YAML in the parent shared folder so
the same manifest is exercised by both the Maven and the Gradle examples —
no duplication.

## Prerequisites (Windows / macOS / Linux)

| Tool      | Version | Install hint                                                                  |
|-----------|---------|-------------------------------------------------------------------------------|
| **JDK**   | 21+     | `winget install EclipseAdoptium.Temurin.21.JDK` (Win) · `brew install --cask temurin@21` (mac) · `apt install openjdk-21-jdk` (Linux) |
| **Maven** | 3.8+    | `winget install Apache.Maven` · `brew install maven` · `apt install maven`    |

Verify:

```bash
java -version    # must print 21.x
mvn -v
```

## One-time setup — build the plugin into your local repo

```bash
cd /path/to/valkeyry-config-plugin
mvn clean install -DskipTests
```

This installs three artifacts into `~/.m2/repository/io/valkeyry/`:

- `valkeyry-config-plugin-core`
- `valkeyry-config-maven-plugin`
- `valkeyry-config-gradle-plugin`

## Run an example

The mock preview backend is built into the YAML defaults (`demo-tenant`,
`X-API-Key: plugin-test-key`, `http://localhost:8081`). To use a real
`valkeyry-config` server, export the env vars or use `-D…` system
properties.

```bash
# macOS / Linux
cd valkeyry-config-plugin/examples/maven/01-flat-feature-flags
mvn valkeyry-config:push

# Windows PowerShell — identical command
cd valkeyry-config-plugin\examples\maven\01-flat-feature-flags
mvn valkeyry-config:push
```

Expected output for example 1:

```
[INFO] · feature_flags: declaring schema…
[INFO]   → registered id=<uuid> configVersion=1
[INFO]   → submitted=3 inserted=3 duplicates=0
[INFO] valkeyry-config push complete — submitted=3 inserted=3 duplicates=0
```

Run them all from the aggregator:

```bash
cd valkeyry-config-plugin/examples/maven
mvn -N validate                # sanity-check the aggregator
# Then per-module:
mvn -f 01-flat-feature-flags/pom.xml     valkeyry-config:push
mvn -f 02-inline-product-catalog/pom.xml valkeyry-config:push
mvn -f 03-multi-table/pom.xml            valkeyry-config:push
mvn -f 04-env-driven/pom.xml             valkeyry-config:push
```

## Overriding the backend

Every YAML uses `${VAR:default}` placeholders. Override at the shell level:

```bash
# macOS / Linux
export VALKEYRY_ENDPOINT=https://valkeyry-config.acme.io
export VALKEYRY_TENANT=acme-prod
export VALKEYRY_API_KEY=$(vault kv get -field=key secret/valkeyry/ci)
mvn valkeyry-config:push

# Windows PowerShell
$env:VALKEYRY_ENDPOINT = 'https://valkeyry-config.acme.io'
$env:VALKEYRY_TENANT   = 'acme-prod'
$env:VALKEYRY_API_KEY  = 'replace-me'
mvn valkeyry-config:push
```

Or point the plugin at a different manifest entirely:

```bash
mvn valkeyry-config:push -Dvalkeyry.manifest=../../03-multi-table/valkeyry-config.yaml
```

## Skipping the push

```bash
mvn valkeyry-config:push -Dvalkeyry.skip=true
```

## Troubleshooting

| Symptom                                                    | Cause / fix                                                                  |
|------------------------------------------------------------|------------------------------------------------------------------------------|
| `Could not find artifact io.valkeyry:valkeyry-config-…`    | Did you run `mvn clean install` in the plugin root? It must populate `~/.m2`. |
| `Declare failed 401`                                       | API key or tenant mismatch — re-check the env vars.                          |
| `Connection refused / NoRouteToHost`                       | Endpoint unreachable; the default mock URL is offline-only.                  |
| `Empty manifest` / `Missing 'endpoint'`                    | YAML path wrong or env-var didn't resolve; run with `-X` for the loader log. |
