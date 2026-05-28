# Architecture

```
                            ┌────────────────────────────┐
                            │  Humans (UI / CLI / curl)  │
                            └─────────────┬──────────────┘
                                          │ OIDC Bearer JWT
                                          ▼
            ┌──────────────────────────────────────────────────────────┐
            │                  valkeyry-config                          │
            │  WebFlux  +  R2DBC  +  Flyway  +  JSON Schema validator    │
            │  ┌─────────────────────┐   ┌────────────────────────────┐ │
            │  │ VirtualTableRegistry│   │ VirtualTableEntry (JSONB)  │ │
            │  │  schema versions    │←→ │  payload-hash dedup        │ │
            │  └─────────────────────┘   │  is_latest version trail   │ │
            │                            └────────────────────────────┘ │
            └────────┬─────────────────────────────────────────┬──────────┘
                     │ HTTP Basic / API-key                     │ R2DBC
                     │                                          ▼
            ┌────────┴────────────┐                        ┌──────────────┐
            │ Build-tool plugin   │                        │ PostgreSQL   │
            │ (Maven Mojo /       │                        │   JSONB +    │
            │  Gradle Task)       │                        │   GIN index  │
            │                     │                        └──────────────┘
            └─────────────────────┘

                            ┌────────────────────────────┐
                            │  Producers (services, UIs) │
                            └─────────────┬──────────────┘
                                          │ OIDC / Basic
                                          ▼
            ┌──────────────────────────────────────────────────────────┐
            │                  valkeyry-ipaas                            │
            │  WebFlux  +  Reactor-Kafka  +  Reactor-RabbitMQ  +  JMS    │
            │  + Reactive Redis (Valkey) token-bucket WebFilter           │
            │  + S3/MinIO Claim-Check externalisation                     │
            └────────┬────────────────┬────────────────┬─────────────────┘
                     │                │                │
                     ▼                ▼                ▼
               ┌─────────┐      ┌─────────┐      ┌──────────┐
               │  Kafka  │      │ RabbitMQ│      │ ActiveMQ │
               └─────────┘      └─────────┘      └──────────┘
```

## Modules

### `valkeyry-config`
- **Reactive** REST API gated by `TenantAccessGuard`.
- **Flyway** runs over JDBC at startup to manage schema. At runtime, **R2DBC** does all work.
- The **Idempotency Guard** is enforced by `(tenant, table, record_key, payload_hash)` lookup
  before insert. The fingerprint algorithm is shared verbatim with the build-tool plugin.

### `valkeyry-ipaas`
- All publish work flows through `TenantRouter`:
  1. `ClaimCheckService` decides inline vs externalised;
  2. `BrokerRegistry` picks the adapter (default + per-call override);
  3. adapter handles the wire format.
- The rate-limit WebFilter is wired ahead of the controllers and uses a **Lua-atomic**
  bucket on Valkey so the token check and update are a single round-trip.

### `valkeyry-config-plugin`
- `plugin-core` has **zero Spring Boot** dependencies — it must run inside Maven and Gradle
  classloaders, both of which forbid framework bleed-through.
- Same hash algorithm as the server, allowing the *server* to short-circuit unchanged payloads
  even when the *plugin* didn't know it was a duplicate.

## Security tracks

| Track | Caller        | Header                  | Authority shape           |
|-------|---------------|-------------------------|---------------------------|
| 1     | Human / SaaS  | `Authorization: Bearer` | `SCOPE_tenant:<id>` (mapped from JWT claim `valkeyry.tenants`) |
| 2     | LDAP agent    | `Authorization: Basic`  | `SCOPE_tenant:<id>` (mapped from LDAP `ou` attribute) |
| 2     | Headless CI   | `X-API-Key: …`          | `SCOPE_tenant:<id>` (from `valkeyry.api-keys.table`) |

`TenantAccessGuard` only sees authorities — every track converges on the same normalised
representation, so a single guard guarantees correctness for both flows.

## Data flow examples

### Plugin push
```
plugin engine  ──►  POST /api/v1/tenants/acme/tables          (declare schema)
plugin engine  ──►  POST /api/v1/tenants/acme/tables/x/entries:batch  (upload N records)
                                  │
                                  ▼
                       JsonSchemaValidator
                                  │
                                  ▼
                       PayloadFingerprint (SHA-256)
                                  │
                                  ▼
                       Idempotency Guard (dup? skip)
                                  │
                                  ▼
                       markPreviousLatestStale
                                  │
                                  ▼
                       INSERT new row (version+1)
```

### Publish
```
producer ──► POST /api/v1/tenants/acme/messages  { destination, mode, payload }
                                  │
                                  ▼
                       Rate-limit WebFilter (Lua-atomic)
                                  │
                                  ▼
                       ClaimCheckService.externaliseIfNeeded
                                  │
                                  ▼
                       BrokerRegistry.select(broker, mode)
                                  │
                                  ▼
                       BrokerAdapter.publish  (Kafka/Rabbit/ActiveMQ)
```
