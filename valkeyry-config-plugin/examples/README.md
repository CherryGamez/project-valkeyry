# valkeyry-config-plugin — Worked Examples

Each subdirectory is a **runnable** plugin manifest demonstrating one feature
of `valkeyry-config-plugin`. Copy any folder into your own project, point its
`endpoint:` at your `valkeyry-config` server, and run:

```bash
# Maven projects
mvn -f your-project/pom.xml io.valkeyry:valkeyry-config-maven-plugin:push

# Gradle projects
./gradlew :your-project:valkeyryConfigPush
```

| # | Example                                  | Demonstrates                                                                   |
|---|------------------------------------------|--------------------------------------------------------------------------------|
| 1 | [`01-flat-feature-flags`](./01-flat-feature-flags/)         | Classic style — `schema:` points at a separate `.schema.json` + `data/*.json`. |
| 2 | [`02-inline-product-catalog`](./02-inline-product-catalog/) | New `schemaInline:` — JSON-Schema declared right in YAML, including dropdown / multi-choice / numeric ranges. |
| 3 | [`03-multi-table`](./03-multi-table/)                       | One manifest declaring **multiple tables** with a mix of file + inline schemas. |
| 4 | [`04-env-driven`](./04-env-driven/)                         | Endpoint, tenant and auth credentials pulled entirely from environment variables — the CI shape. |

All examples target the **mock preview backend** (`demo-tenant`,
`X-API-Key: plugin-test-key`) by default; override with env vars to point at
a real server.
