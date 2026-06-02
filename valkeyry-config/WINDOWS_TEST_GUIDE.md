# valkeyry-config — Windows Local Test Guide

> A hand-rolled, end-to-end walkthrough for running **valkeyry-config** and
> **valkeyry-config-plugin** on a Windows laptop. The guide covers every
> authentication track the server supports:
>
> | Track                     | Use case                       | Section |
> |---------------------------|--------------------------------|---------|
> | Anonymous / API-Key       | Quick smoke + plugin push      | §3, §4  |
> | LDAP Basic                | Headless build agents (CI)     | §6      |
> | OIDC JWT (Bearer token)   | Human SSO + tenant scoping     | §7      |
>
> Every command below is **PowerShell 7+**. Anything in `code` blocks is
> meant to be copy-pasted as-is — no placeholders unless explicitly marked
> `<like-this>`.
>
> If you're on macOS, the sister guide is [`MAC_TEST_GUIDE.md`](MAC_TEST_GUIDE.md).

---

## 0. TL;DR — happy path in 4 commands

```powershell
cd valkeyry-config
docker compose -f docker-compose.dev.yml up -d postgres        # boot Postgres
mvn -pl valkeyry-config -am -DskipTests package                # build the jar
docker compose -f docker-compose.dev.yml up -d app             # boot the app
start http://localhost:8081/                                   # open the UI
```

The UI is at **http://localhost:8081/**. Anonymous reads work; writes need
either an API key, an LDAP user, or a JWT (see §3.2 below).

---

## 1. Prerequisites

| Tool               | Version | Install (PowerShell, Admin)                                |
|--------------------|---------|-------------------------------------------------------------|
| **Docker Desktop** | 4.30+   | https://www.docker.com/products/docker-desktop/ (WSL2 backend) |
| **Java JDK**       | 21      | `winget install EclipseAdoptium.Temurin.21.JDK`             |
| **Maven**          | 3.9+    | `winget install Apache.Maven`                               |
| **Git**            | latest  | `winget install Git.Git`                                    |
| **jq**             | latest  | `winget install jqlang.jq`                                  |
| **ldap-utils** *   | latest  | `scoop install openldap` *(only if you want to bind from PowerShell)* |
| **curl**           | bundled | ships with Windows 10/11                                    |

Open a **new** PowerShell so `PATH` picks up the installs, then verify:

```powershell
docker --version           # >= 27.x
java -version              # openjdk 21.x
mvn -version               # JDK must be 21
```

> Docker Desktop → Settings → Resources → give it **≥ 8 GB RAM** and **4 CPUs**.
> The four sidecars (Postgres, OpenLDAP, mock-oauth2, app) together comfortably
> fit, but the default 4 GB is tight when WSL is also running.

---

## 2. Clone & first boot

```powershell
git clone https://github.com/your-org/valkeyry.git
cd valkeyry\valkeyry-config
docker compose -f docker-compose.dev.yml up -d postgres
docker compose -f docker-compose.dev.yml ps
```

You should see one `postgres` container, status `healthy`.

Two ways to run the Spring Boot app:

**A. From your IDE** (recommended while iterating)
1. Open `valkeyry-config/pom.xml` in IntelliJ / VS Code.
2. Run → `ValkeyryConfigApplication`.
3. The app reads `application.yml` directly — Postgres is at `localhost:5432`,
   API keys are blank, LDAP/OIDC are *configured but not required* (the
   filters short-circuit when no `Authorization` header is sent).

**B. All-in-Docker** (more like prod)
```powershell
mvn -pl valkeyry-config -am -DskipTests package
docker compose -f docker-compose.dev.yml up -d app
docker compose -f docker-compose.dev.yml logs -f app
```

Either way, the app is now listening on **http://localhost:8081/**.

---

## 3. Smoke tests

### 3.1 Health endpoint

```powershell
curl http://localhost:8081/actuator/health | jq
# → {"status":"UP",...}
```

### 3.2 Auth tracks at a glance

| Track       | Header                                       | Tenant resolution                |
|-------------|----------------------------------------------|----------------------------------|
| Anonymous   | none                                         | Only reads on permit-all paths   |
| API-key     | `X-API-Key: <key>`                           | Key → tenant from `VALKEYRY_API_KEYS` |
| LDAP Basic  | `Authorization: Basic base64(user:pass)`     | Tenant from user's `ou` attribute |
| OIDC JWT    | `Authorization: Bearer <jwt>`                | Tenant from `tenants` claim       |

### 3.3 Push a schema with the bundled API key

The compose file already wires `VALKEYRY_API_KEYS=plugin-test-key:demo-tenant`,
so the simplest write path is:

```powershell
$API = "http://localhost:8081/api/v1/tenants/demo-tenant"

curl -X POST "$API/tables" `
  -H "X-API-Key: plugin-test-key" `
  -H "Content-Type: application/json" `
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
```

Then ingest one row:

```powershell
curl -X POST "$API/tables/customers/entries" `
  -H "X-API-Key: plugin-test-key" `
  -H "Content-Type: application/json" `
  -d '{"recordKey":"u-1","data":{"email":"jane@acme.io","active":true,"role":"editor"}}' | jq
```

In the UI (http://localhost:8081/) pick the `demo-tenant` tenant from the
sidebar, click `customers`, and you should see the row.

---

## 4. UI tour — flexible config types (dropdown / checkbox / multi-choice)

The create-table modal now ships **two editor modes**, selectable in the modal
itself:

- **Visual Builder** — point-and-click form. Click `+ Add field`, choose a
  widget (Text / Email / URL / Date / Number / Integer / Checkbox / Dropdown /
  Multi-choice), set the label, options, default, required flag. The
  JSON-Schema is regenerated live and pushed verbatim to the registry on
  *Declare*.
- **Raw JSON** — the original textarea, still available for advanced users
  (`oneOf`, nested objects, custom `format`, `$ref`, …).

The **Schema Gallery** chips at the top of the modal seed both modes with the
same templates — flip between them freely without losing state.

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

### 5.1 File-based (`schema:` pointing at a `.schema.json` file)

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

This is **new** — no separate `.schema.json` file needed. All UI widget types
are expressible in YAML:

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

Run from the project root that contains `valkeyry-config.yaml`:

```powershell
# Maven
mvn -f your-project/pom.xml io.valkeyry:valkeyry-config-maven-plugin:push

# Gradle
.\gradlew :your-project:valkeyryConfigPush
```

The plugin logs `· feature_flags: declaring schema… → registered id=…`,
then `submitted=N inserted=N duplicates=0`.

---

## 6. LDAP authentication (Docker)

### 6.1 Start a local LDAP

Bitnami's image is the most reliable on Windows — env-var based, no LDIF file
required, and the schema is loaded automatically.

```powershell
docker run -d --name valkeyry-ldap `
  -p 1389:1389 -p 1636:1636 `
  -e LDAP_ROOT="dc=valkeyry,dc=io" `
  -e LDAP_ADMIN_USERNAME="admin" `
  -e LDAP_ADMIN_PASSWORD="admin" `
  -e LDAP_USERS="alice,bob" `
  -e LDAP_PASSWORDS="alice-pass,bob-pass" `
  -e LDAP_USER_DC="people" `
  bitnami/openldap:2.6
```

Verify the bind:

```powershell
# From inside the container (no Windows ldap-utils needed)
docker exec -it valkeyry-ldap ldapsearch -x `
  -H ldap://localhost:1389 `
  -D "cn=admin,dc=valkeyry,dc=io" -w admin `
  -b "ou=people,dc=valkeyry,dc=io" "(uid=alice)"
```

You should see `dn: cn=alice,ou=people,dc=valkeyry,dc=io` and a `uid: alice`
attribute. By default Bitnami sets `cn=` for the RDN; valkeyry-config's
default `user-dn-pattern` is `uid={0},ou=people,...` — so we need to override
that to `cn={0},ou=people,...` (or change the LDAP layout — Bitnami's docs
have a `LDAP_USER_DC` knob but the RDN is hard-wired to `cn`).

### 6.2 Tag users with a tenant

LDAP tenant resolution reads the `ou` attribute on the user record. Add
`ou: demo-tenant` to Alice:

```powershell
@"
dn: cn=alice,ou=people,dc=valkeyry,dc=io
changetype: modify
add: ou
ou: demo-tenant
"@ | Out-File -Encoding ascii .\alice-tenant.ldif

docker cp .\alice-tenant.ldif valkeyry-ldap:/tmp/alice-tenant.ldif
docker exec -it valkeyry-ldap ldapmodify -x `
  -H ldap://localhost:1389 `
  -D "cn=admin,dc=valkeyry,dc=io" -w admin `
  -f /tmp/alice-tenant.ldif
```

### 6.3 Point valkeyry-config at the LDAP server

Stop the running app, then restart with these env vars:

```powershell
docker compose -f docker-compose.dev.yml stop app

$env:VALKEYRY_LDAP_URL          = "ldap://host.docker.internal:1389"
$env:VALKEYRY_LDAP_BASE         = "dc=valkeyry,dc=io"
$env:VALKEYRY_LDAP_USER_DN      = "cn={0},ou=people,dc=valkeyry,dc=io"
$env:VALKEYRY_LDAP_MANAGER_DN   = "cn=admin,dc=valkeyry,dc=io"
$env:VALKEYRY_LDAP_MANAGER_PW   = "admin"
$env:VALKEYRY_LDAP_TENANT_ATTR  = "ou"

# Run the app from the IDE, or re-up the compose service inheriting these vars:
docker compose -f docker-compose.dev.yml up -d app
```

### 6.4 Bind from the API

```powershell
$pair = "alice:alice-pass"
$b64  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))

curl -X POST "http://localhost:8081/api/v1/tenants/demo-tenant/tables" `
  -H "Authorization: Basic $b64" `
  -H "Content-Type: application/json" `
  -d '{"tableName":"ldap_smoke","schema":{"type":"object","properties":{"x":{"type":"string"}}}}' | jq
```

Expected: `201 Created`. If you see `401`, double-check the `cn=`/`uid=`
pattern matches what `ldapsearch` showed in §6.1.

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

`navikt/mock-oauth2-server` boots in ~3 seconds, exposes JWKS, and lets you
mint arbitrary JWTs via a `/token` endpoint — perfect for local testing.

```powershell
docker run -d --name valkeyry-oidc `
  -p 8079:8080 `
  -e SERVER_PORT=8080 `
  ghcr.io/navikt/mock-oauth2-server:2.1.10
```

The issuer URL is `http://host.docker.internal:8079/default` (the path
`/default` is the default *realm* the image ships with). Confirm:

```powershell
curl http://localhost:8079/default/.well-known/openid-configuration | jq .issuer
# → "http://localhost:8079/default"
```

### 7.2 Tell valkeyry-config to trust that issuer

```powershell
$env:VALKEYRY_OIDC_ISSUER = "http://host.docker.internal:8079/default"
docker compose -f docker-compose.dev.yml up -d app
docker compose -f docker-compose.dev.yml logs --tail=50 app | Select-String "issuer"
```

You should see `Configured oauth2 resource server with issuer …`.

### 7.3 Mint a JWT

The mock server has a `/<issuer>/token` endpoint that accepts arbitrary claims
via the `claims` form param. We embed `tenants: ["demo-tenant"]` so the
`JwtTenantAuthoritiesConverter` maps it to `SCOPE_tenant:demo-tenant`:

```powershell
$resp = curl -s -X POST "http://localhost:8079/default/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  --data-urlencode "grant_type=client_credentials" `
  --data-urlencode "client_id=valkeyry-console" `
  --data-urlencode "scope=openid" `
  --data-urlencode "claims={`"tenants`":[`"demo-tenant`"],`"roles`":[`"VALKEYRY_WRITER`"]}"
$JWT = ($resp | ConvertFrom-Json).access_token
"JWT length: $($JWT.Length)"
```

> The `claims` payload **must** be valid JSON. PowerShell's escaping is a pain
> — easiest is to save it to a file and reference it with `--data-urlencode "claims@claims.json"`.

### 7.4 Call the API with the bearer token

```powershell
curl -X POST "http://localhost:8081/api/v1/tenants/demo-tenant/tables" `
  -H "Authorization: Bearer $JWT" `
  -H "Content-Type: application/json" `
  -d '{
    "tableName":"jwt_smoke",
    "schema":{"type":"object","properties":{"hello":{"type":"string"}}}
  }' | jq
```

Expected: `201 Created`. A 401 means the JWT signature didn't verify — check
that the issuer URL in §7.2 **exactly** matches the one the token was issued
under (trailing slashes count).

### 7.5 Use the JWT from the UI

The shipped UI uses the `X-API-Key` header by default. To exercise the JWT
path through the browser, paste your token into the **Auth** field of the
*Connect tenant* dialog (`Bearer …`) and re-load — every subsequent fetch
sends the bearer.

---

## 8. Putting it all together — full integration matrix

Run all four sidecars at once:

```powershell
# Postgres + valkeyry-config
docker compose -f docker-compose.dev.yml up -d

# LDAP
docker run -d --name valkeyry-ldap -p 1389:1389 `
  -e LDAP_ROOT="dc=valkeyry,dc=io" -e LDAP_ADMIN_PASSWORD=admin `
  -e LDAP_USERS=alice,bob -e LDAP_PASSWORDS=alice-pass,bob-pass `
  bitnami/openldap:2.6

# OIDC mock
docker run -d --name valkeyry-oidc -p 8079:8080 ghcr.io/navikt/mock-oauth2-server:2.1.10
```

Then exercise every track in one PowerShell session:

```powershell
# API-key
curl -s -H "X-API-Key: plugin-test-key" http://localhost:8081/api/v1/tenants/demo-tenant/tables | jq length

# LDAP (after §6 setup)
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("alice:alice-pass"))
curl -s -H "Authorization: Basic $b64" http://localhost:8081/api/v1/tenants/demo-tenant/tables | jq length

# JWT (after §7 setup, with $JWT from §7.3)
curl -s -H "Authorization: Bearer $JWT" http://localhost:8081/api/v1/tenants/demo-tenant/tables | jq length
```

All three should return the same count.

---

## 9. Troubleshooting

| Symptom                                                          | Cause / fix                                                                 |
|------------------------------------------------------------------|------------------------------------------------------------------------------|
| `docker compose up` fails with port 5432 in use                  | Native Postgres is running. `Stop-Service postgresql-x64-16` or change the port mapping. |
| App logs `Connection to localhost:5432 refused`                   | Postgres still booting. `docker compose logs postgres` — wait for `database system is ready`. |
| LDAP bind returns `Invalid Credentials` even with correct password | DN pattern mismatch. Run `ldapsearch` (§6.1) — the RDN attribute (`cn` vs `uid`) must match `VALKEYRY_LDAP_USER_DN`. |
| LDAP bind succeeds but writes return `403`                       | User has no `ou` attribute. Re-apply §6.2 LDIF.                              |
| JWT call returns `401 invalid_token`                             | Issuer mismatch. The token's `iss` claim **must equal** `VALKEYRY_OIDC_ISSUER` byte-for-byte. |
| `http://host.docker.internal` not resolving                      | Older Docker Desktop. Upgrade to ≥ 4.30, or replace with the WSL2 gateway IP. |
| UI shows "Schema is not valid JSON"                              | You edited Raw JSON to something invalid; switch back to *Visual Builder* to regenerate. |
| Plugin reports `Each table must have a 'name'`                   | YAML indentation drift — make sure `tables:` items are 2-space indented under the `- name:` key. |

To watch all logs in one terminal:

```powershell
docker compose -f docker-compose.dev.yml logs -f app `
  | ForEach-Object { "[app] $_" }
```

---

## 10. Teardown

```powershell
docker compose -f docker-compose.dev.yml down -v
docker rm -f valkeyry-ldap valkeyry-oidc
```

Happy shipping.
