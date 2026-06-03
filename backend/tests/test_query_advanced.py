"""Tests for /api/v1/tenants/{tenantId}/query2 (multi-condition advanced query) and
backward-compat for the legacy POST /query endpoint.

Seeded data (see /app/backend/server.py::seed()):
  tenant=demo-tenant, table=users:
    - alice@acme.io  role=admin   tags=[internal,vip]   joinedAt=2024-04-01
    - bob@acme.io    role=editor  tags=[ga]             joinedAt=2024-05-10
    - carol@acme.io  role=viewer  tags=[beta]           joinedAt=2024-06-21
"""
import os
import pytest
import requests

BASE_URL = os.environ["REACT_APP_BACKEND_URL"].rstrip("/")
TENANT = "demo-tenant"
Q2 = f"{BASE_URL}/api/v1/tenants/{TENANT}/query2"
Q1 = f"{BASE_URL}/api/v1/tenants/{TENANT}/query"


@pytest.fixture
def client():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    return s


# ───────────────────────── /query2 multi-condition ─────────────────────────

class TestQuery2SingleCondition:
    def test_endswith_email_returns_three(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.email", "op": "endsWith", "value": "@acme.io"}
        ]})
        assert r.status_code == 200, r.text
        rows = r.json()
        assert len(rows) == 3
        for row in rows:
            assert str(row["data"]["email"]).endswith("@acme.io")

    def test_startswith_case_insensitive(self, client):
        # 'al' should match 'alice' regardless of casing
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.email", "op": "startsWith", "value": "AL"}
        ]})
        assert r.status_code == 200
        rows = r.json()
        keys = {row["recordKey"] for row in rows}
        assert keys == {"alice@acme.io"}

    def test_isnotnull_tags(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.tags", "op": "isNotNull"}
        ]})
        assert r.status_code == 200
        assert len(r.json()) == 3  # all three seeded users have tags


class TestQuery2MultiCondition:
    def test_and_endswith_and_in(self, client):
        # email endsWith @acme.io AND role in [admin, editor] → alice + bob
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.email", "op": "endsWith", "value": "@acme.io"},
            {"field": "data.role",  "op": "in", "value": ["admin", "editor"],
             "connector": "AND"},
        ]})
        assert r.status_code == 200, r.text
        rows = r.json()
        keys = {r2["recordKey"] for r2 in rows}
        assert keys == {"alice@acme.io", "bob@acme.io"}

    def test_or_connector_role_admin_or_viewer(self, client):
        # role=admin OR role=viewer → alice + carol
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.role", "op": "equals", "value": "admin"},
            {"field": "data.role", "op": "equals", "value": "viewer",
             "connector": "OR"},
        ]})
        assert r.status_code == 200, r.text
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"alice@acme.io", "carol@acme.io"}


class TestQuery2InOperator:
    def test_in_array_form(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.role", "op": "in", "value": ["admin", "editor"]}
        ]})
        assert r.status_code == 200
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"alice@acme.io", "bob@acme.io"}

    def test_in_csv_form(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.role", "op": "in", "value": "admin, editor"}
        ]})
        assert r.status_code == 200
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"alice@acme.io", "bob@acme.io"}

    def test_notin_csv(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.role", "op": "notIn", "value": "admin"}
        ]})
        assert r.status_code == 200
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"bob@acme.io", "carol@acme.io"}


class TestQuery2OtherOps:
    def test_contains(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.fullName", "op": "contains", "value": "lic"}
        ]})
        assert r.status_code == 200
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"alice@acme.io"}

    def test_between_dates(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.joinedAt", "op": "betweenDates",
             "value": "2024-04-15", "value2": "2024-05-31"}
        ]})
        assert r.status_code == 200
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"bob@acme.io"}

    def test_regex(self, client):
        r = client.post(Q2, json={"table": "users", "conditions": [
            {"field": "data.email", "op": "regex", "value": "^a.*@acme"}
        ]})
        assert r.status_code == 200
        keys = {row["recordKey"] for row in r.json()}
        assert keys == {"alice@acme.io"}

    def test_missing_table_400(self, client):
        r = client.post(Q2, json={"conditions": []})
        assert r.status_code == 400


# ───────────────────────── /query backward-compat ─────────────────────────

class TestQueryLegacyCompat:
    def test_legacy_equals(self, client):
        r = client.post(Q1, json={
            "table": "users", "op": "equals",
            "field": "data.role", "value": "admin",
        })
        assert r.status_code == 200, r.text
        rows = r.json()
        keys = {row["recordKey"] for row in rows}
        assert keys == {"alice@acme.io"}

    def test_legacy_endpoint_still_exists(self, client):
        # Sanity: legacy endpoint must not 404 after introducing /query2.
        r = client.post(Q1, json={
            "table": "users", "op": "contains",
            "field": "data.email", "value": "acme",
        })
        assert r.status_code == 200
        assert len(r.json()) == 3
