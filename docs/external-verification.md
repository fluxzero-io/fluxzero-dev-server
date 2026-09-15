# External verification and frontend dependency changes

The dev server serializes its own compile and test work. An independent Maven/Gradle process does not share
that coordinator. Keep one owner of mutable build output: a successful compilation does not protect its class
files against a second process running `clean`, compiling, or rewriting classpath metadata.

## Full verification outside the managed loop

Use the standalone verification entry point from the same dev-server distribution as the running environment:

```powershell
java -cp C:\tools\fluxzero-dev-server-standalone.jar io.fluxzero.devserver.DevVerificationMain --project-dir C:\repo\app --timeout-seconds 1800 -- C:\repo\app\gradlew.bat --no-daemon check
```

```sh
java -cp /tools/fluxzero-dev-server-standalone.jar io.fluxzero.devserver.DevVerificationMain --project-dir /repo/app --timeout-seconds 1800 -- /repo/app/gradlew --no-daemon check
```

Use the actual application directory and project wrapper (Maven's `mvnw.cmd`/`mvnw` works too). This entry point
requires an already running local console. It does not start an environment or change its profile.

The operation waits for active managed compilation/tests and application replacement, holds all project build
coordinators, and stops managed frontend process trees. The last application and in-memory runtime stay alive.
Only after status reports `paused` does it execute the supplied argument vector in the application directory.
Changes observed during the pause remain queued. After the external command exits, frontends restart and queued
managed work continues. A failing command preserves its exit code; failure to resume after a successful command
is also a failure. Confirm frontend readiness and the active build before browser acceptance.

The timeout bounds the external command. On timeout or graceful interruption the helper stops its process tree
before resuming. If a writer cannot be stopped, the environment remains paused. Do not launch detached writers
from the command. Externally managed watchers and runners must be stopped separately.

### Console integration and recovery

The local console exposes same-origin, authenticated-by-origin POST actions
`/_fluxzero/dev/actions/pause-builds` and `/_fluxzero/dev/actions/resume-builds`, with the existing
`X-Fluxzero-Console: 1` header and exact console `Origin`. HTTP 202 means accepted, not completed. Poll
`/_fluxzero/dev/status.json`: `maintenance.buildPauseState` progresses through `pausing`, `paused`, `resuming`,
and `running`; check `maintenance.error` and wait for `maintenance.busy` to become false after resumption.
Concurrent maintenance is rejected with HTTP 409. Acquisition of lifecycle/build locks is bounded.

If the helper is forcibly killed, there is deliberately no timed automatic resumption that could race a surviving
writer. Stop/finish that writer first, then issue `resume-builds` and check status. Restarting the whole dev server
is a fallback and discards its in-memory data. Older distributions without these actions require the original
`fz dev stop --project-dir <app>`, external verification, and `fz dev start --background --project-dir <app>`
sequence with the original launch options. Never turn a failed verification into success because restart succeeded.

## Isolated output for an external Gradle runner

A separate build directory is additional protection, not permission to ignore shared writers. Configure the
verification mode in the project build itself. Redirect **all** project/subproject outputs, including main and test
classes, generated sources/resources, classpath metadata and test reports. Changing only test-report locations
leaves the classpath shared. Check plugins or custom tasks that write literal `build/` or other fixed paths.

For example, a project may expose a `verificationBuildRoot` Gradle property and derive each subproject's
`layout.buildDirectory` from that root plus its unique project path. This property is project configuration;
it is not a built-in Gradle or Fluxzero flag. Validate the actual task output locations before relying on it.

Keep runner `GRADLE_USER_HOME`, `--project-cache-dir` and verification outputs outside the watched application
source tree. A custom directory such as `build-agent` or a Gradle cache inside the watched tree may trigger more
builds. A separate Gradle cache does not isolate compiled classes by itself. Also isolate other shared resources,
including ports, generated frontend bundles and `node_modules`, or serialize access to them.

## Dependency installation with a running frontend

On Windows, a running native build tool such as esbuild can hold its executable open. `npm ci` replaces
`node_modules`, so it can fail with `EPERM` when Vite/esbuild still owns that file. Retrying the same installation
while the watcher remains active does not release the lock.

- Declare dependency-install inputs precisely: dependencies and lockfiles, install hooks, Node/npm versions and
  npm configuration. Ordinary source or build-script edits should not unnecessarily reinstall dependencies.
- For an actual dependency change, use the verification entry point above with the install command (for example
  an explicit Node executable, its npm CLI script, `--prefix <frontend-directory> ci`). It stops managed watchers
  before replacing dependencies and resumes the environment after the command. Check the install exit code and
  frontend readiness: a failed install may leave incomplete dependencies requiring repair. Stop externally owned
  watchers separately. This is an explicit coordinated operation; the server does not infer arbitrary custom
  Gradle task inputs or automatically rewrite dependency-install tasks.
- A Gradle-managed Node toolchain covers Gradle's Node tasks. A frontend command beginning with bare `node`
  still uses the launcher's PATH. Configure an explicit executable or an appropriate project launcher when
  identical versions are required for both builds and the running frontend.

Use the existing build tool and wrapper when joining a repository. For a new shared project, agree on Maven or
Gradle before scaffolding. Do not introduce both build definitions merely because separate agents chose different
defaults.
