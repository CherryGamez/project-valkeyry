# Example 4 — Env-driven (CI shape)

Same as Example 2 but with every credential pulled from the environment.
The `${VAR:default}` syntax supplies the local-dev default; production CI
overrides via real env vars.

```bash
# CI usage
export VALKEYRY_ENDPOINT=https://valkeyry-config.acme.io
export VALKEYRY_TENANT=acme-prod
export VALKEYRY_API_KEY="$(vault kv get -field=key secret/valkeyry/ci)"

mvn io.valkeyry:valkeyry-config-maven-plugin:push
```

The plugin halts before contacting the server if any required variable is
missing, so a misconfigured pipeline fails fast.
