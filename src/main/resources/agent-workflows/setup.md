## Start Here

1. Inspect the workspace before editing and classify it:
   - **New or empty target:** generate a starter with the Fluxzero CLI.
   - **Existing Fluxzero project:** preserve its build tool, modules, source
     layout, configuration, and unrelated behavior. Never run `fz init` over it.
   - **Existing non-Fluxzero project:** preserve the repository and integrate
     Fluxzero incrementally using the project-setup docs. Do not replace the
     project with a generated starter unless the user explicitly requests a
     separate replacement application.
2. Use the bundled `fluxzero-dev` MCP server before making framework-level
   decisions. Start with the docs root/start tool when it is available.
3. Treat a successfully read documentation article as stable for this task.
   Remember its URL and reuse its guidance instead of rereading it. Reread only
   after an incomplete response, context loss, or a changed checkpoint/content
   hash.
4. Extract the framework topics from the task and search for each topic before
   traversing broad sections. Read focused results first, then follow links only
   for missing detail; do not read the whole graph before implementation.
5. Only for a new or empty target, verify that the installed CLI is the current
   stable release and run `fz upgrade` if it is older, before generating anything.
   This updates the bundled starter SDK; it does not upgrade existing projects.
   Then follow MCP project-setup guidance for the chosen build tool and language. Confirm the directory with `get_status`, then
   use `fz init --in-place` with the Java or Kotlin starter in that exact root.
   Prefer non-interactive flags when the brief determines the answers. Never create
   and move a named child project or initialize over an existing project.
6. For an existing project, detect the build tool and current Fluxzero SDK from
   its effective Maven or Gradle model before changing dependencies. Read the
   matching MCP setup article and make the smallest compatible build change.
7. Compare the `version` returned by `docs_start` with the project's SDK.
   A mismatch does not justify an SDK upgrade or downgrade. Select the matching
   version explicitly when necessary, preserving `namespace` and `version` in
   links and reads. If its artifact is missing, report that limitation instead
   of silently using another release. Before project generation, the latest
   published SDK is the fallback; the response identifies the concrete version.
8. Treat generated code as a starting point. Replace its generic package,
   example domain, endpoints, dependencies, and tests as required by the actual
   product brief; do not mistake successful generation for task completion.
9. Follow the MCP links for commands, aggregates, endpoints, testing,
   authorization, and live updates as needed.
10. If neither a Fluxzero project nor Fluxzero docs are available, stop and
   explain the setup problem. Do not continue by inventing a non-Fluxzero app.

