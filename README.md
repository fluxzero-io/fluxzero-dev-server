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
java -jar target/dev-server-0-SNAPSHOT-standalone.jar --project-dir /path/to/project
```

For normal use, install the Fluxzero CLI and run this from the application project instead:

```shell
fz dev
```

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
