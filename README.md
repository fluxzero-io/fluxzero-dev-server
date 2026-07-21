# Fluxzero Dev Server

The Fluxzero Dev Server provides a complete local development environment for Fluxzero applications. It starts
an embedded test runtime and proxy, launches one or more applications, performs rolling replacements after source
changes, runs affected tests in the background, and can manage a frontend development server behind one public
URL.

The server is normally launched through `fz dev`, the Fluxzero Maven plugin, or the Fluxzero Gradle plugin. This
repository contains the independently versioned server implementation and its standalone executable JAR.

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
fz dev list --json
```

The global index under `~/.fluxzero/dev/environments/` contains only project paths and session/process identity.
Current status, URLs, and application names are read from each project's `.fluxzero/dev/session.json`; MCP tokens,
resolved environment variables, and other secrets are never copied into the index.

Launchers resolve the latest compatible stable `1.x` release from Maven Central. A specific development or
snapshot build can be selected with `--dev-server-version` or `FLUXZERO_DEV_SERVER_VERSION` after installing it
in the local Maven repository.

The published Maven coordinates are:

```text
io.fluxzero.tools:fluxzero-dev-server:<version>
io.fluxzero.tools:fluxzero-dev-server:<version>:standalone
```

Every push to `main` that passes the cross-platform build, whole-application tests, frontend framework tests, and
release packaging validation produces a semantic release. A fresh-repository smoke test then verifies the
published Central artifact before the GitHub release is created. The first release is `1.0.0`; breaking launcher,
configuration, session, or control protocol changes require a new major version.

Dependabot tracks the Fluxzero SDK BOM independently. Non-major SDK updates are automatically merged only after
the full pull-request verification succeeds; that merge then produces a patch release of the dev server. Other
SDK releases do not trigger this repository directly.

### Release Repository Setup

Before the first `main` release, the GitHub repository must be public and have access to the same Maven Central
Actions secrets as the SDK repository: `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `OSSRH_SIGNING_KEY`, and
`OSSRH_SIGNING_PASSPHRASE`. The Dependabot secret store must contain
`DEPENDABOT_AUTOMERGE_APP_CLIENT_ID` and `DEPENDABOT_AUTOMERGE_APP_PRIVATE_KEY`; that GitHub App needs write
access to contents and pull requests in this repository. Central namespace ownership for `io.fluxzero.tools` is
shared with the Fluxzero CLI artifacts.

Project-level configuration belongs in `.fluxzero/dev.yaml`. Ephemeral session state, diagnostics, test impact
data, and combined logs are written below `.fluxzero/dev/` in the application project and should not be committed.

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
