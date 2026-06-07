# Runnable Gradle examples

This folder is a **Gradle multi-project build** with one subproject per
canonical manifest under [`../`](../). Each subproject applies the
`io.valkeyry.config` plugin and points it at the YAML in the sibling
shared folder — so the same manifest is exercised by both the Maven and
Gradle examples.

## Prerequisites (Windows / macOS / Linux)

| Tool       | Version | Install hint                                                                  |
|------------|---------|-------------------------------------------------------------------------------|
| **JDK**    | 21+     | `winget install EclipseAdoptium.Temurin.21.JDK` (Win) · `brew install --cask temurin@21` (mac) · `apt install openjdk-21-jdk` (Linux) |
| **Gradle** | 8.5+    | `winget install Gradle.Gradle` · `brew install gradle` · `sdk install gradle 8.10` |

Verify:

```bash
java -version    # must print 21.x
gradle -v
```

## One-time setup — install the plugin into your local Maven repo

The Gradle plugin and its `plugin-core` runtime are resolved from
`mavenLocal()`. Build and install the parent project once:

```bash
cd /path/to/valkeyry-config-plugin
mvn clean install -DskipTests
```

## Run a single example

```bash
# macOS / Linux
cd valkeyry-config-plugin/examples/gradle
gradle :01-flat-feature-flags:valkeyryConfigPush --info

# Windows PowerShell — identical command
cd valkeyry-config-plugin\examples\gradle
gradle :01-flat-feature-flags:valkeyryConfigPush --info
```

Expected output:

```
> Task :01-flat-feature-flags:valkeyryConfigPush
Loaded manifest with 1 table(s) → http://localhost:8081
· feature_flags: declaring schema…
  → registered id=<uuid> configVersion=1
  → submitted=3 inserted=3 duplicates=0
valkeyry-config push complete — submitted=3 inserted=3 duplicates=0
```

## Run every example in one shot

```bash
gradle valkeyryConfigPush
```

## Overriding the backend

The YAML resolves `${VAR:default}`, so just export real values:

```bash
# macOS / Linux
export VALKEYRY_ENDPOINT=https://valkeyry-config.acme.io
export VALKEYRY_TENANT=acme-prod
export VALKEYRY_API_KEY=$(vault kv get -field=key secret/valkeyry/ci)
gradle :04-env-driven:valkeyryConfigPush

# Windows PowerShell
$env:VALKEYRY_ENDPOINT = 'https://valkeyry-config.acme.io'
$env:VALKEYRY_TENANT   = 'acme-prod'
$env:VALKEYRY_API_KEY  = 'replace-me'
gradle :04-env-driven:valkeyryConfigPush
```

## Skipping the push

```bash
gradle :01-flat-feature-flags:valkeyryConfigPush -PvalkeyrySkip=true
```

(or programmatically: set `valkeyryConfig { skip = true }` in the subproject's
`build.gradle`).

## Troubleshooting

| Symptom                                                | Cause / fix                                                                       |
|--------------------------------------------------------|-----------------------------------------------------------------------------------|
| `Could not resolve io.valkeyry:valkeyry-config-…`      | Run `mvn clean install` in the plugin root so the jars land in `~/.m2`.           |
| `Plugin [id: 'io.valkeyry.config'] was not found`      | Same — the descriptor is inside the gradle-plugin jar in `~/.m2`.                 |
| `manifestPath … does not exist`                        | Wrong relative path. From `examples/gradle/<n>/`, the YAML lives at `../../<n>/`. |
| `Connection refused`                                   | Default endpoint is the mock URL `http://localhost:8081`; export real env vars.   |
