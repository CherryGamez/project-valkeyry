#!/usr/bin/env python3
"""
Tiny mock HTTP server emulating just the two endpoints the valkeyry-config-plugin
calls — used by the README's "default mock preview backend" so the examples are
runnable offline.

Endpoints:
  POST /api/v1/tenants/{tenant}/tables                          → 201 declare
  POST /api/v1/tenants/{tenant}/tables/{name}/entries:batch     → 201 ingest

Auth:
  X-API-Key: plugin-test-key   (anything goes; we just echo what we received)

Run:
  python3 mock_valkeyry_config.py 8081
"""
import json
import sys
import uuid
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, HTTPServer

STORAGE = defaultdict(dict)
CONFIG_VERSION = {"v": 0}


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, status: int, body: dict) -> None:
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body_bytes = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(body_bytes)
        except json.JSONDecodeError:
            self._send_json(400, {"error": "invalid json"})
            return

        path = self.path
        if path.endswith("/entries:batch"):
            table = path.rsplit("/", 2)[-2]
            bucket = STORAGE[table]
            submitted = inserted = duplicates = 0
            for entry in body.get("entries", []):
                submitted += 1
                key = entry.get("recordKey")
                if key in bucket:
                    duplicates += 1
                else:
                    bucket[key] = entry.get("data")
                    inserted += 1
            self._send_json(
                201,
                {
                    "submitted": submitted,
                    "inserted": inserted,
                    "duplicates": duplicates,
                    "inserted_views": [
                        {"recordKey": k, "version": 1} for k in list(bucket)[-inserted:]
                    ],
                },
            )
            return
        if path.endswith("/tables"):
            CONFIG_VERSION["v"] += 1
            self._send_json(
                201,
                {
                    "id": str(uuid.uuid4()),
                    "tableName": body.get("tableName"),
                    "configVersion": CONFIG_VERSION["v"],
                    "schema": body.get("schema"),
                },
            )
            return
        self._send_json(404, {"error": f"no route for {path}"})

    def log_message(self, fmt, *args):  # quiet
        sys.stderr.write("[mock] %s — %s\n" % (self.address_string(), fmt % args))


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
    httpd = HTTPServer(("127.0.0.1", port), Handler)
    print(f"[mock] valkeyry-config mock listening on http://127.0.0.1:{port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
