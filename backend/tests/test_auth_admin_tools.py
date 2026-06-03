"""Backend tests for Valkeyry mock: auth, admin, tools converters."""
import os
import io
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "").rstrip("/")
if not BASE_URL:
    # Fallback to frontend/.env if env var not present in shell
    try:
        with open("/app/frontend/.env") as f:
            for line in f:
                if line.startswith("REACT_APP_BACKEND_URL="):
                    BASE_URL = line.split("=", 1)[1].strip().rstrip("/")
                    break
    except Exception:
        pass


@pytest.fixture(scope="session")
def s():
    return requests.Session()


@pytest.fixture(scope="session")
def admin_token(s):
    r = s.post(f"{BASE_URL}/api/v1/auth/login",
               json={"username": "admin", "password": "admin"}, timeout=10)
    assert r.status_code == 200, r.text
    tok = r.json()["token"]
    return tok


@pytest.fixture(scope="session")
def admin_headers(admin_token):
    return {"Authorization": f"Bearer {admin_token}"}


# ─────────────── /api/v1/auth/config ───────────────
def test_auth_config_defaults(s):
    r = s.get(f"{BASE_URL}/api/v1/auth/config", timeout=10)
    assert r.status_code == 200
    data = r.json()
    assert data["ssoEnabled"] is False
    assert data["ldapEnabled"] is False


# ─────────────── /api/v1/auth/login ───────────────
def test_auth_login_admin_ok(s):
    r = s.post(f"{BASE_URL}/api/v1/auth/login",
               json={"username": "admin", "password": "admin"}, timeout=10)
    assert r.status_code == 200
    d = r.json()
    assert d["role"] == "admin"
    assert d["mode"] == "admin"
    assert d["tenants"] == ["*"]
    # JWT must have exactly 2 dot separators (3 segments)
    tok = d["token"]
    assert tok.count(".") == 2, f"token shape wrong: {tok!r}"
    segs = tok.split(".")
    assert all(len(seg) > 0 for seg in segs)


def test_auth_login_bad_credentials(s):
    r = s.post(f"{BASE_URL}/api/v1/auth/login",
               json={"username": "admin", "password": "wrong"}, timeout=10)
    assert r.status_code == 401


# ─────────────── /api/v1/auth/me ───────────────
def test_auth_me_admin(s, admin_headers):
    r = s.get(f"{BASE_URL}/api/v1/auth/me", headers=admin_headers, timeout=10)
    assert r.status_code == 200
    d = r.json()
    assert d["username"] == "admin"
    assert d["role"] == "admin"
    assert d["isAdmin"] is True


def test_auth_logout_204(s):
    r = s.post(f"{BASE_URL}/api/v1/auth/logout", timeout=10)
    assert r.status_code == 204


# ─────────────── Tenants admin CRUD ───────────────
def test_admin_tenants_requires_auth(s):
    r = s.get(f"{BASE_URL}/api/v1/admin/tenants", timeout=10)
    assert r.status_code in (401, 403)


def test_admin_tenants_list_has_demo(s, admin_headers):
    r = s.get(f"{BASE_URL}/api/v1/admin/tenants", headers=admin_headers, timeout=10)
    assert r.status_code == 200
    ids = [t["id"] for t in r.json()]
    assert "demo-tenant" in ids


def test_admin_tenants_crud(s, admin_headers):
    # Use unique id to avoid clashing if test rerun without restart
    tid = "TEST_acme"
    # Cleanup if leftover
    s.delete(f"{BASE_URL}/api/v1/admin/tenants/{tid}", headers=admin_headers, timeout=10)

    r = s.post(f"{BASE_URL}/api/v1/admin/tenants",
               headers=admin_headers,
               json={"id": tid, "name": "ACME"}, timeout=10)
    assert r.status_code == 201, r.text
    assert r.json()["name"] == "ACME"

    # Conflict on dup
    r2 = s.post(f"{BASE_URL}/api/v1/admin/tenants",
                headers=admin_headers,
                json={"id": tid, "name": "ACME"}, timeout=10)
    assert r2.status_code == 409

    # Update
    r3 = s.put(f"{BASE_URL}/api/v1/admin/tenants/{tid}",
               headers=admin_headers,
               json={"id": tid, "name": "ACME Inc", "description": "updated"},
               timeout=10)
    assert r3.status_code == 200
    assert r3.json()["name"] == "ACME Inc"
    assert r3.json()["description"] == "updated"

    # Delete
    r4 = s.delete(f"{BASE_URL}/api/v1/admin/tenants/{tid}",
                  headers=admin_headers, timeout=10)
    assert r4.status_code == 204

    # Verify removal
    r5 = s.get(f"{BASE_URL}/api/v1/admin/tenants", headers=admin_headers, timeout=10)
    ids = [t["id"] for t in r5.json()]
    assert tid not in ids


# ─────────────── Users / role-gating ───────────────
def test_create_writer_user_and_login(s, admin_headers):
    # Ensure acme tenant exists for bob
    s.post(f"{BASE_URL}/api/v1/admin/tenants", headers=admin_headers,
           json={"id": "acme", "name": "ACME"}, timeout=10)

    # Cleanup bob if exists from previous run by listing users + deleting
    list_r = s.get(f"{BASE_URL}/api/v1/admin/users", headers=admin_headers, timeout=10)
    for u in list_r.json():
        if u["username"] == "bob":
            s.delete(f"{BASE_URL}/api/v1/admin/users/{u['id']}",
                     headers=admin_headers, timeout=10)
            break

    r = s.post(f"{BASE_URL}/api/v1/admin/users", headers=admin_headers,
               json={"username": "bob", "password": "p@ssw0rd",
                     "role": "writer", "source": "LOCAL", "tenants": ["acme"]},
               timeout=10)
    assert r.status_code == 201, r.text
    assert r.json()["role"] == "writer"

    # Login as bob
    lr = s.post(f"{BASE_URL}/api/v1/auth/login",
                json={"username": "bob", "password": "p@ssw0rd"}, timeout=10)
    assert lr.status_code == 200, lr.text
    d = lr.json()
    assert d["role"] == "writer"
    assert d["mode"] == "console"
    bob_token = d["token"]

    # Bob hitting admin endpoint should be 403
    r2 = s.get(f"{BASE_URL}/api/v1/admin/tenants",
               headers={"Authorization": f"Bearer {bob_token}"}, timeout=10)
    assert r2.status_code == 403


# ─────────────── Tools converters ───────────────
def test_tools_convert_csv(s, admin_headers):
    csv_data = b"name,age\nAlice,30\nBob,25\n"
    files = {"file": ("people.csv", io.BytesIO(csv_data), "text/csv")}
    r = s.post(f"{BASE_URL}/api/v1/tools/convert/csv",
               headers=admin_headers, files=files, timeout=15)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["source"] == "csv"
    assert len(d["tables"]) == 1
    tbl = d["tables"][0]
    assert tbl["columns"] == ["name", "age"]
    assert len(tbl["rows"]) == 2
    assert tbl["rows"][0]["name"] == "Alice"


def test_tools_convert_xlsx_returns_501(s, admin_headers):
    files = {"file": ("x.xlsx", io.BytesIO(b"fake"), "application/octet-stream")}
    r = s.post(f"{BASE_URL}/api/v1/tools/convert/xlsx",
               headers=admin_headers, files=files, timeout=15)
    assert r.status_code == 501


def test_tools_convert_dmn(s, admin_headers):
    dmn = b"""<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/">
  <decision id="d1" name="Grade">
    <decisionTable hitPolicy="UNIQUE">
      <input label="score"><inputExpression><text>score</text></inputExpression></input>
      <output label="grade"/>
      <rule id="r1">
        <inputEntry><text>&gt;=90</text></inputEntry>
        <outputEntry><text>"A"</text></outputEntry>
      </rule>
      <rule id="r2">
        <inputEntry><text>&lt;90</text></inputEntry>
        <outputEntry><text>"B"</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>"""
    files = {"file": ("g.dmn", io.BytesIO(dmn), "application/xml")}
    r = s.post(f"{BASE_URL}/api/v1/tools/convert/dmn",
               headers=admin_headers, files=files, timeout=15)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["source"] == "dmn"
    assert len(d["tables"]) == 1
    tbl = d["tables"][0]
    assert tbl["tableName"] == "Grade"
    assert "in:score" in tbl["columns"]
    assert "out:grade" in tbl["columns"]
    assert len(tbl["rows"]) == 2
    assert tbl["rows"][0]["_ruleId"] == "r1"


# ─────────────── Static pages ───────────────
def test_login_page_serves(s):
    r = s.get(f"{BASE_URL}/login.html", timeout=10)
    assert r.status_code == 200
    body = r.text
    for testid in ("login-username", "login-password", "login-submit-btn"):
        assert f'data-testid="{testid}"' in body, f"missing {testid}"


def test_auth_js_asset(s):
    r = s.get(f"{BASE_URL}/assets/auth.js", timeout=10)
    assert r.status_code == 200
