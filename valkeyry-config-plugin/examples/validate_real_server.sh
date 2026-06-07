#!/usr/bin/env bash
#
# Run the four Maven and Gradle examples against a REAL `valkeyry-config`
# Spring Boot server (not the mock). Useful as the final "smoke" before
# tagging a plugin release.
#
# Pre-requisites (one-time):
#   1. Postgres up on 127.0.0.1:5432 with a database `valkeyry_config`
#      owned by user `valkeyry`/`valkeyry`.
#   2. `mvn clean install -DskipTests` in /app/valkeyry-config-plugin
#      so the plugin artifacts land in ~/.m2.
#   3. `mvn package -DskipTests` in /app/valkeyry-config
#      so the Spring Boot fat jar exists at target/.
#
# Usage:
#   ./examples/validate_real_server.sh
#
# Override anything via env vars: JAVA_HOME, MVN_BIN, GRADLE_BIN,
# VALKEYRY_ENDPOINT, VALKEYRY_TENANT, VALKEYRY_API_KEY, PG_URL.

set -euo pipefail

JAVA_HOME=${JAVA_HOME:-/opt/jdk21}
MVN_BIN=${MVN_BIN:-/opt/apache-maven-3.9.9/bin/mvn}
GRADLE_BIN=${GRADLE_BIN:-/opt/gradle-8.10/bin/gradle}
PATH=$JAVA_HOME/bin:$PATH
export JAVA_HOME PATH

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP_JAR=$REPO_ROOT/valkeyry-config/target/valkeyry-config-1.0.0-SNAPSHOT.jar
EXAMPLES_DIR=$REPO_ROOT/valkeyry-config-plugin/examples

if [[ ! -f "$APP_JAR" ]]; then
    echo "ERROR: $APP_JAR not found. Run 'mvn package -DskipTests' in /app/valkeyry-config first." >&2
    exit 1
fi

# ── 1. Start the Spring Boot server ───────────────────────────────────
export VALKEYRY_CONFIG_PORT=${VALKEYRY_CONFIG_PORT:-8081}
export VALKEYRY_CONFIG_BIND=${VALKEYRY_CONFIG_BIND:-127.0.0.1}
export VALKEYRY_CONFIG_R2DBC_URL=${VALKEYRY_CONFIG_R2DBC_URL:-r2dbc:postgresql://127.0.0.1:5432/valkeyry_config?schema=valkeyry_config}
export VALKEYRY_CONFIG_JDBC_URL=${VALKEYRY_CONFIG_JDBC_URL:-jdbc:postgresql://127.0.0.1:5432/valkeyry_config?currentSchema=valkeyry_config}
export VALKEYRY_CONFIG_DB_USER=${VALKEYRY_CONFIG_DB_USER:-valkeyry}
export VALKEYRY_CONFIG_DB_PASS=${VALKEYRY_CONFIG_DB_PASS:-valkeyry}
export VALKEYRY_API_KEYS=${VALKEYRY_API_KEYS:-plugin-test-key:demo-tenant}

echo "[validate] booting valkeyry-config on :$VALKEYRY_CONFIG_PORT …"
"$JAVA_HOME/bin/java" -jar "$APP_JAR" > /tmp/valkeyry-config.log 2>&1 &
APP_PID=$!
cleanup() { kill "$APP_PID" 2>/dev/null || true; }
trap cleanup EXIT

# Wait until the API responds (max 60 s).
for _ in $(seq 60); do
    if curl -fsS -H "X-API-Key: plugin-test-key" \
        "http://127.0.0.1:$VALKEYRY_CONFIG_PORT/api/v1/tenants/demo-tenant/tables" \
        > /dev/null 2>&1; then
        break
    fi
    sleep 1
done

export VALKEYRY_ENDPOINT=http://127.0.0.1:$VALKEYRY_CONFIG_PORT
export VALKEYRY_TENANT=demo-tenant
export VALKEYRY_API_KEY=plugin-test-key

run() {
    local label=$1; shift
    echo ""
    echo "===== $label ====="
    if "$@" 2>&1 | grep -E "(→|push complete|· |Loaded|ERROR|FAIL|BUILD)"; then
        return ${PIPESTATUS[0]}
    fi
}

# ── 2. Maven examples ─────────────────────────────────────────────────
for d in 01-flat-feature-flags 02-inline-product-catalog 03-multi-table 04-env-driven; do
    run "MAVEN: $d" "$MVN_BIN" -B -f "$EXAMPLES_DIR/maven/$d/pom.xml" valkeyry-config:push
done

# ── 3. Gradle examples ────────────────────────────────────────────────
cd "$EXAMPLES_DIR/gradle"
for d in 01-flat-feature-flags 02-inline-product-catalog 03-multi-table 04-env-driven; do
    run "GRADLE: $d" "$GRADLE_BIN" ":$d:valkeyryConfigPush" --console=plain --no-daemon --rerun-tasks
done

# ── 4. Sanity check ───────────────────────────────────────────────────
echo ""
echo "===== Server-side data verification ====="
echo "--- tables ---"
curl -fsS -H "X-API-Key: plugin-test-key" \
    "$VALKEYRY_ENDPOINT/api/v1/tenants/demo-tenant/tables" \
    | python3 -c 'import json,sys; print([t["tableName"]+"(v"+str(t["configVersion"])+")" for t in json.load(sys.stdin)])'

echo "--- audit count ---"
curl -fsS -H "X-API-Key: plugin-test-key" \
    "$VALKEYRY_ENDPOINT/api/v1/tenants/demo-tenant/audit?limit=200" \
    > /tmp/audit.json
python3 -c '
import json
rows = json.load(open("/tmp/audit.json"))
ops = sorted({r["operation"] for r in rows})
print(f"{len(rows)} audit entries (ops: {chr(44).join(ops)})")
'

echo ""
echo "[validate] all checks passed."
