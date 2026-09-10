#!/usr/bin/env python3
"""Exercise packaged old/new CLI and dev-server compatibility on the current operating system.

Supply the latest build and a published distribution predating standalone docs/start_dev.
All sessions, artifact pins and registry entries are isolated. Logs remain in the printed
scratch directory, and each environment is stopped even when an assertion fails.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("old-jar", "new-jar", "old-fz", "new-fz"):
        parser.add_argument("--" + argument, type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("consumer", Path(__file__).with_name("verify-agent-docs.py"))
    consumer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(consumer)
    new_jar, old_jar = args.new_jar.resolve(), args.old_jar.resolve()
    clis = {"old": str(args.old_fz.resolve()), "new": str(args.new_fz.resolve())}
    root = Path(tempfile.mkdtemp(prefix="bootstrap-matrix-"))
    print("Logs and isolated test state:", root, flush=True)
    environment = {k: v for k, v in os.environ.items() if not k.startswith("FLUXZERO_")}
    environment.update(FLUXZERO_DEV_SERVER_CACHE=str(root / "cache"),
                       JAVA_TOOL_OPTIONS=f"-Dfluxzero.dev.registryDirectory={root / 'registry'}")
    # Private cache aliases avoid modifying the user's installed distributions or resolving remote artifacts.
    versions = {"1.998.0": old_jar, "1.999.0": new_jar}
    for version, jar in versions.items():
        directory = root / "cache" / version
        directory.mkdir(parents=True)
        target = directory / f"fluxzero-dev-server-{version}-standalone.jar"
        shutil.copyfile(jar, target)
        target.with_suffix(".jar.sha256").write_text(hashlib.sha256(target.read_bytes()).hexdigest())

    # Run from the private copies too, so a concurrent developer rebuild cannot remove the tested JAR.
    new_jar = root / "cache/1.999.0/fluxzero-dev-server-1.999.0-standalone.jar"
    old_jar = root / "cache/1.998.0/fluxzero-dev-server-1.998.0-standalone.jar"

    @contextmanager
    def bridge(command, project, label):
        with (root / f"{label}.log").open("w") as log:
            process = subprocess.Popen(command, cwd=project, env=environment, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=log, text=True)
            try:
                yield consumer.Client(process)
            finally:
                process.stdin.close()
                try:
                    process.wait(timeout=4)
                except subprocess.TimeoutExpired:
                    # Historical bridges predate bounded EOF shutdown; terminate them explicitly.
                    process.terminate()
                    process.wait(timeout=10)

    def ensure(cli, version, project):
        return [clis[cli], "mcp", "--dev-server-version", version,
                "--project-dir", str(project), "--ensure-dev"]

    def direct(jar, project):
        return ["java", "-cp", str(jar), "io.fluxzero.devserver.DevMcpStdioMain", "--project-dir", str(project)]

    def stop(project):
        stopped = subprocess.run(["java", "-cp", str(new_jar), "io.fluxzero.devserver.DevServerControlMain",
                                  "stop", "--project-dir", str(project)], env=environment,
                                 capture_output=True, text=True, timeout=30)
        assert stopped.returncode == 0, stopped.stderr

    for cli in clis:
        for version in versions:
            label = f"{cli}-{version}"
            project = root / label
            project.mkdir()
            try:
                with bridge(ensure(cli, version, project), project, label) as client:
                    session = client.call("get_status")["session"]
                    assert session["status"] == "running", session
                    names = {tool["name"] for tool in client.rpc("tools/list", {})["tools"]}
                    assert ("start_dev" in names) == (version == "1.999.0"), names
                    # The opposite generation bridge must consume this HTTP control plane without replacing it.
                    other_jar = new_jar if version == "1.998.0" else old_jar
                    with bridge(direct(other_jar, project), project, label + "-opposite") as other:
                        assert other.call("get_status")["session"]["sessionId"] == session["sessionId"]
                        for tool in ("get_active_problems", "get_logs", "get_test_status"):
                            other.call(tool)
                    # An explicit other version must respect the established active project pin.
                    other_version = "1.999.0" if version == "1.998.0" else "1.998.0"
                    with bridge(ensure(cli, other_version, project), project, label + "-pin") as peer:
                        assert peer.call("get_status")["session"]["sessionId"] == session["sessionId"]
                    print("PASS matrix, mixed HTTP, pin", label, flush=True)
            finally:
                stop(project)

    for attempt, (cli, version) in enumerate([(c, v) for c in clis for v in versions] * 2):
        project = root / f"race-{attempt}"
        project.mkdir()
        try:
            with bridge(direct(new_jar, project), project, f"race-{attempt}-new") as client:
                with ThreadPoolExecutor() as executor:
                    def legacy_start():
                        with bridge(ensure(cli, version, project), project, f"race-{attempt}-cli") as peer:
                            return peer.call("get_status")["session"]
                    future = executor.submit(legacy_start)
                    client.call("start_dev")
                    deadline = time.monotonic() + 60
                    while True:
                        result = client.call("get_status")
                        if "session" in result:
                            break
                        assert time.monotonic() < deadline, result
                        time.sleep(.1)
                    assert future.result()["sessionId"] == result["session"]["sessionId"]
                print("PASS mixed cold race", attempt, cli, version, flush=True)
        finally:
            stop(project)
    print("ALL PASS", root, flush=True)


if __name__ == "__main__":
    main()
