# AGENTS.md

Instructions for coding agents working in this repository.

## Project Shape

This repository contains the standalone Fluxzero dev server, built with Maven and Java 21. It is a local
development product rather than an application dependency.

- `src/main/java/io/fluxzero/devserver`: orchestration for the embedded Fluxzero test runtime and proxy,
  application and frontend child processes, rolling reloads, background tests, startup commands, diagnostics,
  terminal attachment, and MCP access.
- `src/main/resources`: logging and runtime resources included in the standalone distribution.
- `src/test/java/io/fluxzero/devserver`: unit, lifecycle, process, gateway, and integration tests.
- `src/test/resources`: complete Maven/Gradle and frontend fixtures used to exercise real development workflows.
- `docs`: implementation decisions, plans, and developer-facing reference material.

The related repositories have deliberately separate responsibilities:

- `fluxzero-sdk-java` owns the Fluxzero SDK, test server, proxy, and their public embedded APIs.
- `fluxzero-cli` owns `fz dev` and the Maven/Gradle plugin launchers. Those launchers should remain thin and
  resolve this repository's standalone artifact.

Do not copy SDK, test-server, proxy, or CLI implementation into this repository to avoid a proper public
integration boundary.

## Build And Test

- Use the Maven wrapper: `./mvnw` on Unix and `mvnw.cmd` on Windows.
- The project compiles with `maven.compiler.release=21`.
- Full verification is `./mvnw -B clean install`.
- Run focused tests with `./mvnw -B -Dtest=ClassName test`.
- Run whole-application development workflow tests with `./mvnw -B verify -Pdev-server-e2e`.
- Run the real Vite and Angular gateway/HMR tests with
  `./mvnw -B verify -Pdev-server-frontend-e2e`. This profile requires Node.js and npm and may download fixture
  dependencies.
- Changes to shutdown, detachment, process trees, ports, filesystem watching, or terminal interaction require
  focused lifecycle tests. Cover Windows and Unix behavior where they differ.
- Changes to the shaded artifact or dependency graph must be verified against the standalone JAR, not only the
  Maven test classpath.

Keep the default test suite deterministic and reasonably fast. Expensive framework and whole-application tests
belong behind the existing opt-in profiles unless their cost can be reduced enough for every build.

## Architecture And Compatibility

- Treat `.fluxzero/dev.yaml`, `.fluxzero/dev/session.json`, diagnostics files, the MCP surface, environment
  variables, and launcher exit codes as compatibility boundaries. Version schemas before making incompatible
  changes.
- Use only public SDK, test-server, and proxy APIs. If a missing capability belongs to one of those components,
  add a small public integration point in the owning repository instead of reaching into internals here.
- Do not locate or build a sibling SDK checkout from this build or its tests. SDK compatibility is selected by
  the explicit `fluxzero.version` property and its artifacts must already be installed or published.
- Keep application compilation and application replacement separate. A failed compile or startup must leave the
  last ready application running.
- Preserve single-flight/coalescing behavior for compiles and tests. Rapid saves must make forward progress
  without creating unbounded work.
- Bound every shutdown and recovery path. The dev server must not leave child processes, occupied ports, or a
  terminal waiting indefinitely after exit.
- Never log, persist, expose through MCP, or include in diagnostics resolved secret values. Configuration may
  persist secret references such as `op://` URIs, but not their contents.
- Keep frontend support framework-neutral. Readiness and recovery must use process, HTTP, and websocket state,
  not Angular-, Vite-, or tool-specific log messages.
- The standalone server may orchestrate Spring Boot applications, but application detection and lifecycle must
  not depend on Spring Boot.

## Coding Guidelines

- Favor durable, well-tested behavior over local workarounds; this tool controls user processes and development
  data.
- Follow existing Java style: Apache license headers, standard imports before static imports, small cohesive
  types, and package-private tests where practical.
- Document public configuration and integration APIs. Internal orchestration types do not need ceremonial
  Javadoc when their role is clear from the code.
- Add dependencies deliberately and manage their versions in the root POM. Remember that every runtime
  dependency affects the standalone shaded JAR.
- Keep terminal output concise and actionable. Detailed output belongs in the combined log and structured event
  files; current unresolved problems belong in diagnostics.
- Avoid brittle sleeps in concurrent tests. Prefer observable readiness, bounded polling, latches, and explicit
  process state.
- Do not commit `target/`, generated local state under `.fluxzero/dev`, resolved secrets, or fixture dependency
  directories such as `node_modules/`.

## Commit Messages

- Use Conventional Commits with a clear scope: `<type>(<scope>): <imperative summary>`.
- Useful scopes include `lifecycle`, `compile`, `testing`, `frontend`, `gateway`, `commands`, `diagnostics`, `mcp`,
  `terminal`, `config`, `deps`, and `ci`.
- For non-trivial commits, explain why the change is needed and its observable impact in the commit body.
- Never use literal `\n` or `\r\n` sequences for commit-message line breaks. Supply real line breaks with
  separate `-m` arguments or a commit-message file and inspect the result.
