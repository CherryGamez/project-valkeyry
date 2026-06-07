#!/usr/bin/env python3
"""
End-to-end regression test for the four runnable Maven / Gradle examples.

For each example we:
  1. start the bundled mock valkeyry-config server on a free port,
  2. run the build (`mvn valkeyry-config:push` or `gradle <subproject>:valkeyryConfigPush`),
     overriding `VALKEYRY_ENDPOINT` so it talks to the mock,
  3. assert the build exits 0 and emits the expected
       `submitted=X inserted=Y duplicates=Z`
     line for that example.

Run from the plugin root:

    cd /app/valkeyry-config-plugin
    python3 examples/test_examples.py

Prerequisites already satisfied by the parent build:
    mvn clean install     in  /app/valkeyry-config-plugin
    mvn -N install        in  /app                            (reactor parent)
"""
from __future__ import annotations

import os
import re
import socket
import subprocess
import sys
import time
import unittest
from contextlib import closing
from pathlib import Path
from typing import Tuple

HERE = Path(__file__).resolve().parent           # examples/
PLUGIN_ROOT = HERE.parent                        # valkeyry-config-plugin/
JAVA_HOME = os.environ.get("JAVA_HOME", "/opt/jdk21")
MAVEN_BIN = os.environ.get("MVN_BIN", "/opt/apache-maven-3.9.9/bin/mvn")
GRADLE_BIN = os.environ.get("GRADLE_BIN", "/opt/gradle-8.10/bin/gradle")


def _free_port() -> int:
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _start_mock() -> Tuple[subprocess.Popen, int]:
    port = _free_port()
    proc = subprocess.Popen(
        [sys.executable, str(HERE / "mock_valkeyry_config.py"), str(port)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    # Wait until the server is accepting connections (max 5 s).
    for _ in range(50):
        time.sleep(0.1)
        try:
            with closing(socket.create_connection(("127.0.0.1", port), timeout=0.2)):
                return proc, port
        except OSError:
            continue
    proc.kill()
    raise RuntimeError("mock did not come up")


def _expected_pattern(submitted: int, inserted: int) -> re.Pattern[str]:
    # duplicates may differ across runs against a fresh mock, so we only pin submitted/inserted.
    return re.compile(
        rf"valkeyry-config push complete — submitted={submitted} inserted={inserted} duplicates=\d+"
    )


class ExampleProjectsTest(unittest.TestCase):
    # (sub-folder, expected submitted, expected inserted on first run)
    MAVEN_CASES = [
        ("01-flat-feature-flags",      3, 3),
        ("02-inline-product-catalog",  2, 2),
        ("03-multi-table",             5, 5),
        ("04-env-driven",              1, 1),
    ]
    # For Gradle we re-use the same canonical YAMLs, so submitted/inserted match.
    GRADLE_CASES = MAVEN_CASES

    def setUp(self) -> None:
        self.env = {
            **os.environ,
            "JAVA_HOME": JAVA_HOME,
            "PATH": f"{JAVA_HOME}/bin:{os.environ.get('PATH', '')}",
            "VALKEYRY_TENANT": "demo-tenant",
            "VALKEYRY_API_KEY": "plugin-test-key",
        }

    def _run_with_fresh_mock(self, cwd: Path, cmd: list[str], timeout: int) -> subprocess.CompletedProcess:
        proc, port = _start_mock()
        try:
            env = {**self.env, "VALKEYRY_ENDPOINT": f"http://127.0.0.1:{port}"}
            return subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()
            finally:
                if proc.stdout:
                    proc.stdout.close()
                if proc.stderr:
                    proc.stderr.close()

    # ─────────────────────────  Maven  ─────────────────────────
    def test_maven_examples(self) -> None:
        for sub, submitted, inserted in self.MAVEN_CASES:
            with self.subTest(maven=sub):
                cwd = HERE / "maven" / sub
                result = self._run_with_fresh_mock(
                    cwd, [MAVEN_BIN, "-B", "valkeyry-config:push"], timeout=120
                )
                self.assertEqual(
                    result.returncode, 0,
                    msg=f"Maven {sub} failed:\n{result.stdout}\n---STDERR---\n{result.stderr}",
                )
                self.assertRegex(result.stdout, _expected_pattern(submitted, inserted))

    # ─────────────────────────  Gradle  ─────────────────────────
    def test_gradle_examples(self) -> None:
        gradle_root = HERE / "gradle"
        for sub, submitted, inserted in self.GRADLE_CASES:
            with self.subTest(gradle=sub):
                result = self._run_with_fresh_mock(
                    gradle_root,
                    [GRADLE_BIN, f":{sub}:valkeyryConfigPush", "--console=plain", "--no-daemon"],
                    timeout=180,
                )
                self.assertEqual(
                    result.returncode, 0,
                    msg=f"Gradle {sub} failed:\n{result.stdout}\n---STDERR---\n{result.stderr}",
                )
                self.assertRegex(result.stdout, _expected_pattern(submitted, inserted))


if __name__ == "__main__":
    unittest.main(verbosity=2)
