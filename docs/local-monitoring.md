# Local development console and monitoring

The dev server serves its console at `/_fluxzero/dev/` on the public application
port, including projects without a separate frontend and frontend-only projects.
The reserved console route remains local in frontend-only mode. **Project** is the first menu item and landing
page, showing only the selected dev server's application, components, resources and tests. The sidebar selector
groups results as **Current**, **Running** and **Stopped**. The whole result row is clickable. Search by server name or
project folder. Choosing an active server opens its Project page. Choosing an inactive server with an existing
folder offers a confirmation to start it using its project configuration and the current dev-server distribution.
Startup is bounded to two minutes, coalesces duplicate attempts and switches the browser only when a local
console URL is ready. A failed start leaves the user on the current server. Temporary CLI overrides from a previous
launch are not restored. The dialog also offers a trash button to remove an inactive server from the selector
without deleting project files. Folder links are available on the Project page.

Use the pencil next to the server title on the Project page to rename the current server. The folder name is the
default and can be restored with **Use folder name**. Names are stored per canonical project path in the global registry's `names/`
directory, independently of session registrations, so restarts and older server registrations do not overwrite
them. Application names and project files are unchanged. **Monitoring** uses the same Auditlog UI as the dashboard,
with Audit trail, Logs, Traces, Issues, Documents and Insights in the sidebar. Existing Visualize deep links remain
supported, but Visualize is not a menu item.
The shell uses the dashboard's light/dark theme tokens, logos and navigation
spacing; the theme is also applied to the embedded monitoring views.

## Known environments

Discovery uses the current user's global registry under
`~/.fluxzero/dev/environments/`. A separate `history/` index retains project paths
and last public URLs after a normal stop; the existing CLI list/stop cleanup
behavior is unchanged. Registration imports already registered environments.
Older projects that stopped before this history existed become known again when
started. The console does not scan the filesystem. It starts another known dev server only after explicit confirmation.
Each project path has an **Open folder** button: Finder on macOS, Windows Explorer
on Windows, and the desktop file manager (`xdg-open`) on Linux. Missing folders
have a disabled button. Only known project directories can be opened; paths are
passed as individual process arguments without a shell.

The trash button removes a stopped project from this overview, keeping its files,
local data, and CLI ownership registration. A history tombstone prevents legacy
registrations from reappearing during discovery; starting the project again
registers it visibly. Running servers must be stopped before they can be removed.
Project actions require a local, same-origin POST with the console request header;
GET requests and cross-origin browser requests cannot launch file managers or
rename, start or remove servers from the selector.

Automated tests use an isolated registry under the build output, including their
bootstrap child processes. Existing historical test entries can be removed with
the same trash button. A stale heartbeat is shown as not responding and disables the link. The overview
endpoint exposes only display fields, never MCP credentials or session metadata.

## Console frontend

`frontend/` contains the standalone Angular shell. The Maven build installs a
pinned Node runtime, runs `npm ci` and `npm run build`, and includes the resulting
assets in the standalone JAR. Node is a build tool only; serving the UI requires
no frontend process or Node installation. For Java-only iterations after a
successful frontend build, `-DskipFrontend` reuses that bundle. Do not use this
flag for clean qualification or release builds. Run `npm test` in `frontend/`
for the headless Chrome frontend tests.

Components use `@Handler`, `@HandleCommand` and `@HandleQuery` with the dashboard's
DOM dispatch contract: a bubbling `CustomEvent`, payload in `detail`, response
in `$result`, and the nearest handler stopping propagation. Registrations follow
component lifetime, and scopes cannot route to siblings. The small local
implementation uses prototype metadata so minification cannot change handler
identity. It does not copy the dashboard's authentication, remote gateways or
backend-generated domain models. Query handlers at the shell read console
endpoints; a project component may override a query in its own DOM subtree.

Auditlog remains an independent Angular app with its existing handlers. As on
the dashboard, the iframe is a separate DOM tree. Host navigation crosses that
boundary through the existing `fluxzero-marketplace-host-navigation` protocol;
only messages from the expected same-origin frame are accepted. View URLs,
filters and browser history stay synchronized. The host contract's optional
`navigation: 'host'` hides Auditlog's horizontal switcher. Existing hosts without
that field retain their previous behavior.

## Monitoring defaults

Auditlog starts automatically with every local backend environment, before application traffic.
The distribution contains the pinned Auditlog backend and UI. First use extracts and verifies them
in `~/.fluxzero/cache/dev-monitoring/`; subsequent starts reuse the verified cache. No Auditlog
checkout, Node installation, artifact paths, or user configuration is required at runtime.
Frontend-only environments do not start local monitoring.

To opt out, put this in `.fluxzero/dev.yaml` or the selected profile, then restart the environment:

```yaml
monitoring:
  enabled: false
```

You can customize retention or storage without providing artifact paths:

```yaml
monitoring:
  retention: P2D
  maxDiskBytes: 2147483648
```

Optional `~/.fluxzero/dev/monitoring.yaml` settings apply to projects without a monitoring block.
This file contains the monitoring fields directly, without a `version` or `monitoring` wrapper.
Project settings replace these machine defaults completely. Relative paths in machine settings
resolve beside that file; project paths resolve from the project directory. Malformed settings
fail startup with the filename. The JVM property `fluxzero.dev.monitoringDefaults` selects a
different defaults file; automated infrastructure tests explicitly opt out through a fixture.

For Auditlog development, override **both** `auditlogJar` and `uiDirectory` with compatible local
build outputs. Java 25 is required; `javaExecutable` defaults to the supervisor's Java.
Each environment owns separate monitoring processes and storage.

## Building a distribution

The Maven build packages the Auditlog revision pinned in `monitoring/auditlog-source.json`.
Maintainers need read access to that repository, Java 25 and Git. Maven installs the pinned
Node runtime, builds the backend and UI from the pinned commit, and embeds a checksummed ZIP.
Only compiled runtime artifacts are included; source checkout and credentials are not packaged.
The generated build input is cached under ignored `.private/monitoring/` and is reused across
`clean` builds. Changing the source pin or packaging script invalidates this cache.

A local checkout can supply the pinned Git objects without modifying that checkout:

```sh
./mvnw -B package -Dauditlog.source=/path/to/fluxzero-auditlog
```

CI uses `.github/actions/monitoring-source` and requires `AUDITLOG_SOURCE_TOKEN` with read-only
contents access to `fluxzero-io/fluxzero-auditlog` (including a Dependabot secret for its workflow).
Checkout credentials are not persisted. Fork PRs without this secret cannot produce the complete
distribution; run qualification on a trusted maintainer branch. No release or upload is performed
by a local build. End users of the resulting artifact never need these build credentials.

The dev server starts Auditlog before application traffic, waits for its health
endpoint, forwards application stdout/stderr through a bounded queue, and stops
its owned processes when the session stops. Startup failures include the child
service in diagnostics. Service IDs beginning with `monitoring-` are reserved.

## Storage and retention

`victorialogs` downloads a pinned, SHA-256-verified native VictoriaLogs 1.52.0
binary to `~/.fluxzero/cache/victorialogs/`. macOS/Linux ARM64 and AMD64, and
Windows AMD64 are supported. Windows uses the official ZIP and executable;
no external archive tool is required there. Windows ARM64 has no pinned native
artifact in this version. `victoriaLogsBinary` can point to an existing compatible binary for
offline operation. Each dev-server instance owns its own loopback port and
project/worktree-local `.fluxzero/dev/monitoring/victorialogs` directory. Data
survives a normal stop/restart. Docker and a shared VictoriaLogs service are
not needed. Separate projects do not share their audit database.
VictoriaLogs buffers recent writes before its default five-second disk flush;
an abrupt kill can lose those last seconds. Local monitoring does not promise
durable audit delivery across a crash.

The default time retention is one day. `maxDiskBytes` defaults to 1 GiB and is a
**disk retention threshold, not a hard quota**: VictoriaLogs retains recent
partitions and checks disk usage periodically. Allow room above this threshold.
Its `memory.allowedBytes=64MiB` setting sizes caches. `GOMAXPROCS=2` bounds Go
parallelism and `GOMEMLIMIT=128MiB` gives Go a soft memory target. None is a
hard RSS limit; working buffers and native memory can exceed these settings.
The current project page reports measured storage RSS, current disk bytes, the threshold and
whether it has been exceeded. Auditlog's Java heap has a 384 MiB maximum.

The dev server starts its managed Java processes (applications, Testserver/Proxy
and monitoring) with `-Djava.rmi.server.hostname=127.0.0.1`. This keeps local JMX
heap measurements available when the computer changes networks: RMI references
use loopback instead of the LAN address at startup. This also applies to any RMI
objects exported by these locally managed applications.

To use the existing testserver instead:

```yaml
monitoring:
  storage: testserver
  retention: PT15M
  maxRecords: 5000
  maxBytes: 8388608
```

This adapter uses the real document store in the monitoring namespace. Its
record/serialized-byte limits are enforced during ingest, and expired entries
are deleted even while idle. The earliest limit wins: 15 minutes is a maximum
age, not a guarantee of 15 minutes of history under load. Serialized bytes are
not heap bytes; the testserver also retains parsed document/search structures.
The data disappears with the in-memory runtime. The current project page reports retained and
evicted records, serialized size, Auditlog heap and cleanup errors. A retention
failure is surfaced rather than silently claiming that cleanup succeeded.

Application output is capped at 1,000 queued lines and 4,096 characters per
line. Ingest failure/overflow increments the visible dropped-line count. This
keeps unavailable monitoring from indefinitely retaining application output.

## Isolation and reusable UI contract

`/_fluxzero/dev/api/monitoring/*` is routed through the existing Fluxzero proxy
with `_fluxzero_monitoring` forced as namespace. A caller-supplied namespace
cannot redirect this route to application data. Auditlog consumes configured
application namespaces and rejects its own namespace as an ingestion source.
Monitoring reads therefore do not become new application audit entries.

The shell injects `window.fluxzeroHost` before loading the Auditlog bundle:
`kind`, `apiBase`, `navigation`, and `capabilities`. The frontend rewrites its same-origin API
calls through `apiBase`, preserves the Angular base path, and lets the host own
Audit trail, Logs, Traces, Issues, Documents, Insights and Visualize navigation.
The existing dashboard behavior remains the default without this contract.
VictoriaMetrics-specific resource charts are not advertised locally; local
process/storage measurements are shown on the current project page instead.

This is a local development UI served under the dev server's existing access
model. Do not expose it as a public authenticated dashboard.

## Windows prototype checkout

Use a Windows x64 JDK 25, Git, Node.js 24.18 or newer and Python 3.9 or newer.
The dev-server build installs its own pinned Node and packages Auditlog automatically. Python is needed to package the SDK documentation archive.
The complete unreleased prototype spans these branches:

| Repository | Branch |
| --- | --- |
| fluxzero-dev-server | `codex/local-monitoring` |
| fluxzero-auditlog | `codex/local-monitoring` |
| fluxzero-sdk-java | `codex/testserver-reset` |

Build the SDK test runtime once in its dedicated checkout. SDK 1.269.0 does not
contain `TestServer.truncateData(Server)`; that capability is needed by the trash
action. This local version does not change the SDK dependency of the application:

```powershell
cd C:\work\fluxzero-sdk-java
.\mvnw.cmd -B versions:set "-DnewVersion=0-U13-SNAPSHOT" "-DgenerateBackupPoms=false"
.\mvnw.cmd -B -pl test-server,proxy -am install -DskipTests "-Dagent-docs.python=python"

cd C:\work\fluxzero-dev-server
.\mvnw.cmd -B clean install

```

Use `python3` instead of `python` in the property if that is the installed
interpreter name. Auditlog is bundled automatically; no artifact paths are needed. Then launch the built dev server explicitly from PowerShell:

```powershell
$env:FLUXZERO_DEV_RUNTIME_VERSION = "0-U13-SNAPSHOT"
java -jar C:\work\fluxzero-dev-server\target\fluxzero-dev-server-1-SNAPSHOT-standalone.jar --project-dir C:\work\my-app
```

An existing `fz dev` installation does not automatically select this checkout.
Without monitoring enabled, the Projects UI can also be tested independently of
the Auditlog checkout. Without a truncate-capable runtime, data truncation is
unavailable. The prototype's Windows download selection and ZIP extraction have
automated coverage; the complete Windows monitoring start/restart/truncate flow
still needs to be exercised on Windows. The repository's standard CI matrix
includes Windows, while the application and frontend E2E jobs currently use Linux.
