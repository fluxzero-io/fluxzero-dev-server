<a href="https://fluxzero.io"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/3fa8f79df95d07678a730147bc1bd0402ae660d5/assets/brand/2026-09/repository-header.svg" alt="Fluxzero — The European cloud for AI-built apps" width="1280"></a>

# Fluxzero Dev Server

Local development server for [Fluxzero](https://fluxzero.io) applications.

The Fluxzero Dev Server provides a complete local development environment for Fluxzero applications. It starts
the public SDK Test Server and Proxy, launches one or more applications, performs rolling replacements after source
changes, runs affected tests in the background, and can manage a frontend development server behind one public
URL.

The server and dashboard work exclusively with this locally managed Test Server. The private production runtime
is not a supported target or local development dependency. No production-runtime implementation is resolved or started.
Existing `runtime` status fields, cache paths and version-override names are historical compatibility names for the Test Server.

The server is normally launched through `fz dev`, the Fluxzero Maven plugin, or the Fluxzero Gradle plugin. This
repository contains the independently versioned server implementation and its standalone executable JAR.

The agent control plane may start in a completely empty greenfield workspace before project generation. In that
phase only session management, MCP, and a source watcher run; `get_status` reports `waiting-for-project` and directs
the agent to generate the project in the same root. When a Maven or Gradle build appears, the server reloads the
new project configuration and starts the normal Test Server, Proxy, IDP, application, and test lifecycle without
replacing the MCP session. Non-empty directories without a build root remain invalid.

For independent full suites and dependency changes while using a frontend watcher, see
[external verification and build isolation](docs/external-verification.md).

The dashboard's dev server selector only links to sessions whose gateway advertises
`metadata.devConsoleVersion: "1"` in `.fluxzero/dev/session.json`. Older servers remain
listed with a dashboard-unavailable message instead of routing into the customer app.
Restart those environments with a dashboard-enabled Dev Server build to switch to them.

The Total memory breakdown shows known versions after each service name, including the Dev Server,
selected Fluxzero Test Server, dashboard app and bundled VictoriaLogs store. Current startup
or update failures add a short notice and a navigation indicator. Tests and Startup show green success
counts in navigation, replaced by red failure counts when errors exist.
These indicators clear on recovery and do not surface historical log errors or ordinary startup transitions.
Detailed diagnostics remain available through MCP.

Workspace also shows the server's relative start time, with its exact local timestamp on hover.
The Startup commands page, labeled Startup directly below Tests in navigation, lists reported startup actions in execution order with readable names and individual results.
Actions are visible by default, with search and All, Completed, Failed and Pending filters. Pending includes blocked
actions, whose rows retain their Blocked status. Large lists offer Show more after 50 entries. Expand a command to load its JSON (type, revision and payload), then use Copy to copy the displayed JSON.
Command details are loaded only on demand for the active session; diagnostic details remain in the agent tools.

The Tests panel groups parameterized scenarios by test method, collapsed by default with result counts.
Expand a group to inspect its variants. Search and result filters include collapsed variants; failed groups
and scenarios appear first. Show more adds 50 entries at a time and live refresh preserves expanded lists.

The Tests panel follows new output while scrolled to the bottom, and preserves your reading position when
scrolled up. Its pause control pauses automatic tests; an active test run is allowed to finish. Builds, application reloads
and UI servers continue normally. Resume tests the changes collected during the pause. Rerun tests remains
available for a manual run while paused; clearing output only clears the displayed log.

## Requirements

- JDK 25 for building and running the dev server
- A project-local Maven or Gradle wrapper in applications being developed
- Node.js and npm only when running the optional frontend framework tests

## Build

Use the checked-in Maven wrapper:

```shell
./mvnw -B clean install
```

On Windows:

```powershell
.\mvnw.cmd -B clean install
```

The build also installs a pinned Node.js runtime to build the Angular console.
Node.js is not required when running the packaged dev server.

The build creates both the regular Maven artifact and an executable standalone JAR under `target/`.

The build uses an exact published Fluxzero SDK version. It deliberately does not locate or build a sibling SDK
checkout. Override `-Dfluxzero.version=...` only when verifying against another installed or published SDK
version.

## Develop the dev server itself

Run the test application `DevServerPreview` from this repository:

```shell
fz dev --main-class io.fluxzero.devserver.DevServerPreview --no-frontend --idp external
```

The supervising dev server builds the sources, runs tests and replaces the preview application after Java changes.
The application link opens the preview console. The preview runs its own Testserver and Proxy, but disables watching,
compilation, tests and frontend/application launches, so it cannot recursively start another dev server.
Each preview gets free ports and an isolated workspace under `target/dev-previews/`; rolling replacement can start the
new instance before stopping the previous one. These disposable workspaces are not added to project discovery.
Their files remain under `target/` for diagnostics and are removed by a normal clean build while the environment is stopped.
The supervisor owns preview restarts; use its application restart button. The preview does not offer an independent
full-server restart. Rebuilding the console bundles still follows the normal Maven frontend build.

## Test

Run the default unit and integration suite:

```shell
./mvnw -B test
```

Run one test class while developing:

```shell
./mvnw -B -Dtest=DevServerLifecycleTest test
```

Run the opt-in tests that create complete Fluxzero applications and change their sources while the server is
running:

```shell
./mvnw -B verify -Pdev-server-e2e
```

The test-runner telemetry scenarios in this profile exercise both Maven and Gradle. If Gradle is not on `PATH`,
pass `-Dfluxzero.dev.gradleExecutable=/absolute/path/to/gradle` (or `gradle.bat` on Windows).

Run the real Vite and Angular gateway, websocket, and hot-reload tests:

```shell
./mvnw -B verify -Pdev-server-frontend-e2e
```

The frontend profile installs fixture dependencies and therefore requires Node.js, npm, and network access when
the npm cache is incomplete. Custom executable locations can be supplied with `-Dfluxzero.node=...` and
`-Dfluxzero.npm.cli=...`.

## Run From A Checkout

After building, start the standalone server for another project with:

```shell
java -jar target/fluxzero-dev-server-1-SNAPSHOT-standalone.jar --project-dir /path/to/project
```

For normal use, install the Fluxzero CLI and run this from the application project instead:

```shell
fz dev
```

Project-local controls remain available after detaching. `fz dev list` can be run from any directory and shows
all globally registered environments, including stale registrations left by an unexpected process stop:

```shell
fz dev list
fz dev status --project-dir /path/to/project
fz dev logs --project-dir /path/to/project --follow
fz dev stop --project-dir /path/to/project
fz dev stop --all
fz dev list --json
```

An attached environment is owned by its terminal and stops when that terminal closes or receives `Ctrl+C`.
Use `d`, `detach`, or `fz dev --background` to transfer it explicitly to background ownership. Detached macOS
jobs do not restart after login. `fz dev stop --all` stops every registered environment and removes stale
registrations; this is also the migration path for detached jobs created by older CLI releases.

The global index under `~/.fluxzero/dev/environments/` contains only project paths and session/process identity.
Current status, URLs, and application names are read from each project's `.fluxzero/dev/session.json`; MCP tokens,
resolved environment variables, and other secrets are never copied into the index.

### Agent Documentation

The standalone stdio MCP started by `fz mcp` serves SDK documentation through `docs_start`, `docs_search`,
`docs_lookup_symbol`, `docs_read`, and `docs_links`. Start with `docs_start`, search for the relevant topic or
exact symbol, then read individual articles and follow their links. Search returns summaries; article text and
link lists have bounded pages to avoid loading the whole manual into the agent's context.

Documentation follows each project's declared SDK version, independently of the shared Test Server and its
version override. Multiple project versions require an explicit selection. An empty project can use an explicit
version before generation, or use the latest published release as a fallback. A known project's missing
artifact never causes a project upgrade or silently selects another SDK version. Documentation remains
available independently of whether the project dev server can start or is running.

`fz mcp` needs an existing directory but starts no project server, source watcher, or listening port.
`get_status` reports `dev-server-not-running` with structured `start` guidance when no project environment is
active. Call the stdio-only `start_dev` tool with no arguments to start or reuse the environment in
this connection's directory. It returns current status and a session cursor once available; during bootstrap
it returns `dev-server-starting` and the agent polls `get_status`. Startup failures are tool errors with
`dev-server-start-failed`; documentation stays available. Correct the cause before retrying `start_dev`.
Status and documentation calls never start project processes. The normal project/empty-workspace validation
still applies.

Existing `fz mcp --ensure-dev` configurations remain supported. The shared bootstrap uses
`.fluxzero/dev/ensure.lock`, including when an older CLI is starting the same directory.
New launchers delegate locking to this distribution; their legacy fallback coordinates starts
for older distributions. Older dev-server distributions do not expose `start_dev` or the docs
surface: a project pinned to one must upgrade its dev-server distribution to use these tools.

Project requests connect lazily to the loopback HTTP MCP endpoint and reconnect after a session changes.
The HTTP endpoint exposes only project tools; `docs_*` are local to stdio. Closing the agent's stdin ends its
stdio process and cancels downloads without stopping a shared background project environment.

Archives are downloaded on demand from Fluxzero Packages and cached per namespace and version under
`~/.fluxzero/cache/agent-docs/`. A valid cached release needs no network access, including after a restart.
When an agent sandbox cannot write that shared location, the stdio server automatically uses the ignored project-local
`.fluxzero/dev/cache/agent-docs/` directory instead; no `dev.yaml` setting or broader filesystem permission is required.
Only the `sdk` namespace is currently provided. See [the documentation API reference](docs/agent-documentation.md)
for selectors, limits, cache validation, and local archive configuration.

### Agent Problem Deltas

The MCP `wait_for_change` tool is a cursor delta. Its `problemChanges` field contains only selected problems that were
`added`, `changed`, or `resolved` after the supplied cursor; it never repeats the complete active problem snapshot.
Each transition carries a compact problem summary and service identity, but no potentially large `detail` field.
Every response also includes `activeProblemCount`, the current number of active problems matching the same selector.
An empty `problemChanges` list therefore does not mean that the environment is healthy; only a zero count means that
the selected active set is empty.

Clients apply added and changed records by problem id and remove resolved records. They must drain every `hasMore`
page before comparing their local map with `activeProblemCount`. A count mismatch, lost client state, or
`sessionChanged: true` requires a fresh `get_active_problems` baseline. That explicit snapshot tool continues to return
full `DevProblem` records, including details, together with its cursor, total `activeProblemCount`, and `truncated`
marker. Selectors apply identically to transitions and snapshots, including resolutions whose causal lifecycle event
has a lower log level than the resolved problem.

Problem transitions are ordered by the shared event cursor. All events and transitions with the same causal sequence
are returned as one indivisible page group, so retrying a request from the same cursor remains safe.

Launchers resolve the latest compatible stable `1.x` release from [Fluxzero Packages](https://packages.fluxzero.io/). A specific development or
snapshot build can be selected with `--dev-server-version` or `FLUXZERO_DEV_SERVER_VERSION` after installing it
in the local Maven repository.

### Test Server Version Alignment

The dev server detects the Fluxzero SDK version declared by each Maven or Gradle build and resolves the matching
`io.fluxzero:test-server` and `io.fluxzero:proxy` artifacts from Fluxzero Packages. Maven Central remains
available for third-party dependencies. Test Server and Proxy share one isolated
child JVM, so their protocol generation cannot accidentally come from the SDK version embedded in the dev-server
release. Resolved classpaths are cached under `~/.fluxzero/cache/dev-runtime/<sdk-version>/`; normal warm starts do
not contact either repository again.

Projects in one environment must use the same Fluxzero SDK major generation. When compatible projects use different
versions within that generation, the newest version is selected for the shared Test Server. The effective version and
cache status are published as `sdkVersion`, `mode`, and `artifactCache` in the runtime and proxy entries of
`.fluxzero/dev/session.json`.

Build files are the source of truth; generated runtime-classpath metadata is only a fallback. For unusual build models,
set `FLUXZERO_DEV_RUNTIME_VERSION` (or the JVM property `fluxzero.dev.runtime.version`) to an exact published SDK
version. This override changes the local TestServer and Proxy only, not the application's dependencies.

The published Maven coordinates are:

```text
io.fluxzero.tools:fluxzero-dev-server:<version>
io.fluxzero.tools:fluxzero-dev-server:<version>:standalone
```

Every push to `main` that passes the cross-platform build, whole-application tests, frontend framework tests, and
release packaging validation produces a semantic release. The release publishes first to Fluxzero Packages.
An independent consumer with an empty Maven cache verifies the downloaded artifacts and starts and stops the
standalone server before the GitHub release is created. Until 1 October 2026, the workflow also publishes to
Maven Central and verifies that publication. From that date, new releases are available only from Fluxzero
Packages; existing Central releases remain available. The first release is `1.0.0`; breaking launcher,
configuration, session, or control protocol changes require a new major version.

Dependabot tracks the Fluxzero SDK BOM independently. Non-major SDK updates are automatically merged only after
the full pull-request verification succeeds; that merge then produces a patch release of the dev server. Other
SDK releases do not trigger this repository directly.

### Release Repository Setup

The GitHub repository must be public. Central publication before 1 October 2026 uses
`OSSRH_USERNAME` and `OSSRH_PASSWORD`; `OSSRH_SIGNING_KEY` and `OSSRH_SIGNING_PASSPHRASE`
remain in use for signing Packages releases. The Dependabot secret store must contain
`DEPENDABOT_AUTOMERGE_APP_CLIENT_ID` and `DEPENDABOT_AUTOMERGE_APP_PRIVATE_KEY`; that GitHub App needs write
access to contents and pull requests in this repository. Central namespace ownership for `io.fluxzero.tools` is
shared with the Fluxzero CLI artifacts.

Packages uploads use `https://packages.fluxzero.io/publish/maven`; public downloads use
`https://packages.fluxzero.io/maven`. The publishing job uses the existing organization-wide GitHub OIDC trust,
with audience `https://packages.fluxzero.io/publish/maven` and `id-token: write`. Maven server `fluxzero` receives the
short-lived token as its password. No additional long-lived upload credentials are required.

- `./mvnw -Psign deploy` publishes signed artifacts, sources and Javadoc to Packages.
- `./mvnw -Psign,central deploy` performs a manual Central publication for pre-cutoff recovery.
- `bash .github/scripts/verify-packages-consumer.sh <version>` independently downloads and runs a release.

The existing GPG secrets sign both publications. Releases are immutable: after a partial publication, recover
using the original files or publish a new semantic version. Do not rerun a completed Packages upload with rebuilt
JARs or regenerated signatures, and never overwrite published release bytes.

Project-level configuration belongs in `.fluxzero/dev.yaml`. Ephemeral session state, diagnostics, test impact
data, and combined logs are written below `.fluxzero/dev/` in the application project and should not be committed.
Print the configuration reference for the current dev-server version with:

```shell
fz dev config
```

Projects with several complete local setups can define `profiles`, select a `defaultProfile`, and override it with
`fz dev --profile <name>` or `FLUXZERO_DEV_PROFILE`. Existing top-level configuration remains supported. Profile
configuration is deliberately complete rather than inherited, so selecting another profile cannot accidentally retain
applications, secrets, commands, or frontend settings from the default profile.

The output is valid YAML and documents application selection, named application flavors, managed or external
support services and frontends, backend pass-through paths, 1Password secret references, ordered startup commands,
and lifecycle timeouts. `port` is the one public port for the complete dev environment. A managed frontend receives
a separate private `{frontendPort}` in its command. Existing `frontend` configuration continues to serve one UI at
`/`. Use the additive `frontends` map when one environment needs several UIs: each entry has a stable id and optional public mount `path`,
the root frontend omits `path`, and the gateway uses the longest matching path for HTTP and WebSocket traffic.
Set `readinessPath` on a frontend when its functional root redirects or is unsuitable as a health probe; it defaults
to `/`, and readiness probes never follow redirects.
Files changed inside a managed frontend while another dev pipeline is publishing are treated as one coherent update.
The frontend remains available but reports `degraded` until publication settles, then restarts once and returns to
`running` only after its configured HTTP readiness is healthy again. This handoff is based on file ownership, process
lifecycle, and readiness; it does not depend on a frontend framework, generator, or console message format.
Profile-level `backendPaths` add pass-through routes to the built-in `/api` route and keep priority over frontends.
Legacy `gatewayPort`, `{port}`, and frontend-local `backendPaths` remain accepted for version 1 configuration.

Set `frontendOnly: true` on a profile that should run the managed frontend and public gateway without a local
Fluxzero Test Server, proxy, identity provider, applications, compilation, tests, or startup commands. In this mode all
public HTTP and WebSocket traffic, including `/api` and `/_fluxzero`, is routed to the frontend so its own development
proxy can target a remote backend. Backend application settings and `backendPaths` are rejected to prevent a profile
from appearing to start components that it deliberately skips.

Use `projects` inside a profile when one local environment spans independent Maven or Gradle roots. Every named
project has its own directory, application selection, optional application configuration, compile pipeline, source
watcher, rolling replacement, and background tests. The projects share one Fluxzero Test Server and gateway, while an
application configuration can override its namespace. A failed compile or startup in one project leaves the last
ready applications from all projects running. `projects` is additive configuration: existing single-project files
continue to use `apps` and `applicationConfig` unchanged.

Define startup data with profile-level `commands`. Entries run in declaration order across all applications and may
mix existing TestFixture JSON resources with named inline commands:

```yaml
commandDefaults:
  userMetadataKey: $user
  systemUser: $system
commands:
  - src/test/resources/users/*.json
  - src/test/resources/items/create-product.json:
      user: admin
  - create-extra-admin:
      user:
        name: Legacy Admin
      type: com.example.CreateUser
      payload:
        name: Local Admin
```

Referenced JSON resources support TestFixture's `@class`, `@revision`, and recursive `@extends` properties. A short
`@class` value such as `CreateAccount` resolves through the application's generated type registry when the type is
covered by `@RegisterType`; fully qualified class names remain supported. Files may also use the dev server's existing
`type`/`revision`/`payload`/`metadata` envelope. Successful commands run once per
in-memory Test Server; changed or failed commands are retried without re-running unchanged successful predecessors. The
conventional `src/test/resources/fluxzero/dev/commands/**/*.json` directory remains supported and runs after explicitly
configured commands in normalized path order.

The local Test Server starts consumers without a stored position ten seconds before the current end of their log,
instead of the regular one-second look-back. Startup commands published shortly before a new application consumer is
registered therefore remain visible during a normal cold start. Existing stored consumer positions are unaffected and
the mechanism has no dependency on an application framework or framework lifecycle. Command results use the regular
one-minute gateway timeout.

Every startup command receives `$user: "$system"` metadata by default. `commandDefaults.userMetadataKey` changes the
profile-wide key and `commandDefaults.systemUser` may be either an id or a complete JSON/YAML user object. A command or
file reference can override the identity with `user`; ids use the SDK's user-id metadata support, while complete user
objects remain suitable for applications using compatibility defaults. Ordinary `metadata` is merged on inline and
referenced commands and remains the escape hatch for a command that uses a different user key. Explicit command
metadata wins over defaults; `user` wins over metadata at `userMetadataKey` when both are present. Changing identity
metadata changes the command hash so the command runs again.

```yaml
commands:
  - create-as-sender:
      metadata:
        $sender: admin
      type: com.example.CreateUser
      payload:
        name: Local Admin
```

User ids, including `$system`, require Fluxzero SDK 1.236.0 or newer with defaults version `2026.08.04` or newer, or
`fluxzero.auth.useUserIdMetadata=true`. Configure a complete `systemUser` and complete command `user` objects when an
older application must deserialize user metadata directly.

File entries may use `*`, `?`, and recursive `**` glob patterns. Matches are inserted alphabetically by normalized
project-relative path at the pattern's position in the command list. A pattern without matches is reported as a
configuration error and remains watched, so adding its first matching file recovers automatically.

Use `services` for databases, emulators, log stores, or other local dependencies. A service with `command` is owned
by the dev server; a service with only `url` is external and is never stopped. Named ports accept a fixed number or
`dynamic`. Service-local fields reference a named allocated port as `{servicePort.<name>}`. The resolved URL and ports
are available to application and frontend configuration as
`{services.<id>.url}` and `{services.<id>.ports.<name>}`. HTTP, TCP or explicit log readiness participates in startup,
while service health, process identity, logs, diagnostics, stale cleanup, and bounded shutdown remain part of the
same session.

Services without a local HTTP/TCP endpoint can opt into startup readiness from process output:

```yaml
services:
  stripe-webhooks:
    command: >-
      stripe listen --skip-update --color off
      --config .fluxzero/stripe/config.toml
      --forward-to http://localhost:4242/api/checkout/webhook
    readiness:
      log: 'Ready!'
      timeout: 30s
    output:
      redact:
        - 'whsec_[A-Za-z0-9]+'
```

`readiness.log` is a Java regular expression searched on each stdout and stderr line after ANSI formatting is
removed. It requires a managed service with `command` and is mutually exclusive with `readiness.http` and
`readiness.tcp`. With no explicit readiness variant, `url` still supplies HTTP readiness. The startup timeout
defaults to two minutes. The matcher is installed before process launch, so immediate output is recognized.
Timeout or exit before readiness fails startup; exit afterwards makes the service unavailable. A log match is a
one-time startup signal: it does not establish continuing connectivity to a remote provider.

`output.redact` is an ordered list of Java regular expressions. Matches become `[REDACTED]` before output reaches
the terminal, stored logs, diagnostics, or MCP, including output from `stopCommand`. When redaction is configured,
ANSI formatting is removed before replacement so styling cannot split a sensitive value. Readiness matches the
original ANSI-free line inside the process adapter; that unredacted line is neither retained nor published.
Patterns are compiled once before launch; invalid or blank patterns reject configuration. They are literal regexes,
without placeholder expansion. Matching is line-based and does not retain an output history. Configure patterns
for the sensitive values the command emits. Frontend readiness is unchanged.

See [`docs/developer/composed-environment-contract.md`](docs/developer/composed-environment-contract.md) for a full
Dashboard/Auditlog example that combines named profiles, independent build projects, multiple frontends, and
namespace overrides.

Environments stop after 24 hours without source, browser, build, test, command, or attach activity by default,
including before initial readiness. Configure `lifecycle.idleTimeout` with values such as `30m`, `24h`, or
`disabled`; the limit applies in attached and detached mode.

## Development Principles

- A newly compiled application becomes active before the previous ready instance is stopped.
- Compile, application replacement, frontend lifecycle, and background tests remain independent pipelines.
- Failed compiles, failed application starts, and failed tests do not take the last working application down.
- Frontend integrations are command- and protocol-based rather than tied to a specific framework.
- Secret references may be shared in project configuration, but resolved secret values are never persisted or
  included in diagnostics.

See [`docs/developer/dev-server-implementation-plan.md`](docs/developer/dev-server-implementation-plan.md) for
the implemented architecture, phases, and verification scenarios.

## Related Repositories

- [Fluxzero Java SDK](https://github.com/fluxzero-io/fluxzero-sdk-java)
- [Fluxzero CLI](https://github.com/fluxzero-io/fluxzero-cli)

## License

Fluxzero Dev Server is available under the [Apache License 2.0](LICENSE).

## Development console

Open `/_fluxzero/dev/` on the public development URL. App preview opens by default. The **Dev environment** page and **Monitoring** menu show only the
selected dev server. Use the sidebar dropdown to switch servers: results are grouped as **Current**, **Running**
and **Stopped**. Search by name or folder. Selecting an active server opens its App preview; selecting an inactive server with an existing folder offers to start it in the background. Startup uses
that project's `.fluxzero/dev.yaml` and the current dev-server distribution, without restoring temporary
command-line overrides from earlier launches. The browser switches after the server's console is ready.

Use the pencil next to the server title on the Dev environment page to distinguish folders with the same name. The
default is the folder name; **Use folder name** restores it. These local display names are stored in
`~/.fluxzero/dev/environments/names/`
and survive server restarts. They do not rename project directories or applications. Inactive servers can be
removed using the trash button in the dialog shown when selecting them, without deleting project files.

Auditlog is enabled by default for local backend environments. Its backend and UI are bundled with the Dev Server;
no separate checkout or user configuration is required. A project's `monitoring: {enabled: false}` opts out.
Optional `~/.fluxzero/dev/monitoring.yaml` settings customize machine defaults; project settings override them.
See [local monitoring](docs/local-monitoring.md) for Auditlog setup, native VictoriaLogs, the testserver adapter
and resource limits.

Monitoring uses the dashboard's section navigation and page layout. The current view title and project name
sit above the embedded page, sharing its background and content gutters in light and dark themes.
The embedded application keeps its existing filters, trace navigation and per-view state when switching screens.

The **Dev profile** selector below the server picker lists the named profiles from `.fluxzero/dev.yaml`.
Switching requires confirmation because it restarts the entire environment, resets in-memory application data,
and reruns startup commands. The standalone server validates the selected configuration before stopping the
current environment, preserves the public port and explicit launch options, and reconnects the dashboard.
The selection survives subsequent dashboard restarts within the running launcher; it does not change
`defaultProfile` or persist after stopping the launcher. A fresh launch uses the normal CLI/configuration selection.
Embedded previews without restart support display the active profile without allowing a switch.

### Component resources and maintenance

Dev environment shows one compact card per backend application, with memory, status and an individual restart
beside its status badge. Customer applications have no storage metric. A separate **Dev resources**
section reports combined usage of the dev server, Test Server and Proxy, managed UI servers, monitoring
and supporting services. Monitoring storage is reported only there. Tests are workspace-wide and have their own sidebar page.
A stopped application stays visible. Infrastructure status and memory offer hover/focus breakdowns by component.
Graph buttons open dialogs with a time axis and byte scale for memory and monitoring storage.
Memory shows used / maximum: Java components report actual heap usage and the effective JVM heap limit;
VictoriaLogs reports Go-managed memory (Sys minus HeapReleased) and its exported Go memory limit. The dev server
reads its own heap directly and samples managed Java processes through local JMX. Attach and sampling run in a
bounded background pool. Components without a managed heap measurement or limit (such as Node frontends, Mailpit
and webhook forwarders) use measured process-tree RSS on Unix or working set on Windows. The infrastructure total
adds these displayed measurements; an unknown limit remains unknown and is omitted from the usage label. If a
heap limit is known but its usage is unavailable, RSS is not substituted against that limit. Missing process
measurements also remain unknown.
The memory breakdown provides a graph button per service, opening a dialog with the component's history.
The dev server retains at most 60 five-second samples in memory, including usage and limits per component ID,
even with no browsers connected. Missing samples leave gaps. Navigation, reload and reconnect restore the same
history; restarting the dev server starts a new history. Monitoring storage shows its on-disk size
and configured retention threshold. Its chart scales to that threshold (1 GiB by default), retained with each sample,
rather than the observed storage peak. Totals, limits and component details automatically use IEC units (B, KiB,
MiB, GiB and larger). Cards show labeled memory and storage measurements and reflow into a single column on
narrow screens. Long names and paths wrap, and component popovers stay within the visible viewport.

The workspace restart control offers **Apps** and **All**. The selected scope is remembered in localStorage; the initial default is Apps.
Choosing a scope does not execute the action. Apps reuses the last ready backend builds and replaces
managed frontend processes while retaining their ports and the Test Server. Each app also has an individual restart.
All replaces the managed environment within the standalone launcher JVM, retaining the public
port. It always asks for confirmation and warns that in-memory application data is reset because a fresh Test Server
is started. Configured initial commands run again; on-disk VictoriaLogs monitoring history is deleted after stopping its writers.
Profile switches and ordinary stop/start preserve this history. A failed storage reset is reported without restarting.
The five-minute resource graph history and process-local counters start fresh. With `monitoring.storage: testserver`,
monitoring data is in-memory and resets too. The launcher PID stays unchanged.
There is no separate Reset data action in the dashboard. Legacy per-store HTTP maintenance actions remain for
compatibility. All maintenance actions require a same-origin loopback POST.

The test bar includes left-aligned passed / failed / total counts using the table status colors. Total represents the
known project inventory, independent of the run selection. A selective run temporarily clears the previous outcomes
of its selected tests, preserving other results, then updates each outcome live. Errors count as failures; skipped
and pending tests remain neutral. Unknown inventories display `?` instead of substituting the number executed.
The bounded inventory and latest outcomes are stored in `.fluxzero/dev/test-inventory.json` and survive reconnects
and dev-server restarts. Missing results and interrupted tests never become synthetic passes.

### Console preferences

The theme icon beside the connection status opens a vertical menu for Light, Dark and System.
It shows the current preference (System follows operating-system appearance changes), and the selected option
is highlighted. Theme preferences are saved in browser local storage for the current dev-server address.

Environment restart always opens a confirmation dialog. Cancel or Escape dismisses it without restarting.
Confirmation cannot be disabled, including by preferences saved in earlier versions. Old Settings links redirect to Projects.

### Live test progress and output

**Tests** has its own sidebar page. Named scenarios and their latest outcomes appear in one card.
Compact **Rerun** and **Pause** / **Resume** actions remain available above the tabs.
Technical logs are available in the **Output** tab on the right. The default **All** filter shows every discovered test,
with failed tests first. Use **Passed**, **Failed**, or **Skipped** to narrow the results. Search works across
names, suites and modules, with 50 results per page. This inventory is
loaded only while viewing Tests, separately from the frequent status updates. It describes discovered tests,
not a claim that every possible application scenario is covered.

JUnit display names (including dynamic/parameterized invocation names) are retained across restarts. Older
inventories and Gradle results use readable names derived from test identities. Without named telemetry,
the aggregate run results and output remain available; individual outcomes are never inferred from counts.

The dev server automatically adds a small listener to Maven/JUnit Platform test runs and callbacks to Gradle
`Test` tasks. Customer source files and build configuration do not need changes. Each completed invocation
updates the test bar through the console WebSocket, including parameterized, dynamic and forked tests.
JUnit Platform discovers the test classes in the runner's test output directory without executing unselected tests.
Parameterized and dynamic invocations can change the inventory during execution. Gradle learns the inventory from
completed full runs and uses stable testcase identities during selective runs. Newly discovered cases start pending;
removed cases are pruned when a fresh inventory or a completed full run establishes their absence.
When events are unavailable, XML remains the fallback for run diagnostics; incomplete telemetry does not invent
per-test outcomes. An interrupted run keeps its valid reported outcomes and leaves unfinished tests pending.

The rerun button beside the test bar runs the full suite for each test-enabled module, even with unchanged
inputs. Runs use the existing test pipeline. A newer code-change run supersedes a queued manual run;
a manual run interrupted for compilation is not automatically resumed. Gradle manual runs use
`--rerun-tasks` to bypass up-to-date checks and cached task results. Output is always visible at a default and minimum height of 80 pixels; drag the lower-right corner to resize
it vertically. The pause/play button pauses only automatic tests; builds and UI servers continue running,
and manual reruns remain available. Output follows new lines when already at the bottom and preserves the
viewport when scrolled up. Hovering or focusing the output reveals scroll-to-bottom and clear buttons.
The clear button clears the shared
output history, including on reconnect, while keeping test results and application data. The dev server retains the latest 200 lines, limited to 2,000 characters each,
in memory and restores this tail on reconnect. Output is escaped as text and terminal colors are stripped.

The listener uses an authenticated, run-scoped loopback connection, bounded queues and a Java 8 compatible
helper JAR; it does not package another JUnit runtime into the customer application. Gradle callbacks disable
configuration caching for that test invocation, but leave up-to-date checks and the test result cache intact.
Telemetry is best-effort and cannot change a test result. The build command's exit status and fresh test
reports remain authoritative for completion, including failures outside individual test methods.

### Console push protocol

`/_fluxzero/dev/updates` is a local, same-origin WebSocket endpoint (protocol version 1). Like Dashboard, the
UI publishes incoming updates to its DOM handlers; commands and queries remain HTTP requests behind their
existing DOM handlers. Opening a connection returns `{type: "snapshot", version, sequence, status, environments}`;
`status.resourceHistory` contains the bounded resource history. Subsequent `{type: "update", ...}` frames carry
only changed top-level status fields, an optional changed environments list, and an optional new `sample`.
Test output uses `output: {firstSequence, lines}` with only newly appended lines; clients evict older lines
using `firstSequence`; zero clears the output. Snapshots include the complete retained `status.testOutput` tail.
Status objects within a changed field are replacements, not recursive patches. Heartbeats carry the current
sequence. Reconnect always returns a fresh snapshot; clients replace old state and reconnect on sequence gaps.

Sampling is shared across all clients and continues without a browser. Test run state transitions request an
coalesced refresh within 100 ms; OS memory is sampled at most every five seconds. The UI no longer polls status or environments.
The server accepts at most 32 sockets and disconnects stalled clients with bounded outgoing queues (16 frames,
1 MiB of text) and a ten-second send deadline. The UI detects silent connections within 30 seconds and retries
with exponential backoff up to 30 seconds. The channel accepts no commands; lifecycle actions retain the
same-origin HTTP protections and flush their acceptance before a gateway restart.


---

<p align="center"><strong>Are you a builder or coding agent?</strong><br>We welcome your ideas, issues, and pull requests!</p>

<p align="center">
  <a href="https://github.com/fluxzero-io/fluxzero-sdk-java"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/sdk-light.svg" alt="SDK — Connect your code to Fluxzero"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-agent-plugins"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/agents-light.svg" alt="Agent plugins — Guide your coding agent"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-cli"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/cli-light.svg" alt="CLI — Create, run, and deploy apps"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-dev-server"><picture><source media="(max-width: 520px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-mobile-dark.svg"><source media="(max-width: 520px)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-mobile-light.svg"><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/21a1ad90e2cd306a35b6f7f9f969f500e99dd70a/assets/brand/2026-09/profile/dev-server-light.svg" alt="Dev Server — Develop and test locally"></picture></a>
</p>

<p align="center">
  <a href="https://fluxzero.io/">Website</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/how-it-works">How it works</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/docs">Docs</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/about">About us</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/contact">Contact us</a>
</p>

### Stop and start from the dashboard

The workspace page offers **Stop** with two scopes. **Workspace** is the default: it stops managed apps, frontends, builds, tests and supporting services, while retaining the dashboard controls and workspace ownership. Choose **Start** (a single action without a dropdown) to initialize the workspace again on the same URL and with its current profile. In-memory application data is lost; monitoring history stored on disk is retained.

**Everything** also closes the dashboard and exits the dev server. Start again with `fz dev` in the workspace, or through another running workspace’s dashboard. Both stop actions require confirmation. The complete shutdown scope is never saved as a default.

A workspace whose dashboard remains available has session status `idle`; its gateway and heartbeat remain active, and its application, Test Server, MCP and supporting services are stopped. `fz dev` resumes it and `fz dev stop` closes it completely.
