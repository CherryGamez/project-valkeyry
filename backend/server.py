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

# ─────────── Init ───────────

seed()
print("Mock backend ready · seeded tenants:", list(STATE.keys()), flush=True)
