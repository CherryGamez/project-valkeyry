"""
Valkeyry-Config — *Mock* Preview Backend
========================================

This is a Python/FastAPI **stand-in** for the real Java/Spring `valkeyry-config`
service. Its sole purpose is to let the Emergent preview pod run the production
GUI end-to-end without a JVM in this environment — every endpoint the UI calls
is mocked here with in-memory storage that mirrors the real API contract.

It is **not** production code and is **not** deployed. The real backend remains
the Java Spring Boot module under `/app/valkeyry-config/`. Use this preview to:

  • drive the HTML/JS console (`/app/valkeyry-config/src/main/resources/static/`),
  • see the "Endpoints & URLs" tab + live-response panel actually working,
  • exercise plugin flows against canned responses,
  • smoke-test schema/widget rendering.

The shape of every response below was lifted verbatim from the Java DTOs
(`VirtualTableEntry`, `AuditEntry`, `WebhookSubscription`, …) so the UI cannot
tell the difference.
"""

from __future__ import annotations

import hashlib
import json
import re
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, FileResponse
from pydantic import BaseModel, Field

# ─────────────────────────────────────────────────────────────────────────────
# In-memory store
# ─────────────────────────────────────────────────────────────────────────────

STATIC_DIR = Path("/app/valkeyry-config/src/main/resources/static")

# tenants[tenantId] = {
#   "tables":   { tableName: { "schema": {...}, "version": int, "createdAt": iso } },
#   "entries":  { (tableName, recordKey): {...} },
#   "history":  { (tableName, recordKey): [versions...] },
#   "audit":    [ {...} ],
#   "webhooks": [ {...} ]
# }
STATE: Dict[str, Dict[str, Any]] = {}

def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

def tenant(tid: str) -> Dict[str, Any]:
    if tid not in STATE:
        STATE[tid] = {"tables": {}, "entries": {}, "history": {}, "audit": [], "webhooks": []}
    return STATE[tid]

def data_hash(d: Any) -> str:
    return "sha256:" + hashlib.sha256(
        json.dumps(d, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()

def push_audit(tid: str, *, op: str, table: Optional[str], record_key: Optional[str],
               before: Any, after: Any, version: int, record_version: int) -> Dict[str, Any]:
    entry = {
        "id": str(uuid.uuid4()),
        "tenantId": tid,
        "tableName": table,
        "recordKey": record_key,
        "operation": op,
        "beforeValue": before,
        "afterValue": after,
        "version": version,
        "recordVersion": record_version,
        "changedAt": now_iso(),
        "changedBy": "mock-preview",
    }
    tenant(tid)["audit"].insert(0, entry)
    return entry

# ─────────────────────────────────────────────────────────────────────────────
# Seed data — so the user lands on a populated demo tenant
# ─────────────────────────────────────────────────────────────────────────────

USER_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "User profile",
    "type": "object",
    "required": ["email"],
    "properties": {
        "email":    {"type": "string", "format": "email", "title": "Email"},
        "fullName": {"type": "string", "title": "Full name"},
        "role":     {"type": "string", "title": "Role", "enum": ["viewer", "editor", "admin", "owner"], "default": "viewer"},
        "active":   {"type": "boolean", "title": "Active", "default": True},
        "tags": {
            "type": "array",
            "title": "Tags",
            "uniqueItems": True,
            "items": {"type": "string", "enum": ["internal", "beta", "ga", "vip"]},
        },
        "joinedAt": {"type": "string", "format": "date", "title": "Joined date"},
    },
    "additionalProperties": False,
}

FLAG_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "Feature flag",
    "type": "object",
    "required": ["key", "enabled"],
    "properties": {
        "key":     {"type": "string", "pattern": "^[a-z0-9_.-]+$"},
        "enabled": {"type": "boolean", "default": False},
        "rolloutPercent": {"type": "integer", "minimum": 0, "maximum": 100, "default": 0},
        "audiences": {
            "type": "array",
            "uniqueItems": True,
            "items": {"type": "string", "enum": ["internal", "beta", "early-access", "ga", "enterprise"]},
        },
        "owner": {"type": "string", "enum": ["platform", "payments", "growth", "security"]},
    },
    "additionalProperties": False,
}

def seed() -> None:
    t = tenant("demo-tenant")
    t["tables"]["users"]         = {"schema": USER_SCHEMA, "version": 1, "createdAt": now_iso()}
    t["tables"]["feature_flags"] = {"schema": FLAG_SCHEMA, "version": 1, "createdAt": now_iso()}

    sample_users = [
        ("alice@acme.io", {"email":"alice@acme.io","fullName":"Alice","role":"admin","active":True,
                           "tags":["internal","vip"],"joinedAt":"2024-04-01"}),
        ("bob@acme.io",   {"email":"bob@acme.io",  "fullName":"Bob",  "role":"editor","active":True,
                           "tags":["ga"],          "joinedAt":"2024-05-10"}),
        ("carol@acme.io", {"email":"carol@acme.io","fullName":"Carol","role":"viewer","active":False,
                           "tags":["beta"],        "joinedAt":"2024-06-21"}),
    ]
    for rk, d in sample_users:
        t["entries"][("users", rk)] = build_entry("users", rk, d, 1, 1)
        t["history"].setdefault(("users", rk), []).append(t["entries"][("users", rk)])
        push_audit("demo-tenant", op="INGEST_ROW", table="users", record_key=rk,
                   before=None, after=d, version=1, record_version=1)

    sample_flags = [
        ("checkout.v2",   {"key":"checkout.v2","enabled":True, "rolloutPercent":75,
                           "audiences":["beta","ga"],"owner":"payments"}),
        ("dark.mode",     {"key":"dark.mode","enabled":True, "rolloutPercent":100,
                           "audiences":["ga","internal"],"owner":"platform"}),
        ("export.csv",    {"key":"export.csv","enabled":False,"rolloutPercent":0,
                           "audiences":["internal"],"owner":"growth"}),
    ]
    for rk, d in sample_flags:
        t["entries"][("feature_flags", rk)] = build_entry("feature_flags", rk, d, 1, 1)
        t["history"].setdefault(("feature_flags", rk), []).append(t["entries"][("feature_flags", rk)])
        push_audit("demo-tenant", op="INGEST_ROW", table="feature_flags", record_key=rk,
                   before=None, after=d, version=1, record_version=1)

def build_entry(table: str, key: str, data: Any, version: int, record_version: int) -> Dict[str, Any]:
    return {
        "id": str(uuid.uuid4()),
        "tableName": table,
        "recordKey": key,
        "data": data,
        "version": version,
        "recordVersion": record_version,
        "dataHash": data_hash(data),
        "createdAt": now_iso(),
        "deleted": False,
    }

# ─────────────────────────────────────────────────────────────────────────────
# Minimal JSON-Schema validator (covers the widgets the UI emits)
# ─────────────────────────────────────────────────────────────────────────────

def validate(schema: Dict[str, Any], value: Any) -> List[Dict[str, str]]:
    """Best-effort validator. Returns a list of violation dicts identical in
    shape to what the Java `SchemaValidationException` would produce."""
    errs: List[Dict[str, str]] = []
    if not isinstance(schema, dict):
        return errs

    def walk(s: Dict[str, Any], v: Any, path: str):
        t = s.get("type")
        if "enum" in s and v not in s["enum"]:
            errs.append({"pointer": path, "message": f"'{v}' is not one of {s['enum']}"})
            return
        if t == "object":
            if not isinstance(v, dict):
                errs.append({"pointer": path, "message": f"expected object, got {type(v).__name__}"}); return
            for req in s.get("required", []):
                if req not in v:
                    errs.append({"pointer": f"{path}/{req}", "message": "required property missing"})
            for k, sub in (s.get("properties") or {}).items():
                if k in v: walk(sub, v[k], f"{path}/{k}")
        elif t == "array":
            if not isinstance(v, list):
                errs.append({"pointer": path, "message": "expected array"}); return
            item_s = s.get("items") or {}
            for i, item in enumerate(v):
                walk(item_s, item, f"{path}/{i}")
        elif t == "boolean":
            if not isinstance(v, bool):
                errs.append({"pointer": path, "message": "expected boolean"})
        elif t == "integer":
            if not isinstance(v, int) or isinstance(v, bool):
                errs.append({"pointer": path, "message": "expected integer"})
            else:
                if "minimum" in s and v < s["minimum"]:
                    errs.append({"pointer": path, "message": f"must be ≥ {s['minimum']}"})
                if "maximum" in s and v > s["maximum"]:
                    errs.append({"pointer": path, "message": f"must be ≤ {s['maximum']}"})
        elif t == "number":
            if not isinstance(v, (int, float)) or isinstance(v, bool):
                errs.append({"pointer": path, "message": "expected number"})
        elif t == "string":
            if not isinstance(v, str):
                errs.append({"pointer": path, "message": "expected string"})
            else:
                if "pattern" in s and not re.search(s["pattern"], v):
                    errs.append({"pointer": path, "message": f"does not match pattern {s['pattern']}"})
                fmt = s.get("format")
                if fmt == "email" and not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", v):
                    errs.append({"pointer": path, "message": "not a valid email"})
                elif fmt == "uri" and not re.match(r"^https?://", v):
                    errs.append({"pointer": path, "message": "not a valid uri"})
                elif fmt == "date" and not re.match(r"^\d{4}-\d{2}-\d{2}$", v):
                    errs.append({"pointer": path, "message": "expected YYYY-MM-DD date"})
                elif fmt == "date-time" and not re.match(r"^\d{4}-\d{2}-\d{2}T", v):
                    errs.append({"pointer": path, "message": "expected ISO 8601 date-time"})

    walk(schema, value, "")
    return errs

# ─────────────────────────────────────────────────────────────────────────────
# Field projection — `?fields=email,role,data.role` style query param.
# Top-level keys filter the EntryView shape; dotted keys reach into `data`.
# Empty/missing → return all keys (the default contract).
# ─────────────────────────────────────────────────────────────────────────────

def project(entry: Dict[str, Any], fields_csv: Optional[str]) -> Dict[str, Any]:
    if not fields_csv:
        return entry
    wanted = [f.strip() for f in fields_csv.split(",") if f.strip()]
    if not wanted:
        return entry
    out: Dict[str, Any] = {}
    nested_keys: List[str] = []
    for f in wanted:
        if "." in f:
            nested_keys.append(f)
        elif f in entry:
            out[f] = entry[f]
    if nested_keys:
        data = entry.get("data") or {}
        out.setdefault("data", {})
        for nk in nested_keys:
            head, _, tail = nk.partition(".")
            if head != "data":
                continue
            if tail in (data or {}):
                out["data"][tail] = data[tail]
    return out


# ─────────────────────────────────────────────────────────────────────────────
# FastAPI app
# ─────────────────────────────────────────────────────────────────────────────

app = FastAPI(title="Valkeyry Config — Mock Preview", version="v1-preview")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

@app.middleware("http")
async def access_log(req: Request, call_next):
    t0 = time.time()
    resp = await call_next(req)
    print(f"[{req.method:6}] {req.url.path}  →  {resp.status_code}  ({(time.time()-t0)*1000:.1f}ms)", flush=True)
    return resp

# ─────────── Static console (served also by Node at port 3000) ───────────

@app.get("/", include_in_schema=False)
def root_index():
    return FileResponse(STATIC_DIR / "index.html")

# Sister pages — explicit routes so the FastAPI mock serves them with the right
# content-type. The Node proxy on :3000 mirrors these onto the same paths.
@app.get("/login.html", include_in_schema=False)
def login_page(): return FileResponse(STATIC_DIR / "login.html")

@app.get("/admin.html", include_in_schema=False)
def admin_page(): return FileResponse(STATIC_DIR / "admin.html")

@app.get("/tools.html", include_in_schema=False)
def tools_page(): return FileResponse(STATIC_DIR / "tools.html")

@app.get("/assets/{name:path}", include_in_schema=False)
def assets(name: str):
    p = (STATIC_DIR / "assets" / name).resolve()
    if not str(p).startswith(str(STATIC_DIR / "assets")) or not p.exists():
        raise HTTPException(status_code=404, detail="not found")
    return FileResponse(p)

# ─────────── Health / info / Swagger / OpenAPI ───────────

@app.get("/actuator/health")
def health(): return {"status": "UP", "components": {"db": {"status": "UP"}}}

@app.get("/actuator/info")
def info():
    return {
        "build": {"artifact":"valkeyry-config","name":"Valkeyry Config","version":"1.0.0-SNAPSHOT","time":now_iso()},
        "git":   {"branch":"valkeyry-emergent","commit":{"id":"preview"}}
    }

@app.get("/v3/api-docs", include_in_schema=False)
def openapi_json():
    """Spring-style alias for FastAPI's auto-generated /openapi.json."""
    return JSONResponse(app.openapi())

@app.get("/v3/api-docs.yaml", include_in_schema=False)
def openapi_yaml():
    import yaml  # PyYAML ships with FastAPI deps in this image
    return PlainTextResponse(yaml.safe_dump(app.openapi(), sort_keys=False), media_type="application/yaml")

@app.get("/swagger-ui.html", include_in_schema=False)
def swagger_ui():
    """Pin Swagger-UI from a CDN so we don't need to vendor static assets."""
    return HTMLResponse("""<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><title>Valkeyry Config · Swagger UI (preview)</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui.css">
<style>body{margin:0}</style></head>
<body><div id="swagger"></div>
<script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-bundle.js"></script>
<script>
SwaggerUIBundle({
  url: '/v3/api-docs',
  dom_id: '#swagger',
  deepLinking: true,
  layout: 'BaseLayout',
  persistAuthorization: true
});
</script></body></html>""")

# ─────────── Virtual tables ───────────

class DeclareTableRequest(BaseModel):
    tableName: str
    schema_:   Dict[str, Any] = Field(alias="schema")
    class Config: populate_by_name = True

@app.get("/api/v1/tenants/{tenant_id}/tables")
def list_tables(tenant_id: str):
    return [
        {"tableName": name, **{k: meta[k] for k in ("version", "createdAt")}, "schema": meta["schema"]}
        for name, meta in tenant(tenant_id)["tables"].items()
    ]

@app.post("/api/v1/tenants/{tenant_id}/tables", status_code=201)
def declare_table(tenant_id: str, body: DeclareTableRequest):
    t = tenant(tenant_id)
    existed = body.tableName in t["tables"]
    v = (t["tables"][body.tableName]["version"] + 1) if existed else 1
    t["tables"][body.tableName] = {"schema": body.schema_, "version": v, "createdAt": now_iso()}
    push_audit(tenant_id, op="REVISE_TABLE" if existed else "DECLARE_TABLE",
               table=body.tableName, record_key=None,
               before=None, after={"schema": body.schema_}, version=v, record_version=0)
    return {"tableName": body.tableName, "version": v, "schema": body.schema_, "createdAt": now_iso(),
            "configVersion": v}

@app.get("/api/v1/tenants/{tenant_id}/tables/{name}")
def get_table(tenant_id: str, name: str):
    t = tenant(tenant_id)
    if name not in t["tables"]:
        raise HTTPException(status_code=404, detail=f"Table '{name}' not found")
    meta = t["tables"][name]
    return {"tableName": name, **meta}

# ─────────── Entries ───────────

class IngestEntryRequest(BaseModel):
    recordKey: str
    data: Any

@app.post("/api/v1/tenants/{tenant_id}/tables/{name}/entries", status_code=201)
def ingest(tenant_id: str, name: str, body: IngestEntryRequest):
    t = tenant(tenant_id)
    if name not in t["tables"]:
        raise HTTPException(status_code=404, detail=f"Table '{name}' not found")
    schema = t["tables"][name]["schema"]
    errs = validate(schema, body.data)
    if errs:
        return JSONResponse(status_code=422, content={
            "type": "urn:valkeyry:error:schema-violation",
            "title": "Schema validation failed",
            "status": 422,
            "detail": f"{len(errs)} violation(s)",
            "violations": errs,
        })

    key = (name, body.recordKey)
    prev = t["entries"].get(key)
    if prev and prev["dataHash"] == data_hash(body.data):
        return JSONResponse(status_code=409, content={
            "type": "urn:valkeyry:error:duplicate-write",
            "title": "Idempotency-skip",
            "status": 409,
            "detail": "Identical payload already on record — skipped",
        })
    record_version = (prev["recordVersion"] + 1) if prev else 1
    entry = build_entry(name, body.recordKey, body.data, record_version, record_version)
    t["entries"][key] = entry
    t["history"].setdefault(key, []).append(entry)
    push_audit(tenant_id, op="INGEST_ROW", table=name, record_key=body.recordKey,
               before=(prev or {}).get("data"), after=body.data,
               version=record_version, record_version=record_version)
    return entry

@app.post("/api/v1/tenants/{tenant_id}/tables/{name}/entries:batch", status_code=201)
def ingest_batch(tenant_id: str, name: str, body: List[IngestEntryRequest]):
    out = []
    for row in body:
        result = ingest(tenant_id, name, row)
        if isinstance(result, JSONResponse):
            out.append(json.loads(result.body))
        else:
            out.append(result)
    return out

@app.get("/api/v1/tenants/{tenant_id}/tables/{name}/entries")
def list_entries(tenant_id: str, name: str,
                 limit: int = Query(50, ge=1, le=1000),
                 offset: int = Query(0, ge=0),
                 fields: Optional[str] = Query(None,
                     description="Comma-separated field list to project. "
                                 "Use `data.<key>` for nested data keys. "
                                 "Default = all fields.")):
    rows = _collect_entries(tenant_id, name, limit + offset + 1)
    paged = rows[offset:offset + limit]
    return [project(r, fields) for r in paged]

def _collect_entries(tenant_id: str, name: str, limit: int = 1000) -> List[Dict[str, Any]]:
    t = tenant(tenant_id)
    rows = [e for (tn, _rk), e in t["entries"].items() if tn == name and not e.get("deleted")]
    rows.sort(key=lambda e: e["createdAt"], reverse=True)
    return rows[:limit]

@app.get("/api/v1/tenants/{tenant_id}/tables/{name}/entries/{record_key}")
def get_entry(tenant_id: str, name: str, record_key: str,
              fields: Optional[str] = Query(None)):
    e = tenant(tenant_id)["entries"].get((name, record_key))
    if not e or e.get("deleted"):
        raise HTTPException(status_code=404, detail="Entry not found")
    return project(e, fields)

@app.delete("/api/v1/tenants/{tenant_id}/tables/{name}/entries/{record_key}", status_code=204)
def delete_entry(tenant_id: str, name: str, record_key: str):
    t = tenant(tenant_id)
    key = (name, record_key)
    if key not in t["entries"]:
        raise HTTPException(status_code=404, detail="Entry not found")
    prev = t["entries"][key]
    t["entries"][key] = {**prev, "deleted": True}
    push_audit(tenant_id, op="DELETE_ROW", table=name, record_key=record_key,
               before=prev["data"], after=None,
               version=prev["recordVersion"]+1, record_version=prev["recordVersion"]+1)
    return Response(status_code=204)

@app.get("/api/v1/tenants/{tenant_id}/tables/{name}/entries/{record_key}/history")
def entry_history(tenant_id: str, name: str, record_key: str):
    return tenant(tenant_id)["history"].get((name, record_key), [])

@app.post("/api/v1/tenants/{tenant_id}/query")
def query_unified(tenant_id: str, body: Dict[str, Any]):
    """PL/SQL-style unified query: { table, field, op, value, fields? }.

    `field` may be either `recordKey` (top-level) or `data.<key>` for any
    column inside the JSONB payload. `op` is one of `equals`, `contains`,
    `startsWith`. Empty `field` / `value` → list rows (with optional
    projection).
    """
    table = body.get("table")
    if not table:
        raise HTTPException(status_code=400, detail="'table' is required")
    field = (body.get("field") or "").strip()
    op    = (body.get("op")    or "equals").strip()
    value = body.get("value")
    fields = body.get("fields")
    if not field or value in (None, ""):
        rows = _collect_entries(tenant_id, table, 1000)
        return [project(r, fields) for r in rows]
    # Resolve which getter to use per cell — recordKey lives on the row;
    # data.<col> lives inside the JSONB payload.
    def cell(row):
        if field == "recordKey":              return row.get("recordKey")
        if field.startswith("data."):         return (row.get("data") or {}).get(field[5:])
        return None
    def matches(row):
        v = cell(row)
        if op == "equals":     return v == value
        if op == "contains":   return v is not None and str(value).lower() in str(v).lower()
        if op == "startsWith": return v is not None and str(v).lower().startswith(str(value).lower())
        return False
    rows = [r for r in _collect_entries(tenant_id, table, 1000) if matches(r)]
    return [project(r, fields) for r in rows]


@app.post("/api/v1/tenants/{tenant_id}/query2")
def query_advanced(tenant_id: str, body: Dict[str, Any]):
    """Multi-condition PL/SQL-style query — mirrors the Java UnifiedQueryController#queryAdvanced.

    Body: ``{ table, conditions: [{field, op, value, value2?, connector?}], fields?, limit?, offset? }``.

    Supported ops: equals, notEquals, contains, notContains, startsWith, endsWith, regex, in,
    notIn, gt, gte, lt, lte, between, isNull, isNotNull, before, after, onDate, betweenDates.
    """
    import re as _re
    from datetime import datetime as _dt
    table = body.get("table")
    if not table:
        raise HTTPException(status_code=400, detail="'table' is required")
    conditions = body.get("conditions") or []
    fields = body.get("fields")
    limit  = body.get("limit") or 1000
    offset = body.get("offset") or 0

    def get_cell(row, field):
        if not field: return None
        if field == "recordKey": return row.get("recordKey")
        path = field[5:] if field.startswith("data.") else field
        cur = row.get("data") or {}
        for p in path.split('.'):
            if not isinstance(cur, dict): return None
            cur = cur.get(p)
        return cur

    def parse_dt(v):
        if v is None: return None
        if isinstance(v, (int, float)): return _dt.fromtimestamp(v)
        try: return _dt.fromisoformat(str(v).replace('Z', '+00:00'))
        except Exception: return None

    def eval_cond(row, c):
        field = c.get("field", "")
        op = c.get("op")
        v  = c.get("value")
        v2 = c.get("value2")
        cell = get_cell(row, field)
        # Empty field → TRUE (matches Java behavior)
        if not field: return True
        try:
            if op == "equals":     return cell == v
            if op == "notEquals":  return cell != v
            if op == "contains":   return cell is not None and str(v).lower() in str(cell).lower()
            if op == "notContains":return cell is None or str(v).lower() not in str(cell).lower()
            if op == "startsWith": return cell is not None and str(cell).lower().startswith(str(v).lower())
            if op == "endsWith":   return cell is not None and str(cell).lower().endswith(str(v).lower())
            if op == "regex":      return cell is not None and bool(_re.search(str(v), str(cell), _re.IGNORECASE))
            if op in ("in","notIn"):
                items = v if isinstance(v, list) else [s.strip() for s in str(v).split(',') if s.strip()]
                hit = str(cell) in [str(x) for x in items]
                return hit if op == "in" else not hit
            if op == "isNull":     return cell is None
            if op == "isNotNull":  return cell is not None
            if op in ("gt","gte","lt","lte"):
                if cell is None or v is None: return False
                try: a, b = float(cell), float(v)
                except (TypeError, ValueError): return False
                return {"gt":a>b,"gte":a>=b,"lt":a<b,"lte":a<=b}[op]
            if op == "between":
                if cell is None or v is None or v2 is None: return False
                try: x, lo, hi = float(cell), float(v), float(v2)
                except (TypeError, ValueError): return False
                return lo <= x <= hi
            if op in ("before","after","onDate"):
                cd, vd = parse_dt(cell), parse_dt(v)
                if cd is None or vd is None: return False
                if op == "before": return cd < vd
                if op == "after":  return cd > vd
                return cd.date() == vd.date()
            if op == "betweenDates":
                cd, lo, hi = parse_dt(cell), parse_dt(v), parse_dt(v2)
                if cd is None or lo is None or hi is None: return False
                return lo <= cd <= hi
        except Exception as _exc:
            # Don't let one malformed cell zero-out the entire result set; just skip the row
            # but surface the cause in the server log so misbehaving regex/dates are diagnosable.
            print(f"[query2] eval_cond skipped row (field={field!r}, op={op!r}): {_exc}", flush=True)
            return False
        raise HTTPException(status_code=400, detail=f"Unsupported op: {op}")

    def matches(row):
        if not conditions: return True
        acc = eval_cond(row, conditions[0])
        for c in conditions[1:]:
            conn = (c.get("connector") or "AND").upper()
            cur = eval_cond(row, c)
            if conn == "OR":  acc = acc or cur
            else:             acc = acc and cur
        return acc

    rows = [r for r in _collect_entries(tenant_id, table, limit + offset) if matches(r)]
    rows = rows[offset:offset + limit]
    return [project(r, fields) for r in rows]



@app.post("/api/v1/tenants/{tenant_id}/tables/{name}/search")
def search(tenant_id: str, name: str, body: Dict[str, Any],
           fields: Optional[str] = Query(None)):
    """Mini WHERE engine: supports `equals`, `contains` (substring), `startsWith`."""
    rows = _collect_entries(tenant_id, name, 1000)
    def matches(row, op, pred):
        d = (row.get("data") or {})
        for k, v in pred.items():
            cell = d.get(k)
            if op == "equals":
                if cell != v: return False
            elif op == "contains":
                if cell is None or str(v).lower() not in str(cell).lower(): return False
            elif op == "startsWith":
                if cell is None or not str(cell).lower().startswith(str(v).lower()): return False
            else:
                return False
        return True
    out = []
    for r in rows:
        if all(matches(r, op, pred) for op, pred in body.items() if isinstance(pred, dict)):
            out.append(project(r, fields))
    return out

# ─────────── Audit ───────────

@app.get("/api/v1/tenants/{tenant_id}/audit")
def list_audit(tenant_id: str, limit: int = 200):
    return tenant(tenant_id)["audit"][:limit]

@app.post("/api/v1/tenants/{tenant_id}/audit/{audit_id}/rollback", status_code=201)
def rollback(tenant_id: str, audit_id: str):
    t = tenant(tenant_id)
    audit = next((a for a in t["audit"] if a["id"] == audit_id), None)
    if not audit:
        raise HTTPException(status_code=404, detail="Audit entry not found")
    if not audit.get("recordKey") or audit.get("beforeValue") is None:
        return JSONResponse(status_code=409,
                            content={"type":"urn:valkeyry:error:not-rollbackable",
                                     "title":"Not rollbackable","status":409,
                                     "detail":"Only INGEST_ROW with a prior value can be rolled back"})
    return ingest(tenant_id, audit["tableName"],
                  IngestEntryRequest(recordKey=audit["recordKey"], data=audit["beforeValue"]))

# ─────────── Webhooks ───────────

class WebhookCreateRequest(BaseModel):
    url: str
    description: Optional[str] = None
    secret: Optional[str] = None

@app.get("/api/v1/tenants/{tenant_id}/webhooks")
def list_webhooks(tenant_id: str): return tenant(tenant_id)["webhooks"]

@app.post("/api/v1/tenants/{tenant_id}/webhooks", status_code=201)
def create_webhook(tenant_id: str, body: WebhookCreateRequest):
    t = tenant(tenant_id)
    if any(w["url"] == body.url for w in t["webhooks"]):
        return JSONResponse(status_code=409,
                            content={"type":"urn:valkeyry:error:duplicate-resource",
                                     "title":"Duplicate","status":409,
                                     "detail":f"Webhook URL {body.url!r} already registered"})
    wh = {"id": str(uuid.uuid4()), "url": body.url, "description": body.description,
          "enabled": True, "createdAt": now_iso()}
    t["webhooks"].append(wh)
    return wh

@app.delete("/api/v1/tenants/{tenant_id}/webhooks/{wh_id}", status_code=204)
def delete_webhook(tenant_id: str, wh_id: str):
    t = tenant(tenant_id)
    before = len(t["webhooks"])
    t["webhooks"] = [w for w in t["webhooks"] if w["id"] != wh_id]
    if len(t["webhooks"]) == before:
        raise HTTPException(status_code=404, detail="Webhook not found")
    return Response(status_code=204)

# ─────────── Preview banner endpoint (helper for the GUI) ───────────

@app.get("/api/__preview_info")
def preview_info():
    return {
        "mode": "mock-preview",
        "note": "This is the Python/FastAPI preview backend, not the real Java service.",
        "seededTenants": list(STATE.keys()),
    }

# ═══════════════════════════════════════════════════════════════════════════
# Auth / Admin / Tools — mock parity for the new HTMX login flow
# (real implementations live in AuthController / AdminController / ToolsController)
# ═══════════════════════════════════════════════════════════════════════════

import base64
import io
import csv
import xml.etree.ElementTree as ET

ADMIN_USER = "admin"
ADMIN_PASS = "admin"
SSO_ENABLED  = False
LDAP_ENABLED = False
SSO_AUTH_URL = ""

# In-memory admin store. Pre-seed with admin so /admin.html shows something useful.
ADMIN_TENANTS: Dict[str, Dict[str, Any]] = {
    "demo-tenant": {
        "id": "demo-tenant", "name": "Demo Tenant",
        "description": "Pre-seeded preview tenant.",
        "enabled": True, "createdAt": now_iso(), "createdBy": "bootstrap",
    }
}
ADMIN_USERS: Dict[str, Dict[str, Any]] = {
    "admin": {
        "id": str(uuid.uuid4()), "username": "admin",
        "displayName": "Built-in administrator", "email": None,
        "role": "admin", "source": "LOCAL", "enabled": True,
        "tenants": ["*"], "createdAt": now_iso(), "createdBy": "bootstrap",
        # password held outside the view (mock plaintext compare; real impl uses BCrypt)
        "__password": "admin",
    }
}

def _mint_token(payload: Dict[str, Any]) -> str:
    """Faux HS256 token: real signature replaced by 'mock'. Shape (3 segments) matches a real JWT
    so the SafeBearerTokenAuthenticationConverter dot-check passes round-trip."""
    header = base64.urlsafe_b64encode(b'{"alg":"HS256","typ":"JWT"}').rstrip(b'=').decode()
    body = base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b'=').decode()
    sig = base64.urlsafe_b64encode(b'mock-signature').rstrip(b'=').decode()
    return f"{header}.{body}.{sig}"

def _decode_token(token: str) -> Optional[Dict[str, Any]]:
    try:
        parts = token.split('.')
        if len(parts) != 3: return None
        pad = '=' * (-len(parts[1]) % 4)
        return json.loads(base64.urlsafe_b64decode(parts[1] + pad).decode())
    except Exception:
        return None

class LoginBody(BaseModel):
    username: str
    password: str

@app.get("/api/v1/auth/config")
def auth_config():
    return {"ssoEnabled": SSO_ENABLED, "ldapEnabled": LDAP_ENABLED,
            "ssoAuthUrl": SSO_AUTH_URL or None}

@app.post("/api/v1/auth/login")
def auth_login(body: LoginBody):
    # 1) Built-in admin bypass
    if body.username == ADMIN_USER and body.password == ADMIN_PASS:
        claims = {"sub": ADMIN_USER, "valkeyry.role": "admin",
                  "valkeyry.tenants": ["*"], "valkeyry.source": "LOCAL",
                  "iss": "valkeyry-local", "exp": int(time.time()) + 28800}
        return {"token": _mint_token(claims), "tokenType": "Bearer", "expiresInSec": 28800,
                "username": ADMIN_USER, "role": "admin", "tenants": ["*"],
                "source": "LOCAL", "mode": "admin"}
    # 2) Local DB user
    u = ADMIN_USERS.get(body.username)
    if u and u.get("source") == "LOCAL" and u.get("enabled") and u.get("__password") == body.password:
        claims = {"sub": u["username"], "valkeyry.role": u["role"],
                  "valkeyry.tenants": u.get("tenants", []), "valkeyry.source": "LOCAL",
                  "iss": "valkeyry-local", "exp": int(time.time()) + 28800}
        return {"token": _mint_token(claims), "tokenType": "Bearer", "expiresInSec": 28800,
                "username": u["username"], "role": u["role"], "tenants": u.get("tenants", []),
                "source": "LOCAL", "mode": "admin" if u["role"] == "admin" else "console"}
    raise HTTPException(status_code=401, detail="Bad credentials")

def _claims_from_header(req: Request) -> Optional[Dict[str, Any]]:
    h = req.headers.get("authorization", "")
    if not h.lower().startswith("bearer "): return None
    return _decode_token(h[7:].strip())

@app.get("/api/v1/auth/me")
def auth_me(req: Request):
    c = _claims_from_header(req)
    if not c: raise HTTPException(status_code=401)
    role = c.get("valkeyry.role", "reader")
    return {"username": c.get("sub", "?"), "role": role,
            "tenants": c.get("valkeyry.tenants", []),
            "source": c.get("valkeyry.source", "LOCAL"),
            "isWriter": role in ("writer", "admin"),
            "isAdmin": role == "admin"}

@app.post("/api/v1/auth/logout", status_code=204)
def auth_logout(): return Response(status_code=204)

def _require_admin(req: Request):
    c = _claims_from_header(req)
    if not c or c.get("valkeyry.role") != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    return c

# ───── Tenants CRUD ─────

class TenantBody(BaseModel):
    id: str
    name: str
    description: Optional[str] = None
    enabled: Optional[bool] = True

@app.get("/api/v1/admin/tenants")
def admin_list_tenants(req: Request):
    _require_admin(req)
    return list(ADMIN_TENANTS.values())

@app.post("/api/v1/admin/tenants", status_code=201)
def admin_create_tenant(body: TenantBody, req: Request):
    c = _require_admin(req)
    if body.id in ADMIN_TENANTS:
        raise HTTPException(status_code=409, detail="Tenant id already exists")
    t = {"id": body.id, "name": body.name, "description": body.description,
         "enabled": body.enabled if body.enabled is not None else True,
         "createdAt": now_iso(), "createdBy": c.get("sub", "?")}
    ADMIN_TENANTS[body.id] = t
    return t

@app.put("/api/v1/admin/tenants/{tid}")
def admin_update_tenant(tid: str, body: TenantBody, req: Request):
    _require_admin(req)
    if tid not in ADMIN_TENANTS: raise HTTPException(status_code=404, detail="No such tenant")
    if body.id != tid: raise HTTPException(status_code=400, detail="Path id must match body id")
    t = ADMIN_TENANTS[tid]
    t["name"] = body.name
    t["description"] = body.description
    if body.enabled is not None: t["enabled"] = body.enabled
    return t

@app.delete("/api/v1/admin/tenants/{tid}", status_code=204)
def admin_delete_tenant(tid: str, req: Request):
    _require_admin(req)
    ADMIN_TENANTS.pop(tid, None)
    return Response(status_code=204)

# ───── Users CRUD ─────

class UserBody(BaseModel):
    username: str
    displayName: Optional[str] = None
    email: Optional[str] = None
    role: Optional[str] = "reader"
    source: Optional[str] = "LOCAL"
    password: Optional[str] = None
    enabled: Optional[bool] = True
    tenants: Optional[List[str]] = None

@app.get("/api/v1/admin/users")
def admin_list_users(req: Request):
    _require_admin(req)
    return [{k: v for k, v in u.items() if not k.startswith("__")} for u in ADMIN_USERS.values()]

@app.post("/api/v1/admin/users", status_code=201)
def admin_create_user(body: UserBody, req: Request):
    c = _require_admin(req)
    if body.username in ADMIN_USERS:
        raise HTTPException(status_code=409, detail="Username already exists")
    src = (body.source or "LOCAL").upper()
    if src == "LOCAL" and not body.password:
        raise HTTPException(status_code=400, detail="Password required for LOCAL users")
    u = {
        "id": str(uuid.uuid4()), "username": body.username,
        "displayName": body.displayName, "email": body.email,
        "role": body.role or "reader", "source": src,
        "enabled": body.enabled if body.enabled is not None else True,
        "tenants": body.tenants or [],
        "createdAt": now_iso(), "createdBy": c.get("sub", "?"),
        "__password": body.password if src == "LOCAL" else None,
    }
    ADMIN_USERS[body.username] = u
    return {k: v for k, v in u.items() if not k.startswith("__")}

@app.put("/api/v1/admin/users/{uid}")
def admin_update_user(uid: str, body: UserBody, req: Request):
    _require_admin(req)
    u = next((v for v in ADMIN_USERS.values() if v["id"] == uid), None)
    if u is None: raise HTTPException(status_code=404, detail="No such user")
    u["displayName"] = body.displayName
    u["email"] = body.email
    if body.role: u["role"] = body.role
    if body.source: u["source"] = body.source.upper()
    if body.enabled is not None: u["enabled"] = body.enabled
    if body.password: u["__password"] = body.password
    if body.tenants is not None: u["tenants"] = body.tenants
    return {k: v for k, v in u.items() if not k.startswith("__")}

@app.delete("/api/v1/admin/users/{uid}", status_code=204)
def admin_delete_user(uid: str, req: Request):
    _require_admin(req)
    victim = next((k for k, v in ADMIN_USERS.items() if v["id"] == uid), None)
    if victim and victim != ADMIN_USER:  # don't let the UI nuke the bypass admin
        ADMIN_USERS.pop(victim)
    return Response(status_code=204)

# ───── Tools converters ─────

def _require_auth(req: Request):
    c = _claims_from_header(req)
    if not c: raise HTTPException(status_code=401)
    return c

from fastapi import UploadFile, File

@app.post("/api/v1/tools/convert/csv")
async def tools_convert_csv(req: Request, file: UploadFile = File(...)):
    _require_auth(req)
    raw = (await file.read()).decode("utf-8", errors="replace")
    reader = csv.reader(io.StringIO(raw))
    rows_iter = list(reader)
    if not rows_iter:
        return {"source": "csv", "fileName": file.filename, "tables": []}
    header = [h.strip() or f"col_{i+1}" for i, h in enumerate(rows_iter[0])]
    rows = []
    for line in rows_iter[1:]:
        row = {}
        for i, col in enumerate(header):
            v = line[i] if i < len(line) else None
            row[col] = None if (v is None or v == "") else v
        rows.append(row)
    name = (file.filename or "table").rsplit(".", 1)[0]
    return {"source": "csv", "fileName": file.filename,
            "tables": [{"tableName": name, "columns": header, "rows": rows}]}

@app.post("/api/v1/tools/convert/xlsx")
async def tools_convert_xlsx(req: Request, file: UploadFile = File(...)):
    _require_auth(req)
    # Mock-grade XLSX: we don't carry openpyxl/POI in the preview pod, so we just
    # surface a 501 explaining how to test against the real Java backend.
    raise HTTPException(status_code=501,
        detail="XLSX conversion is implemented in the Java ToolsController; the Python mock "
               "only proxies CSV and DMN. Run the Java backend to exercise XLSX.")

@app.post("/api/v1/tools/convert/dmn")
async def tools_convert_dmn(req: Request, file: UploadFile = File(...)):
    _require_auth(req)
    raw = await file.read()
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as e:
        raise HTTPException(status_code=400, detail=f"Failed to parse DMN: {e}")
    ns = {"dmn": "https://www.omg.org/spec/DMN/20191111/MODEL/"}
    # Fall back to no-namespace if not OMG namespace
    decisions = root.findall(".//dmn:decision", ns)
    if not decisions: decisions = root.findall(".//decision")
    tables = []
    for d in decisions:
        for dt in d.findall(".//dmn:decisionTable", ns) or d.findall(".//decisionTable"):
            input_names: List[str] = []
            for inp in dt.findall("dmn:input", ns) or dt.findall("input"):
                label = inp.get("label") or inp.get("id")
                input_names.append(label)
            output_names: List[str] = []
            for outp in dt.findall("dmn:output", ns) or dt.findall("output"):
                label = outp.get("label") or outp.get("name") or outp.get("id")
                output_names.append(label)
            cols = [f"in:{n}" for n in input_names] + [f"out:{n}" for n in output_names]
            rows = []
            for rule in dt.findall("dmn:rule", ns) or dt.findall("rule"):
                row: Dict[str, Any] = {"_ruleId": rule.get("id")}
                ies = rule.findall("dmn:inputEntry", ns) or rule.findall("inputEntry")
                for i, ie in enumerate(ies):
                    if i < len(input_names):
                        txt = "".join(t.text or "" for t in (ie.findall("dmn:text", ns) or ie.findall("text"))).strip()
                        row[f"in:{input_names[i]}"] = txt
                oes = rule.findall("dmn:outputEntry", ns) or rule.findall("outputEntry")
                for i, oe in enumerate(oes):
                    if i < len(output_names):
                        txt = "".join(t.text or "" for t in (oe.findall("dmn:text", ns) or oe.findall("text"))).strip()
                        row[f"out:{output_names[i]}"] = txt
                rows.append(row)
            tables.append({"tableName": d.get("name") or d.get("id"),
                           "decisionId": d.get("id"),
                           "hitPolicy": dt.get("hitPolicy", "UNIQUE"),
                           "columns": cols, "rows": rows})
    return {"source": "dmn", "fileName": file.filename, "tables": tables}

# ─────────── Init ───────────

seed()
print("Mock backend ready · seeded tenants:", list(STATE.keys()), flush=True)
