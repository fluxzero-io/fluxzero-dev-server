## Development Feedback Loop

The bundled `fluxzero-dev` MCP server starts with `fz mcp`. Documentation and
`get_status` work without a project environment. When `get_status` returns
`dev-server-not-running` or `dev-server-unavailable`, call `start_dev` with no arguments
on this same MCP connection when development is needed. It starts or reuses the background
project environment; directory validation remains in force. If it reports `dev-server-starting`,
poll `get_status` until a session is available. On `dev-server-start-failed`, inspect the startup
diagnostics, correct the cause and retry `start_dev`. Fetch a fresh status and cursor before
waiting for project events. Status and documentation calls never start development themselves.

`start_dev` already selects background ownership; it needs no interactive detach
action. If the task requires a CLI or local-build launch instead, select background
mode using that launcher's current help. Bare `fz dev` attaches a terminal whose
closure stops the environment. The shared bootstrap isolates background servers
from the launching terminal/session before reporting startup; no agent-specific
shell wrapper is needed. After the launching command has finished, check fresh
project status and the application URL before
handing it to the user. Preserve the startup logs and session identity if the
process disappears; do not mistake it for a missing background flag or silently
start a second environment.

The active dev environment exclusively owns source watching, compilation,
application and local support-service replacement, configured startup commands,
and background test execution. Do not start a second build, test process,
application, watcher, or unbounded log follower in parallel with it.
The Dev Server also owns test selection and timing. Observe the tests it starts;
do not manually rerun a selected test, trigger a fresh test merely to refresh
evidence, run existing regression tests as an extra check, or run the whole
suite after edits. CI owns full regression coverage.

The MCP control plane can connect while applications, frontends, or support
services are still starting. On the first connection, call `get_status`
immediately. If startup is not ready and the status reports a non-zero problem
count, call `get_active_problems` immediately.
Follow `wait_for_change` from that status cursor until the environment becomes
ready or a concrete failure is reported. Do not wait for an MCP startup timeout
before inspecting progress, and do not start a second dev environment as a
diagnostic fallback.

For each implementation iteration:

1. Call `get_status` and remember its session ID and cursor before editing.
2. Make one coherent source or test change.
3. Call `wait_for_change` with that cursor. Inspect the returned structured
   events, advance to its returned cursor, and wait again while work relevant to
   the edit is still in progress. Do not stop merely because the first
   `source-changed` or `compile-started` event arrived.
4. For a backend change, wait through compile/reload. If the Dev Server starts a
   relevant test run, follow its lifecycle event to `passed` or `failed` and
   corroborate it with `get_test_status.tests`. If it selects no tests, a stable
   compile/reload with no new problem is the terminal state; do not invoke the
   wrapper to manufacture a fresh green result. When adding or changing a test,
   make the Dev Server's resulting run pass once. Do not rerun it after later
   unrelated edits unless the Dev Server selects it again. For a frontend-only
   change, follow the delegated frontend events and service state; do not require
   unrelated backend tests.
5. On a terminal failure or degraded service, inspect `get_active_problems`,
   then `get_test_status`, then only the bounded log slice needed for diagnosis.
   Fix the reported cause and repeat from a fresh status cursor.

### Empty-target initialization

1. Call `get_status` and confirm the intended directory, even if it reports
   `dev-server-not-running`. Use the docs tools before generation.
2. Run `fz init --in-place` in that exact empty target. Do not accept the default
   named-child layout and move it later.
3. If no project environment is running, call `start_dev` on the same connection.
   Poll `get_status` while startup is in progress and obtain a fresh session cursor.
   If a greenfield environment was already active, retain its pre-initialization
   cursor and session instead; generation does not require reconnecting it.
4. Follow `wait_for_change` until project discovery, startup, compile and test work
   reach terminal states. Corroborate with `get_status`, `get_test_status` and
   `get_active_problems`.

Direct wrapper commands are not a second verification loop. Use one only when
the dev environment explicitly reports that verification is unmanaged or the
user specifically requests the command. In that fallback, run only a new or
changed focused test once when needed to make it green. Do not run existing
regression tests or the full suite for extra confidence; CI owns that coverage.

## Before Finishing

Finish only after the cursored event loop reaches stable service states, every
test run actually started by the Dev Server has passed, and the active-problem
list is empty. A coherent edit for which the Dev Server selects no tests does
not require a manual test run. If structured verification is unavailable, state
why and run a new or changed focused test once only when its behavior still
needs proof. Leave full regression verification to CI.

If the result is not recognizably Fluxzero, repair it before answering. Do not
present a generic app as complete.
