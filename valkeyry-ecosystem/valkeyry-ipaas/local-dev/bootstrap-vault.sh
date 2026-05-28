#!/usr/bin/env bash
# ============================================================================
# Bootstraps the local HashiCorp Vault developer instance:
#   - Enables KV v2 at "secret/"
#   - Injects dummy S3 storage credentials at
#       secret/data/local-tenant/project-alpha/storage
#   - Prints the static developer token for application use.
# Prerequisite: docker compose -f local-dev/docker-compose.yaml up -d vault
# ============================================================================
set -euo pipefail

VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
VAULT_TOKEN="${VAULT_TOKEN:-dev-root-token}"

curl_v () {
  curl -sS -H "X-Vault-Token: ${VAULT_TOKEN}" -H "Content-Type: application/json" "$@"
}

echo "[bootstrap-vault] Waiting for Vault @ ${VAULT_ADDR} ..."
for i in $(seq 1 30); do
  if curl -sS "${VAULT_ADDR}/v1/sys/health" >/dev/null 2>&1; then break; fi
  sleep 1
done

echo "[bootstrap-vault] Enabling kv-v2 at 'secret/' (idempotent) ..."
curl_v -X POST "${VAULT_ADDR}/v1/sys/mounts/secret" \
  -d '{"type":"kv","options":{"version":"2"}}' || true

echo "[bootstrap-vault] Writing dummy MinIO S3 credentials ..."
curl_v -X POST "${VAULT_ADDR}/v1/secret/data/local-tenant/project-alpha/storage" \
  -d '{
    "data": {
      "access_key": "minioadmin",
      "secret_key": "minioadmin",
      "endpoint":   "http://localhost:9000",
      "region":     "us-east-1"
    }
  }'

echo
echo "[bootstrap-vault] Done."
echo "    VAULT_ADDR=${VAULT_ADDR}"
echo "    VAULT_TOKEN=${VAULT_TOKEN}"
echo "    Secret path: secret/data/local-tenant/project-alpha/storage"
