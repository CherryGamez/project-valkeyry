# Examples

Ready-to-run fixtures + recipes that exercise the `valkeyry-config` API end-to-end.

```
examples/
├── README.md                          ← you are here
├── postman/
│   ├── valkeyry-config.postman_collection.json
│   └── valkeyry-config-local.postman_environment.json
├── import/
│   ├── customers.csv                  ← POST /tools/convert/csv
│   ├── products.xlsx                  ← POST /tools/convert/xlsx (2 sheets)
│   ├── loan-approval.dmn              ← POST /tools/convert/dmn (Camunda DMN 1.3)
│   └── _generate_products_xlsx.py     ← regenerate products.xlsx (openpyxl)
└── webhooks/
    ├── README.md                      ← register + verify HMAC walkthrough
    ├── receiver.js                    ← Node receiver on :9099, prints HMAC verdict
    └── payload-sample.json            ← exact JSON shape AuditWebhookPublisher posts
```

## 1. Postman collection

Both files live in `postman/`. Two ways to use them:

**Postman desktop / web**
1. *Import* → drop `valkeyry-config.postman_collection.json` and
   `valkeyry-config-local.postman_environment.json` onto the dialog.
2. Top-right environment picker → **valkeyry-config (local)**.
3. Run the requests top-to-bottom — the *Auth → Login* step stashes the JWT into
   `{{jwt}}`, the *Audit → list* step captures `{{auditId}}`, and so on.

**Newman (CLI)**
```bash
npm install -g newman
cd valkeyry-config
newman run examples/postman/valkeyry-config.postman_collection.json \
       -e   examples/postman/valkeyry-config-local.postman_environment.json \
       --working-dir .       # so the file-upload requests can resolve relative paths
```

The collection covers every public endpoint:

| Folder                       | Endpoints                                                          |
|------------------------------|--------------------------------------------------------------------|
| 1 · Health & OpenAPI          | `/actuator/health`, `/v3/api-docs`                                |
| 2 · Auth                      | `POST /auth/login`, `GET /auth/me`, `POST /auth/logout`           |
| 3 · Admin                     | tenants + users CRUD                                              |
| 4 · Virtual Tables            | declare schema, ingest (single + batch), get, history, search, delete |
| 5 · Tools — file converters   | `convert/csv`, `convert/xlsx`, `convert/dmn` (uses files from `import/`) |
| 6 · Audit webhooks            | register / list / delete dynamic subscriptions                    |
| 7 · Audit & rollback          | `GET /audit`, `POST /audit/{id}/rollback`                         |

## 2. Import samples (`import/`)

| File                | What the converter returns                                           | Used by                                                |
|---------------------|----------------------------------------------------------------------|--------------------------------------------------------|
| `customers.csv`     | 1 table × 8 rows × 7 columns                                         | Tools tab + Postman §5 + plugin demos in `MAC_TEST_GUIDE.md` |
| `products.xlsx`     | 2 tables (`products`, `stock`) × 6 + 5 rows                          | Tools tab + Postman §5                                  |
| `loan-approval.dmn` | 1 table (`Loan Approval`, hit-policy `FIRST`) × 6 rules × 5 columns  | Tools tab + Postman §5                                  |

Each `tables[].rows[*]` returned by the converter is already shaped to be the
`data` payload of `POST /tables/{name}/entries:batch`. The Tools tab in the UI
takes you straight from upload to ingest.

To regenerate `products.xlsx` (after changing the script):

```bash
pip install openpyxl
python3 examples/import/_generate_products_xlsx.py
```

## 3. Webhooks (`webhooks/`)

A minimal SIEM consumer. Boot the receiver, register it via the API, then
trigger any audit event and watch the HMAC verification pass:

```bash
cd valkeyry-config/examples/webhooks
node receiver.js                                    # one shell

JWT=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
       -H 'Content-Type: application/json' \
       -d '{"username":"admin","password":"admin"}' | jq -r .token)
curl -s -X POST http://localhost:8081/api/v1/tenants/demo-tenant/webhooks \
     -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
     -d '{ "url": "http://localhost:9099/audit",
           "secret": "local-dev-hmac-secret-32-bytes-long" }'

# Trigger any audit event — schema change, entry ingest, etc.
curl -s -X POST http://localhost:8081/api/v1/tenants/demo-tenant/tables \
     -H 'X-API-Key: plugin-test-key' -H 'Content-Type: application/json' \
     -d '{"tableName":"ping","schema":{"type":"object","properties":{"x":{"type":"string"}}}}'
```

The full payload shape and header reference live in
[`webhooks/README.md`](webhooks/README.md).

## Where to look in the test guides

Both [`MAC_TEST_GUIDE.md`](../MAC_TEST_GUIDE.md) and
[`WINDOWS_TEST_GUIDE.md`](../WINDOWS_TEST_GUIDE.md) reference these files in:

- §3.4 — *Tools tab — bulk import via XLSX / CSV / DMN*
- §11 — *Examples folder (Postman / DMN / webhooks)*
