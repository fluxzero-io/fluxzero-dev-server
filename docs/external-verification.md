# External verification and frontend dependency changes

The dev server serializes its own compile and test work. An independent Maven/Gradle process does not share
that coordinator. Keep one owner of mutable build output: a successful compilation does not protect its class
files against a second process running `clean`, compiling, or rewriting classpath metadata.

## Full verification outside the managed loop

Use this sequence when a project requires a separate full suite before pushing:

1. Record the actual application directory, active profile and launch options. In a repository containing
   `app/`, operate on `app/`, not the repository root. Record whether the environment was running.
2. Use `fz dev stop --project-dir <app>` and wait for successful completion. If stopping fails, do not start
   an external build against the same output. Stop externally owned watchers separately if relevant.
3. Run the project's full verification through its wrapper. On Windows use an absolute path to `gradlew.bat`
   or `mvnw.cmd`; on Unix use the executable project wrapper. Preserve the build's exit code.
4. If the environment was running, restart using `fz dev start --background --project-dir <app>` with its
   original profile/options. Report a restart failure separately. A successful restart must not turn a failed
   verification into success.
5. Confirm the new session is ready and a build is active before browser acceptance.

This is stop/verify/start, not an in-memory pause: stopping discards the in-memory runtime. It is not an automatic
`pause/verify/resume` API. Save or reseed disposable development data as appropriate before using this workflow.
Do not restart an environment that was stopped before verification began.

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
- For an actual dependency change, stop the managed loop and any externally owned frontend process, install,
  then restart using the sequence above. A future coordinated install operation would need to stop the watcher
  before replacement and restart only after successful installation.
- A Gradle-managed Node toolchain covers Gradle's Node tasks. A frontend command beginning with bare `node`
  still uses the launcher's PATH. Configure an explicit executable or an appropriate project launcher when
  identical versions are required for both builds and the running frontend.

Use the existing build tool and wrapper when joining a repository. For a new shared project, agree on Maven or
Gradle before scaffolding. Do not introduce both build definitions merely because separate agents chose different
defaults.
