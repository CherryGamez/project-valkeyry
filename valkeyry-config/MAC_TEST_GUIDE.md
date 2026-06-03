# valkeyry-config — macOS Local Test Guide

> End-to-end walkthrough for running **valkeyry-config** and
> **valkeyry-config-plugin** on macOS (Intel or Apple Silicon). The guide
> covers every authentication track the server supports:
>
> | Track                     | Use case                       | Section |
> |---------------------------|--------------------------------|---------|
> | Anonymous / API-Key       | Quick smoke + plugin push      | §3, §4  |
> | LDAP Basic                | Headless build agents (CI)     | §6      |
> | OIDC JWT (Bearer token)   | Human SSO + tenant scoping     | §7      |
>
> All commands assume **zsh** (the default macOS shell). The sister guide
> for Windows lives at [`WINDOWS_TEST_GUIDE.md`](WINDOWS_TEST_GUIDE.md).

---

## 0. TL;DR — happy path in 4 commands

```bash
cd valkeyry-config
docker compose -f docker-compose.dev.yml up -d postgres        # boot Postgres
mvn -pl valkeyry-config -am -DskipTests package                # build the jar
docker compose -f docker-compose.dev.yml up -d app             # boot the app
open http://localhost:8081/                                    # open the UI
```

UI: **http://localhost:8081/**. Anonymous reads work; writes require an
API key, an LDAP user, or a JWT (see §3.2).

---

## 1. Prerequisites

| Tool               | Version | Install (Homebrew)                                          |
|--------------------|---------|-------------------------------------------------------------|
| **Docker Desktop** | 4.30+   | `brew install --cask docker` (or Colima — see §1.1)          |
| **Java JDK**       | 21      | `brew install --cask temurin@21`                            |
| **Maven**          | 3.9+    | `brew install maven`                                        |
| **Git**            | latest  | preinstalled (`xcode-select --install` if missing)          |
| **jq**             | latest  | `brew install jq`                                           |
| **ldap-utils**     | latest  | `brew install openldap` *(optional, only for `ldapsearch`)* |

Verify:

```bash
docker --version              # >= 27.x
java -version                 # openjdk 21.x
mvn -version                  # JDK must be 21
```

> Give Docker Desktop **≥ 8 GB RAM** in *Settings → Resources*. The four
> sidecars (Postgres, OpenLDAP, mock-oauth2, app) need ~4 GB combined; the
> rest is build/test headroom.

### 1.1 Apple Silicon notes

Both Bitnami's OpenLDAP image and `navikt/mock-oauth2-server` ship `arm64`
manifests, so nothing extra is needed on M1/M2/M3 Macs. If you're on **Colima**
instead of Docker Desktop, start it with at least 4 CPUs / 8 GB:

```bash
colima start --cpu 4 --memory 8 --disk 60
```

---

## 2. Clone & first boot

```bash
git clone https://github.com/your-org/valkeyry.git
cd valkeyry/valkeyry-config
docker compose -f docker-compose.dev.yml up -d postgres
docker compose -f docker-compose.dev.yml ps                    # postgres → healthy
```

Two ways to run the Spring Boot app:

**A. From your IDE** (fastest while iterating)
1. Open `valkeyry-config/pom.xml` in IntelliJ / VS Code.
2. Run → `ValkeyryConfigApplication`.
3. The app reads `application.yml` directly. LDAP/OIDC are configured but
   not required — the filters short-circuit when no `Authorization` header
   arrives.

**B. All-in-Docker** (closer to prod)

```bash
mvn -pl valkeyry-config -am -DskipTests package
docker compose -f docker-compose.dev.yml up -d app
docker compose -f docker-compose.dev.yml logs -f app
```

Either way the app is now on **http://localhost:8081/**.

---

## 3. Smoke tests

### 3.1 Health endpoint

```bash
curl -s http://localhost:8081/actuator/health | jq
# → {"status":"UP",...}
```

### 3.1.1 OpenAPI / Swagger UI

The app exposes a Swagger console + the raw OpenAPI 3 contract — both
public, no auth header required:

| URL                                       | What it gives you                  |
|-------------------------------------------|------------------------------------|
| `http://localhost:8081/swagger-ui.html`   | Interactive console (with *Authorize*: paste your API key or JWT) |
| `http://localhost:8081/v3/api-docs`       | OpenAPI 3 contract (JSON)          |
| `http://localhost:8081/v3/api-docs.yaml`  | OpenAPI 3 contract (YAML)          |

You can also reach the same URLs (and every per-tenant endpoint) from the
**Endpoints & URLs** tab in the UI — each row has a **Copy** button and,
for safe GETs, a **Try** button that fires the request and shows the JSON
response *inline* on that page.

### 3.2 Auth tracks at a glance

| Track       | Header                                       | Tenant resolution                |
|-------------|----------------------------------------------|----------------------------------|
| Anonymous   | none                                         | Only reads on permit-all paths   |
| API-key     | `X-API-Key: <key>`                           | Key → tenant from `VALKEYRY_API_KEYS` |
| LDAP Basic  | `Authorization: Basic base64(user:pass)`     | Tenant from user's `ou` attribute |
| OIDC JWT    | `Authorization: Bearer <jwt>`                | Tenant from `tenants` claim       |
| **Local form-login** | `Authorization: Bearer <hs256-jwt>` | Tenant list comes from `app_user_tenant` |

### 3.3 Push a schema with the bundled API key

`docker-compose.dev.yml` wires `VALKEYRY_API_KEYS=plugin-test-key:demo-tenant`,
so the simplest write path is:

```bash
API="http://localhost:8081/api/v1/tenants/demo-tenant"

curl -X POST "$API/tables" \
  -H "X-API-Key: plugin-test-key" \
  -H "Content-Type: application/json" \
  -d '{
    "tableName": "customers",
    "schema": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["email"],
      "properties": {
        "email":  { "type": "string", "format": "email" },
        "active": { "type": "boolean", "default": true },
        "role":   { "type": "string", "enum": ["viewer","editor","admin"] }
      }
    }
  }' | jq

curl -X POST "$API/tables/customers/entries" \
  -H "X-API-Key: plugin-test-key" \
  -H "Content-Type: application/json" \
  -d '{"recordKey":"u-1","data":{"email":"jane@acme.io","active":true,"role":"editor"}}' | jq
```

In the UI, pick `demo-tenant` from the sidebar, click `customers`, and the
row appears.

---

## 3.4 Form-login + Admin panel + Tools tab (2026-02 update)

Starting with the **2026-02-15** release the registry exposes a full
sign-in flow with three new endpoints (powered by `AuthController` /
`AdminController` / `ToolsController`):

| URL                          | Purpose                                         |
|------------------------------|-------------------------------------------------|
| `/login.html`                | WebSSO *and* username/password form             |
| `/admin.html`                | Tenants + users CRUD (Camunda-Identity style)   |
| `/tools.html`                | XLSX / CSV / DMN → JSON converter               |

### Built-in admin (works without LDAP/SSO)

The container ships with **`admin` / `admin`** as the bypass credential.
Override via env vars before starting `docker compose`:

```bash
export VALKEYRY_ADMIN_USERNAME=admin
export VALKEYRY_ADMIN_PASSWORD=change-me-please
export VALKEYRY_JWT_SECRET="a-32-byte-or-longer-random-string!!!"
```

Then sign in at `http://localhost:8081/login.html`:

1. Type `admin` / `admin` → press *Login (username/pwd)*
2. You're redirected to `/admin.html` because the user has the `admin` role.
3. Create a tenant (e.g. `acme`) and a writer user under that tenant.

### Toggling SSO and LDAP

Both auth tracks are now **independent env-var flags** so dev runs don't
fail when those sidecars are off:

```bash
export VALKEYRY_SSO_ENABLED=false      # default off
export VALKEYRY_LDAP_ENABLED=false     # default off
# When SSO is on, also set:
export VALKEYRY_OIDC_ISSUER=http://localhost:8079/default
```

### Tools tab — bulk import via XLSX/CSV/DMN

1. Open `http://localhost:8081/tools.html`.
2. Drop a `.xlsx`, `.csv`, or `.dmn` file in the dropzone.
3. Hit **Convert** — the server returns a JSON shape (one entry in
   `tables[]` per worksheet / DMN decision table):

   ```json
   {
     "source":   "xlsx",
     "fileName": "customers.xlsx",
     "tables":   [{ "tableName": "Sheet1", "columns": [...], "rows": [...] }]
   }
   ```

4. Each `tables[].rows[*]` object is already the `data` payload of
   `POST /api/v1/tenants/{tenant}/tables/{table}/entries:batch`.

### JWT "Missing dot delimiter(s)" — fixed

A `SafeBearerTokenAuthenticationConverter` now sits in front of the OIDC
pipeline, so passing a non-JWT bearer (an old API key in the `Authorization`
header, for instance) no longer triggers
`InvalidBearerTokenException: Missing dot delimiter(s)` — the request just
falls through to the API-key / LDAP filters and either succeeds or returns
a clean 401.

---

## 4. UI tour — flexible config types (dropdown / checkbox / multi-choice)

The create-table modal now ships **two editor modes**, selectable in the modal
itself:

- **Visual Builder** — point-and-click form. Click `+ Add field`, pick a
  widget (Text / Email / URL / Date / Number / Integer / Checkbox / Dropdown /
  Multi-choice), set the label, options, default, required flag. The
  JSON-Schema is regenerated live and pushed verbatim on *Declare*.
- **Raw JSON** — the original textarea, still there for advanced cases
  (`oneOf`, nested objects, `$ref`, …).

The **Schema Gallery** chips at the top of the modal seed both modes with the
same templates. Flip between them freely without losing state.

Walk through the widget set:

1. Click `+ Declare table` → name it `users`.
2. Click the **User profile** gallery chip — the builder fills in five rows
   (`email`, `role` dropdown, `active` checkbox, `tags` multi-choice, `joinedAt` date).
3. Hit **Declare**.
4. In the table view, click `+ Ingest entry`. The right-hand form is rendered
   from the schema, so you'll see a `<select>` for `role`, a checkbox for
   `active`, and a checkbox grid for `tags`. Submit a row, refresh — it's
   stored as proper booleans + arrays in JSONB.

---

## 5. Plugin push (`valkeyry-config-plugin`)

The plugin supports two schema styles:

### 5.1 File-based (`schema:`)

```yaml
# manifests/valkeyry-config.yaml
endpoint: http://localhost:8081
tenant:   demo-tenant
auth:
  type:   api-key
  apiKey: ${VALKEYRY_API_KEY:plugin-test-key}
tables:
  - name:    customers
    schema:  schemas/customers.schema.json
    entries: data/customers/*.json
```

### 5.2 Inline (`schemaInline:` — declared right in YAML)

**New** — no separate `.schema.json` file needed. All UI widget types are
expressible in YAML:

```yaml
endpoint: http://localhost:8081
tenant:   demo-tenant
auth:
  type:   api-key
  apiKey: plugin-test-key
tables:
  - name: feature_flags
    schemaInline:
      $schema: "https://json-schema.org/draft/2020-12/schema"
      type:    object
      required: [key, enabled]
      properties:
        key:     { type: string,  title: "Flag key", pattern: "^[a-z0-9_.-]+$" }
        enabled: { type: boolean, title: "Enabled",  default: false }
        rolloutPercent:
          type: integer
          title: "Rollout %"
          minimum: 0
          maximum: 100
          default: 0
        audiences:
          type: array
          title: "Audiences (multi-choice)"
          uniqueItems: true
          items:
            type: string
            enum: [internal, beta, ga, enterprise]
      additionalProperties: false
    entries: data/flags/*.json
```

Run from the project root containing `valkeyry-config.yaml`:

```bash
# Maven
mvn -f your-project/pom.xml io.valkeyry:valkeyry-config-maven-plugin:push

# Gradle
./gradlew :your-project:valkeyryConfigPush
```

Expected log:
```
· feature_flags: declaring schema… → registered id=… configVersion=1
  → submitted=N inserted=N duplicates=0
```

---

## 6. LDAP authentication (Docker)

### 6.1 Start a local LDAP

Bitnami's image is the smoothest path — env-var configured, no LDIF wrangling
for the basic case.

```bash
docker run -d --name valkeyry-ldap \
  -p 1389:1389 -p 1636:1636 \
  -e LDAP_ROOT="dc=valkeyry,dc=io" \
  -e LDAP_ADMIN_USERNAME="admin" \
  -e LDAP_ADMIN_PASSWORD="admin" \
  -e LDAP_USERS="alice,bob" \
  -e LDAP_PASSWORDS="alice-pass,bob-pass" \
  -e LDAP_USER_DC="people" \
  bitnami/openldap:2.6
```

Confirm the bind works:

```bash
docker exec -it valkeyry-ldap ldapsearch -x \
  -H ldap://localhost:1389 \
  -D "cn=admin,dc=valkeyry,dc=io" -w admin \
  -b "ou=people,dc=valkeyry,dc=io" "(uid=alice)"
```

You should see `dn: cn=alice,ou=people,dc=valkeyry,dc=io`. Note that Bitnami
sets the RDN to `cn`, so the corresponding `VALKEYRY_LDAP_USER_DN` must be
`cn={0},ou=people,...` (not the default `uid={0},...`).

### 6.2 Tag a user with a tenant

LDAP tenant resolution reads the `ou` attribute. Add `ou: demo-tenant` to
Alice:

```bash
cat > /tmp/alice-tenant.ldif <<'EOF'
dn: cn=alice,ou=people,dc=valkeyry,dc=io
changetype: modify
add: ou
ou: demo-tenant
EOF

docker cp /tmp/alice-tenant.ldif valkeyry-ldap:/tmp/alice-tenant.ldif
docker exec -it valkeyry-ldap ldapmodify -x \
  -H ldap://localhost:1389 \
  -D "cn=admin,dc=valkeyry,dc=io" -w admin \
  -f /tmp/alice-tenant.ldif
```

### 6.3 Point valkeyry-config at the LDAP

```bash
docker compose -f docker-compose.dev.yml stop app

export VALKEYRY_LDAP_URL=ldap://host.docker.internal:1389
export VALKEYRY_LDAP_BASE="dc=valkeyry,dc=io"
export VALKEYRY_LDAP_USER_DN="cn={0},ou=people,dc=valkeyry,dc=io"
export VALKEYRY_LDAP_MANAGER_DN="cn=admin,dc=valkeyry,dc=io"
export VALKEYRY_LDAP_MANAGER_PW=admin
export VALKEYRY_LDAP_TENANT_ATTR=ou

docker compose -f docker-compose.dev.yml up -d app
```

### 6.4 Bind from the API

```bash
B64=$(printf 'alice:alice-pass' | base64)
curl -X POST "http://localhost:8081/api/v1/tenants/demo-tenant/tables" \
  -H "Authorization: Basic $B64" \
  -H "Content-Type: application/json" \
  -d '{"tableName":"ldap_smoke","schema":{"type":"object","properties":{"x":{"type":"string"}}}}' | jq
```

Expected: `201 Created`. A `401` usually means the DN pattern doesn't match —
re-run §6.1 `ldapsearch` and check the RDN attribute (`cn` vs `uid`).

### 6.5 Plugin manifest for LDAP

```yaml
endpoint: http://localhost:8081
tenant:   demo-tenant
auth:
  type:     basic
  username: alice
  password: ${VALKEYRY_LDAP_PASS:alice-pass}
tables:
  - name: ldap_table
    schemaInline:
      type: object
      properties:
        msg: { type: string }
```

---

## 7. OIDC / JWT authentication (Docker)

### 7.1 Start a mock OIDC issuer

`navikt/mock-oauth2-server` boots in ~3 seconds, exposes JWKS, and mints
arbitrary JWTs — perfect for local testing.

```bash
docker run -d --name valkeyry-oidc \
  -p 8079:8080 \
  -e SERVER_PORT=8080 \
  ghcr.io/navikt/mock-oauth2-server:2.1.10
```

Confirm:

```bash
curl -s http://localhost:8079/default/.well-known/openid-configuration | jq .issuer
# → "http://localhost:8079/default"
```

### 7.2 Tell valkeyry-config to trust that issuer

```bash
export VALKEYRY_OIDC_ISSUER=http://host.docker.internal:8079/default
docker compose -f docker-compose.dev.yml up -d app
docker compose -f docker-compose.dev.yml logs --tail=50 app | grep -i issuer
```

You should see `Configured oauth2 resource server with issuer …`.

### 7.3 Mint a JWT

The mock server's `/<issuer>/token` accepts arbitrary claims via the `claims`
form param. We embed `tenants: ["demo-tenant"]` so
`JwtTenantAuthoritiesConverter` maps it to `SCOPE_tenant:demo-tenant`:

```bash
cat > /tmp/claims.json <<'EOF'
{ "tenants": ["demo-tenant"], "roles": ["VALKEYRY_WRITER"] }
EOF

JWT=$(curl -s -X POST http://localhost:8079/default/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=valkeyry-console" \
  --data-urlencode "scope=openid" \
  --data-urlencode "claims@/tmp/claims.json" \
  | jq -r .access_token)

echo "JWT length: ${#JWT}"
```

### 7.4 Call the API with the bearer token

```bash
curl -X POST "http://localhost:8081/api/v1/tenants/demo-tenant/tables" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "tableName":"jwt_smoke",
    "schema":{"type":"object","properties":{"hello":{"type":"string"}}}
  }' | jq
```

Expected: `201 Created`. A `401 invalid_token` means the JWT's `iss` claim
didn't match `VALKEYRY_OIDC_ISSUER` byte-for-byte — trailing slashes count.

### 7.5 Use the JWT from the UI

The shipped UI defaults to the `X-API-Key` header. To exercise the JWT path
through the browser, paste your token into the **Auth** field of the
*Connect tenant* dialog (`Bearer …`) and re-load — every subsequent fetch
sends the bearer.

---

## 8. Full integration matrix

Run all four sidecars at once:

```bash
# Postgres + valkeyry-config
docker compose -f docker-compose.dev.yml up -d

# LDAP
docker run -d --name valkeyry-ldap -p 1389:1389 \
  -e LDAP_ROOT="dc=valkeyry,dc=io" -e LDAP_ADMIN_PASSWORD=admin \
  -e LDAP_USERS=alice,bob -e LDAP_PASSWORDS=alice-pass,bob-pass \
  bitnami/openldap:2.6

# OIDC mock
docker run -d --name valkeyry-oidc -p 8079:8080 \
  ghcr.io/navikt/mock-oauth2-server:2.1.10
```

Then exercise every track:

```bash
# API-key
curl -s -H "X-API-Key: plugin-test-key" \
  http://localhost:8081/api/v1/tenants/demo-tenant/tables | jq length

# LDAP
B64=$(printf 'alice:alice-pass' | base64)
curl -s -H "Authorization: Basic $B64" \
  http://localhost:8081/api/v1/tenants/demo-tenant/tables | jq length

# JWT
curl -s -H "Authorization: Bearer $JWT" \
  http://localhost:8081/api/v1/tenants/demo-tenant/tables | jq length
```

All three should return the same count.

---

## 9. Troubleshooting

| Symptom                                                          | Cause / fix                                                                 |
|------------------------------------------------------------------|------------------------------------------------------------------------------|
| `docker compose up` fails with port 5432 already in use          | `brew services stop postgresql` (or change the port mapping in compose).     |
| App logs `Connection to localhost:5432 refused`                   | Postgres still booting. `docker compose logs postgres` — wait for `database system is ready`. |
| LDAP bind returns `Invalid Credentials` even with correct password | DN pattern mismatch. Run `ldapsearch` (§6.1); RDN attribute (`cn` vs `uid`) must match `VALKEYRY_LDAP_USER_DN`. |
| LDAP bind succeeds but writes return `403`                       | User has no `ou` attribute. Re-apply §6.2 LDIF.                              |
| JWT call returns `401 invalid_token`                             | Issuer mismatch. The token's `iss` claim **must equal** `VALKEYRY_OIDC_ISSUER` byte-for-byte. |
| `host.docker.internal` not resolving                             | Old Docker Desktop. Upgrade to ≥ 4.30; on Colima it's `192.168.5.2`.        |
| UI shows "Schema is not valid JSON"                              | You edited Raw JSON to something invalid; switch back to Visual Builder to regenerate. |
| Plugin reports `Each table must have a 'name'`                   | YAML indentation drift — `tables:` items must be 2-space indented under each `- name:`. |
| Apple Silicon: image pull says "no matching manifest"            | Add `--platform linux/amd64` to the `docker run` command (slower, but works under Rosetta). |

Tail everything in one terminal:

```bash
docker compose -f docker-compose.dev.yml logs -f app \
  | sed -u 's/^/[app] /'
```

---

## 10. Teardown

```bash
docker compose -f docker-compose.dev.yml down -v
docker rm -f valkeyry-ldap valkeyry-oidc
```

Happy shipping.
