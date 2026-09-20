## Showing and Testing the App

Introduce Devboard once the first usable app is running and you have checked it.
Resolve the current project URL as described below and link directly to
App preview, with one concrete thing the user can try. For backend-only projects,
introduce the relevant page after the first verified functional scenario. Mention
Progress briefly as the record of completed and upcoming work. Link again when a
meaningful result makes a page useful, or when the user asks; avoid repeating a
generic dashboard link after every edit.

Prefer App preview for normal browser-based UI checks so the user and agent see
the same context. For mobile checks, resize the browser viewport: the Devboard
sidebar already hides at narrow widths, so expanded mode is not required.
Use a standalone app tab when iframe behavior would distort what you need to
verify, such as authentication, top-level navigation or downloads. This is a
default workflow, not a restriction on choosing the right testing surface.


# Open Devboard

1. Use the plugin's `fluxzero-dev` MCP connection. Call `get_status` and
   check the returned project directory against the user's intended project.
   If it differs, use `select_project` when available with the confirmed app
   directory, then read status again. Resolve ambiguity before opening or
   starting a different project. Never choose a project from a remembered port.
2. Reuse the running environment. If this project has no running dev server,
   use `start_dev` on that connection to start it, then follow `get_status`
   until the gateway is available or startup fails. Do not restart an active
   environment just to open Devboard. If the CLI or MCP is unavailable, use the
   plugin's installation guidance; do not invent a launch command.
3. Get the live URL from the returned environment. Prefer an explicitly returned
   `consoleUrl`. Otherwise, a session gateway advertising
   `metadata.devConsoleVersion: "1"` serves Devboard at
   `<session.gateway.url>/_fluxzero/dev/`. If neither is available, explain that
   this server does not expose a supported Devboard instead of guessing.
4. Open the requested page: `#application` for App preview (the default),
   `#projects` for Workspace, `#progress`, `#tests`, or `#startup`.
   Reuse a matching browser tab when possible. Respect the user's chosen browser;
   otherwise use the host's available browser-opening tool. Verify the resulting
   page when browser inspection is available and leave it open for the user.
5. Return a concise clickable link. If opening is unavailable, provide the link
   without claiming to have opened it. A localhost URL is only reachable on the
   machine running the dev server; for a remote session explain that limitation
   rather than exposing a port or changing network access.

Opening Devboard does not require rerunning tests, changing project data, or
updating Progress. Keep this action focused on showing the requested page.
