#!/usr/bin/env python3
"""Exercise the downloaded distribution without the producer's classpath or user state."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import zipfile
from urllib.request import Request, urlopen

jar = Path(sys.argv[1]).resolve()
version = sys.argv[2]
with zipfile.ZipFile(jar) as archive:
    manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    assert "Main-Class: io.fluxzero.devserver.DevServerMain\r\n" in manifest
    assert f"Implementation-Version: {version}\r\n" in manifest
    assert "Fluxzero-SDK-Version: " in manifest
assert subprocess.check_output(["java", "-jar", str(jar), "--version"], text=True, timeout=15).strip() == (
    f"Fluxzero Dev Server {version}"
)
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    project = root / "project"
    project.mkdir()
    with (root / "server.log").open("w+") as log:
        process = subprocess.Popen([
            "java", f"-Duser.home={root}", "-jar", str(jar),
            "--project-dir", str(project), "--idp", "external",
        ], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            session_file = project / ".fluxzero/dev/session.json"
            while time.monotonic() < deadline:
                assert process.poll() is None, "Standalone server exited before readiness"
                try:
                    session = json.loads(session_file.read_text())
                    if session["status"] == "running" and session["mcp"]["state"] == "running":
                        break
                except (FileNotFoundError, json.JSONDecodeError):
                    pass
                time.sleep(0.1)
            else:
                raise AssertionError("Standalone server did not become ready")
            assert session["projectDirectory"] == str(project)
            for service in ("runtime", "proxy", "idp", "compile"):
                assert session[service]["state"] == "waiting-for-project", session[service]
            token = (project / ".fluxzero/dev/mcp-token").read_text().strip()
            request = Request(session["mcp"]["url"], method="POST", headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/json, text/event-stream",
                "Content-Type": "application/json",
            }, data=json.dumps({
                "jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                           "clientInfo": {"name": "release-consumer", "version": "1"}},
            }).encode())
            with urlopen(request, timeout=10) as response:
                assert response.status == 200
                payload = response.read().decode()
                if payload.startswith("event:") or payload.startswith("data:"):
                    payload = next(line[5:].strip() for line in payload.splitlines()
                                   if line.startswith("data:"))
                result = json.loads(payload)["result"]
                assert result["serverInfo"]["name"] == "fluxzero-dev"
                assert result["serverInfo"]["version"] == version
            print("Standalone server started and answered an authenticated MCP initialize request")
        except BaseException:
            log.flush()
            log.seek(0)
            print(log.read(), file=sys.stderr)
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError("Standalone server failed to stop within 10 seconds")
        session = json.loads(session_file.read_text())
        assert session["status"] == "stopped", session["status"]
        print("Standalone server stopped and persisted the stopped session")
