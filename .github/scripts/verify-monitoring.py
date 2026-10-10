#!/usr/bin/env python3
"""Start the packaged monitoring backend/UI and exercise ingestion through its gateway."""
import datetime
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import zipfile

jar = Path(sys.argv[1]).resolve()
with zipfile.ZipFile(jar) as archive:
    manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    sdk = next(line.split(": ", 1)[1] for line in manifest.splitlines() if line.startswith("Fluxzero-SDK-Version:"))

with tempfile.TemporaryDirectory(prefix="monitoring-consumer-") as directory:
    root = Path(directory)
    (root / "pom.xml").write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion><groupId>consumer</groupId><artifactId>monitoring-smoke</artifactId><version>1</version>
      <properties><maven.compiler.release>25</maven.compiler.release></properties>
      <repositories><repository><id>fluxzero</id><url>https://packages.fluxzero.io/maven</url></repository></repositories>
      <dependencies><dependency><groupId>io.fluxzero</groupId><artifactId>sdk</artifactId><version>{sdk}</version></dependency></dependencies>
    </project>''')
    (root / ".fluxzero").mkdir()
    (root / ".fluxzero/dev.yaml").write_text("version: 1\nnamespace: monitoring-smoke\nidp: external\nmonitoring:\n  storage: testserver\n")
    with (root / "server.log").open("w+") as log:
        process = subprocess.Popen(["java", "-jar", str(jar), "--project-dir", str(root),
                                    "--no-compile-on-start", "--no-tests", "--no-watch", "--idp", "external"],
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 180
            session_file = root / ".fluxzero/dev/session.json"
            while time.monotonic() < deadline:
                assert process.poll() is None, "Monitoring consumer exited during startup"
                try:
                    session = json.loads(session_file.read_text())
                    if session["status"] == "running" and session["gateway"]["state"] == "running":
                        break
                    if session["status"] == "failed":
                        raise AssertionError("Monitoring consumer failed to start")
                except (FileNotFoundError, json.JSONDecodeError):
                    pass
                time.sleep(0.25)
            else:
                raise AssertionError("Monitoring did not become ready within 180 seconds")
            base = session["gateway"]["url"]

            def request(path, body=None):
                data = json.dumps(body).encode() if body is not None else None
                req = Request(base + path, data=data, headers={"Content-Type": "application/json",
                              "Origin": base, "X-Fluxzero-Console": "1"})
                try:
                    with urlopen(req, timeout=15) as response:
                        return response.read().decode()
                except HTTPError as error:
                    raise AssertionError(f"{path}: {error.code} {error.read().decode()}") from error

            html = request("/_fluxzero/dev/monitoring/messages")
            assert "window.fluxzeroHost" in html, "Missing Dev Server host contract"
            assert "/_fluxzero/dev/api/monitoring" in html, "Missing monitoring API route"
            api = "/_fluxzero/dev/api/monitoring"
            status = json.loads(request(api + "/logs/local/status"))
            assert status["storage"]["maxRecords"] == 5000, status
            marker = "monitoring release smoke " + root.name
            request(api + "/logs/local/application-logs", [{"application": "smoke", "instance": "smoke-1",
                    "stream": "stdout", "line": marker, "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat()}])
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                result = json.loads(request(api + "/logs/search", {"@class": "io.fluxzero.sdk.common.Message", "metadata": {},
                        "payload": {"@class": "io.fluxzero.auditlog.publishers.api.SearchLog",
                                    "messageTypes": ["CUSTOM"], "term": marker, "facetFilters": [],
                                    "sortableFilters": [], "forceFlush": True}}))
                if any(marker in str(row.get("payload")) for row in result["data"]):
                    break
                time.sleep(0.25)
            else:
                raise AssertionError(f"Ingested application output is not searchable: {result}")
            print(f"Bundled monitoring on SDK {sdk}: host UI, local status, log ingestion and search passed")
        except BaseException:
            log.flush()
            log.seek(0)
            print(log.read(), file=sys.stderr)
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError("Monitoring consumer failed to stop within 20 seconds")
