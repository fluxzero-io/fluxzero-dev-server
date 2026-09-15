# Local development console and monitoring

The dev server serves its console at `/_fluxzero/dev/` on the public application
port, including projects without a separate frontend and frontend-only projects.
The reserved console route remains local in frontend-only mode. **Projects** is the first menu item and landing page. It lists known dev servers on this computer by project folder, with
running/stopped status, the last public port, and links to responsive instances.
Selecting an active project switches to that dev server's **Projects** page.
The current project appears first with its folder, port, service status and monitoring
resources. Other projects follow in a compact list. The current project remains
selected when opening its own link. The sidebar contains **Projects** and **Monitoring**. The external-link icon beside
the current project name opens the application in a new tab. The displayed dev
server name is the last component of its project directory, independent of the
Fluxzero application name; there is no separate display-name setting yet. **Monitoring** uses the same Auditlog UI as the dashboard, with Audit
trail, Logs, Traces, Issues, Documents, Insights and Visualize in the left sidebar.
The shell uses the dashboard's light/dark theme tokens, logos and navigation
spacing; the theme is also applied to the embedded monitoring views.

## Known environments

Discovery uses the current user's global registry under
`~/.fluxzero/dev/environments/`. A separate `history/` index retains project paths
and last public URLs after a normal stop; the existing CLI list/stop cleanup
behavior is unchanged. Registration imports already registered environments.
Older projects that stopped before this history existed become known again when
started. The console does not scan the filesystem or start other dev servers.
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
change the overview.

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

## Enable monitoring

Build the local Auditlog checkout once (Java 25 and its Node/npm version):

```sh
cd /path/to/fluxzero-auditlog/backend
./mvnw -B package
cd ../frontend
npm ci
npm run build-dev-server
```

Add to your project's `.fluxzero/dev.yaml`, or to one selected profile:

```yaml
version: 1
environment: local
monitoring:
  auditlogJar: ../fluxzero-auditlog/backend/target/auditlog.jar
  uiDirectory: ../fluxzero-auditlog/frontend/dist/fluxzero-auditlog/browser
  storage: victorialogs
  retention: P1D
  maxDiskBytes: 1073741824
  # javaExecutable: /path/to/java25/bin/java
```

Run `fz dev` using a dev-server build containing this feature, or launch that
build's standalone JAR with `java -jar ...-standalone.jar --project-dir .`.
Auditlog requires Java 25; `javaExecutable` defaults to the supervisor's Java.
Paths are resolved relative to the development project, including when using
profiles. Building these artifacts does not publish or release them.

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

To use the existing testserver instead:

```yaml
monitoring:
  auditlogJar: ../fluxzero-auditlog/backend/target/auditlog.jar
  uiDirectory: ../fluxzero-auditlog/frontend/dist/fluxzero-auditlog/browser
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
The dev-server build installs its own pinned Node; the separate Auditlog frontend
uses Node/npm from PATH. Python is needed to package the SDK documentation archive.
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

cd C:\work\fluxzero-auditlog\backend
.\mvnw.cmd -B -DskipTests package
cd ..\frontend
npm ci
npm run build-dev-server
```

Use `python3` instead of `python` in the property if that is the installed
interpreter name. Configure `auditlogJar` and `uiDirectory` in the application's
`.fluxzero/dev.yaml` as above; relative paths and forward slashes also work on
Windows. Then launch the built dev server explicitly from PowerShell:

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
