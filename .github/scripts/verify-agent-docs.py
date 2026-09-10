#!/usr/bin/env python3
"""Verify SDK archives through standalone stdio and optionally the real fz/plugin startup path, without publication."""

import argparse
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import zipfile


class Client:
    def __init__(self, process):
        self.process, self.counter, self.messages = process, 0, queue.Queue()
        def read():
            for line in process.stdout:
                try:
                    self.messages.put(json.loads(line))
                except json.JSONDecodeError:
                    self.messages.put({"invalid_stdout": line})
            self.messages.put({"eof": True})
        threading.Thread(target=read, daemon=True).start()
        self.rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                "clientInfo": {"name": "agent-docs-consumer", "version": "1"}})
        self.rpc("notifications/initialized", {}, notification=True)

    def rpc(self, method, params, notification=False):
        self.counter += 1
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            message["id"] = self.counter
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()
        if notification:
            return
        deadline = time.monotonic() + 90
        while True:
            result = self.messages.get(timeout=max(.01, deadline - time.monotonic()))
            assert "invalid_stdout" not in result and "eof" not in result, result
            if result.get("id") != self.counter:
                continue
            assert "error" not in result, result
            return result["result"]

    def call(self, name, **arguments):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})
        assert not result.get("isError"), result
        return result["structuredContent"]


@contextmanager
def bridge(command, directory, environment, log_file):
    with log_file.open("w+") as log:
        process = subprocess.Popen(command, cwd=directory, env=environment, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=log, text=True, bufsize=1)
        try:
            yield Client(process)
        except BaseException:
            log.flush()
            log.seek(0)
            print(log.read())
            raise
        finally:
            if not process.stdin.closed:
                process.stdin.close()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                raise AssertionError("Closing stdin did not stop the CLI/bridge within 15 seconds")
        assert process.returncode == 0, f"Bridge exit {process.returncode}: {log_file.read_text()}"


def check(client, release, manifest, archive, source, all_articles=True):
    selectors = {"namespace": "sdk", "version": release["componentVersion"]}
    start = client.call("docs_start", **selectors)
    assert start["status"] == "ready" and start["source"] == source, start
    assert start["articleCount"] == len(manifest["articles"])
    for field in ("namespace", "sourceCommit", "contentHash"):
        assert start[field] == release[field], field
    tools = client.rpc("tools/list", {})["tools"]
    assert len(tools) == 10
    article = next(a for a in manifest["articles"] if a["symbols"])
    matches = client.call("docs_lookup_symbol", **selectors, symbol=article["symbols"][0], limit=20)
    assert any(a["path"] == article["path"] for a in matches["results"])
    assert client.call("docs_search", **selectors, query=article["title"][:256])["results"]
    for article in manifest["articles"] if all_articles else [article]:
        content, offset = "", 0
        while True:
            page = client.call("docs_read", **selectors, path=article["path"], offset=offset, maxChars=12000)
            assert len(page["content"].encode("utf-16-le")) // 2 <= 12000
            content += page["content"]
            if not page["hasMore"]:
                break
            assert page["nextOffset"] > offset
            offset = page["nextOffset"]
        assert content == archive.read(article["source"]).decode("utf-8"), article["path"]
    links = client.call("docs_links", **selectors, path=manifest["root"])["links"]
    assert all(link["namespace"] == "sdk" and link["version"] == selectors["version"] for link in links)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--download", action="store_true", help="Serve the supplied release ZIP over local HTTP")
    parser.add_argument("--fz", type=Path, help="Also test CLI start/reuse/stop with this executable")
    parser.add_argument("--plugin-config", type=Path, help="Use the generated plugin's stdio command arguments")
    args = parser.parse_args()
    jar, path = args.jar.resolve(), args.archive.resolve()
    with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory(prefix="agent-docs-consumer-") as temporary:
        root = Path(temporary)
        release, manifest = (json.loads(archive.read(name)) for name in ("release.json", "manifest.json"))
        version = release["componentVersion"]
        if args.download:
            assert not version.upper().endswith("-SNAPSHOT"), "Download test requires a release-style version"
        artifact = f"/maven/io/fluxzero/fluxzero-sdk-java/{version}/fluxzero-sdk-java-{version}-agent-docs.zip"
        metadata_path = "/maven/io/fluxzero/fluxzero-sdk-java/maven-metadata.xml"
        data, calls = path.read_bytes(), []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path)
                if self.path == metadata_path:
                    body = f"<metadata><versioning><release>{version}</release></versioning></metadata>".encode()
                elif self.path in (artifact, artifact + ".sha256"):
                    body = hashlib.sha256(data).hexdigest().encode() if self.path.endswith(".sha256") else data
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass

        http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        environment = {k: v for k, v in os.environ.items() if not k.startswith("FLUXZERO_")}
        environment.update(FLUXZERO_DEV_DOCS_CACHE_DIRECTORY=str(root / "docs-cache"),
                           FLUXZERO_DEV_DOCS_REPOSITORY=f"http://127.0.0.1:{http.server_port}/maven/")
        # Isolate the dev registry; do not change the real agent installation or global running environments.
        environment["JAVA_TOOL_OPTIONS"] = f"-Dfluxzero.dev.registryDirectory={root / 'registry'}"
        if not args.download:
            environment["FLUXZERO_DEV_DOCS_SDK_ARCHIVE"] = str(path)
        base = ["java", "-cp", str(jar), "io.fluxzero.devserver.DevMcpStdioMain"]
        cli_version = "1.999.0"  # Private cache alias for this explicitly supplied, unpublished local build.
        if args.fz:
            cache = root / "dev-cache"
            directory = cache / cli_version
            directory.mkdir(parents=True)
            target = directory / f"fluxzero-dev-server-{cli_version}-standalone.jar"
            shutil.copyfile(jar, target)
            target.with_suffix(".jar.sha256").write_text(hashlib.sha256(target.read_bytes()).hexdigest())
            environment["FLUXZERO_DEV_SERVER_CACHE"] = str(cache)
            mcp_args = ["mcp"]
            if args.plugin_config:
                config = json.loads(args.plugin_config.read_text())["mcpServers"]
                assert set(config) == {"fluxzero-dev"}, config
                assert config["fluxzero-dev"]["command"] == "fz"
                mcp_args = config["fluxzero-dev"]["args"]
                assert mcp_args == ["mcp"], mcp_args
            base = [str(args.fz.resolve()), *mcp_args, "--dev-server-version", cli_version]
        workspace = root / "workspace"
        workspace.mkdir()
        (workspace / "brief.md").write_text("An existing non-project directory")
        command = [*base, "--project-dir", str(workspace)]
        try:
            with bridge(command, workspace, environment, root / "first.log") as client:
                status = client.call("get_status")
                assert status["status"] == "dev-server-not-running", status
                assert status["start"]["args"] == ["mcp", "--ensure-dev", "--project-dir", str(workspace)]
                if args.download:
                    latest = client.call("docs_start")
                    assert latest["selection"] == "latest-release" and latest["version"] == version, latest
                check(client, release, manifest, archive, "cache" if args.download else "local-archive")
                assert not (workspace / ".fluxzero/dev/session.json").exists()
                (workspace / "pom.xml").write_text(f'''<project><modelVersion>4.0.0</modelVersion>
<groupId>local.test</groupId><artifactId>docs-consumer</artifactId><version>1</version><dependencies>
<dependency><groupId>io.fluxzero</groupId><artifactId>sdk</artifactId><version>{version}</version>
</dependency></dependencies></project>''')
                assert client.call("docs_start")["selection"] == "project-sdk"
                (workspace / "pom.xml").unlink()
            assert len(calls) == (3 if args.download else 0), calls
        finally:
            http.shutdown()
            http.server_close()
            thread.join(timeout=5)
        environment.pop("FLUXZERO_DEV_DOCS_SDK_ARCHIVE", None)
        with bridge(command, workspace, environment, root / "offline.log") as client:
            check(client, release, manifest, archive, "cache")
            if args.download:
                cached_metadata = root / "docs-cache/sdk/latest.json"
                record = json.loads(cached_metadata.read_text())
                record["checkedAt"] = "2020-01-01T00:00:00Z"
                cached_metadata.write_text(json.dumps(record))
                assert client.call("docs_start")["releaseResolution"]["source"] == "offline"
        if args.fz:
            rejected = subprocess.run([*command, "--ensure-dev"], cwd=workspace, env=environment,
                                      input="", capture_output=True, text=True, timeout=30)
            assert rejected.returncode != 0, "A non-project directory with existing content must not start a dev server"
            assert not (workspace / ".fluxzero/dev/session.json").exists()
            project = root / "greenfield"
            project.mkdir()
            default = [*base, "--project-dir", str(project)]
            ensure = [*default, "--ensure-dev"]
            stop = [str(args.fz.resolve()), "dev", "stop", "--project-dir", str(project),
                    "--dev-server-version", cli_version]
            try:
                with bridge(default, project, environment, root / "waiting.log") as waiting:
                    assert waiting.call("get_status")["status"] == "dev-server-not-running"
                    bootstrapped = subprocess.run(ensure, cwd=project, env=environment, input="",
                                                  capture_output=True, text=True, timeout=45)
                    assert bootstrapped.returncode == 0, bootstrapped.stderr
                    initial = waiting.call("get_status")["session"]
                    session_id, pid = initial["sessionId"], initial["pid"]
                    for attempt in range(2):
                        with bridge(ensure, project, environment, root / f"ensure-{attempt}.log") as started:
                            status = started.call("get_status")
                            session = status["session"]
                            assert session["status"] == "running", status
                            assert session["runtime"]["state"] == "waiting-for-project", status
                            if session_id is not None:
                                assert session["sessionId"] == session_id and session["pid"] == pid
                            session_id, pid = session["sessionId"], session["pid"]
                            assert waiting.call("get_status")["session"]["sessionId"] == session_id
                            check(started, release, manifest, archive, "cache", all_articles=False)
                        os.kill(pid, 0)  # Closing a bridge must leave the intentionally detached dev server alive.
                    stopped = subprocess.run(stop, cwd=project, env=environment, capture_output=True, text=True, timeout=30)
                    assert stopped.returncode == 0, stopped.stderr
                    assert waiting.call("get_status")["status"] == "dev-server-not-running"
                    check(waiting, release, manifest, archive, "cache", all_articles=False)
            finally:
                subprocess.run(stop, cwd=project, env=environment, capture_output=True, timeout=30)
            assert not (project / ".fluxzero/dev/mcp-token").exists()
            session = json.loads((project / ".fluxzero/dev/session.json").read_text())
            assert session["status"] == "stopped", session
        print(f"Verified all {len(manifest['articles'])} articles for sdk/{version}: exact stdio retrieval, "
              f"{'HTTP download/latest fallback' if args.download else 'local archive'}, project detection, "
              f"offline restart, EOF shutdown" + (", plugin CLI startup and --ensure-dev start/reuse/stop" if args.fz else ""))


if __name__ == "__main__":
    main()
