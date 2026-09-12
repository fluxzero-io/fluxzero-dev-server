<a href="https://fluxzero.io"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/3fa8f79df95d07678a730147bc1bd0402ae660d5/assets/brand/2026-09/repository-header.svg" alt="Fluxzero — The European cloud for AI-built apps" width="1280"></a>

# Fluxzero Dev Server

Local development server for [Fluxzero](https://fluxzero.io) applications.

The Fluxzero Dev Server provides a complete local development environment for Fluxzero applications. It starts
a version-aligned test runtime and proxy, launches one or more applications, performs rolling replacements after source
changes, runs affected tests in the background, and can manage a frontend development server behind one public
URL.

The server is normally launched through `fz dev`, the Fluxzero Maven plugin, or the Fluxzero Gradle plugin. This
repository contains the independently versioned server implementation and its standalone executable JAR.

The agent control plane may start in a completely empty greenfield workspace before project generation. In that
phase only session management, MCP, and a source watcher run; `get_status` reports `waiting-for-project` and directs
the agent to generate the project in the same root. When a Maven or Gradle build appears, the server reloads the
new project configuration and starts the normal runtime, proxy, IDP, application, and test lifecycle without
replacing the MCP session. Non-empty directories without a build root remain invalid.

## Requirements

- JDK 21 or newer
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

The build creates both the regular Maven artifact and an executable standalone JAR under `target/`.

The build uses an exact published Fluxzero SDK version. It deliberately does not locate or build a sibling SDK
checkout. Override `-Dfluxzero.version=...` only when verifying against another installed or published SDK
version.

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

Documentation follows each project's declared SDK version, independently of the shared test runtime and its
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

### Runtime Version Alignment

The dev server detects the Fluxzero SDK version declared by each Maven or Gradle build and resolves the matching
`io.fluxzero:test-server` and `io.fluxzero:proxy` artifacts from Fluxzero Packages. Maven Central remains
available for third-party dependencies. Runtime and proxy share one isolated
child JVM, so their protocol generation cannot accidentally come from the SDK version embedded in the dev-server
release. Resolved classpaths are cached under `~/.fluxzero/cache/dev-runtime/<sdk-version>/`; normal warm starts do
not contact either repository again.

Projects in one environment must use the same Fluxzero SDK major generation. When compatible projects use different
versions within that generation, the newest version is selected for the shared runtime. The effective version and
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
Fluxzero runtime, proxy, identity provider, applications, compilation, tests, or startup commands. In this mode all
public HTTP and WebSocket traffic, including `/api` and `/_fluxzero`, is routed to the frontend so its own development
proxy can target a remote backend. Backend application settings and `backendPaths` are rejected to prevent a profile
from appearing to start components that it deliberately skips.

Use `projects` inside a profile when one local environment spans independent Maven or Gradle roots. Every named
project has its own directory, application selection, optional application configuration, compile pipeline, source
watcher, rolling replacement, and background tests. The projects share one Fluxzero runtime and gateway, while an
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
in-memory runtime; changed or failed commands are retried without re-running unchanged successful predecessors. The
conventional `src/test/resources/fluxzero/dev/commands/**/*.json` directory remains supported and runs after explicitly
configured commands in normalized path order.

The local dev runtime starts consumers without a stored position ten seconds before the current end of their log,
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
`{services.<id>.url}` and `{services.<id>.ports.<name>}`. HTTP or TCP readiness participates in startup, while service
health, process identity, logs, diagnostics, stale cleanup, and bounded shutdown remain part of the same session.

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


---

<p align="center"><strong>Are you a builder or coding agent?</strong><br>We welcome your ideas, issues, and pull requests!</p>

<p align="center">
  <a href="https://github.com/fluxzero-io/fluxzero-sdk-java"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/sdk-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/sdk-light.svg" alt="SDK — Connect your code to Fluxzero" width="150" height="68"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-agent-plugins"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/agents-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/agents-light.svg" alt="Agent plugins — Guide your coding agent" width="150" height="68"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-cli"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/cli-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/cli-light.svg" alt="CLI — Create, run, and deploy apps" width="150" height="68"></picture></a>
  <a href="https://github.com/fluxzero-io/fluxzero-dev-server"><picture><source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/dev-server-dark.svg"><img src="https://raw.githubusercontent.com/fluxzero-io/.github/d4ab6c7914b7e21d06336601bdf8d9dd6e4b725a/assets/brand/2026-09/profile/dev-server-light.svg" alt="Dev Server — Develop and test locally" width="150" height="68"></picture></a>
</p>

<p align="center">
  <a href="https://fluxzero.io/">Website</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/how-it-works">How it works</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/docs">Docs</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/about">About us</a> &nbsp;·&nbsp;
  <a href="https://fluxzero.io/contact">Contact us</a>
</p>
