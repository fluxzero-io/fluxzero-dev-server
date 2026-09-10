# Agent documentation API

Documentation is served locally by `DevMcpStdioMain`, launched through `fz mcp`. It needs an existing directory,
not an active dev server or build project. It starts no HTTP listener, source watcher, or project services and does
not depend on the dashboard. Project tools optionally connect to the loopback, bearer-protected `/mcp` endpoint.
That HTTP endpoint does not expose `docs_*`. CLI launch metadata may still live under `.fluxzero/dev/launcher`.

## Selecting documentation

All five tools accept these optional selectors:

| Field | Meaning |
| --- | --- |
| `namespace` | Component namespace; defaults to `sdk`. `docs_start` lists available namespaces. |
| `projectId` | Build project id from `docs_start.projects`; useful for composed environments. |
| `version` | Exact component version. Omit for the detected project SDK or latest-release fallback. |

Each response and article reference identifies its namespace and version. Responses also include `sourceCommit`,
`contentHash`, acquisition `source` (`cache`, `downloaded`, or `local-archive`), and selection mode
(`project-sdk`, `explicit-version`, or `latest-release`). Preserve the identity when following links or requesting another text page.

Without `version`, SDK selection reads declarations in Maven POMs and local parent/module POMs, or common Gradle
properties, scripts and version catalogs. It does not run a build or use generated classpath metadata. Dynamic or
externally inherited build models may need an explicit version. Detection is refreshed on each call; the shared
runtime's highest-version selection and `FLUXZERO_DEV_RUNTIME_VERSION` never select documentation.

For a single target without a known SDK version, the fallback resolves the newest stable numeric SDK release from
Packages Maven metadata. Responses and links use the concrete release version, never a mutable `latest` path.
An explicit version can also be read while `.fluxzero/dev.yaml` is invalid; omit `projectId` in that case.

If multiple selected projects have unknown or different SDK versions, `docs_start` returns `status: selection-required`,
project candidates, and an explanation without downloading anything. Other tools return an MCP tool error until
selection is resolved. Projects all declaring the same version can share a selection. With an explicit `projectId`,
an explicit version must match one of that project's detected versions. Omit `projectId` to intentionally inspect
another version, for example before generating a project or planning an upgrade.

## Selective retrieval

| Tool | Additional arguments | Result |
| --- | --- | --- |
| `docs_start` | None | Namespaces, project candidates, selected root summary and up to 20 root links. |
| `docs_search` | `query`, optional `limit` (default 5, maximum 20) | Ranked title and summary matches; no article bodies. |
| `docs_lookup_symbol` | `symbol`, optional `limit` (default 5, maximum 20) | Exact symbol matches, for example `@LocalOnly`. |
| `docs_read` | `path`, optional `offset` (default 0), `maxChars` (default 12000, maximum 24000) | Article text page, up to 10 links, total `linkCount`. |
| `docs_links` | `path`, optional `offset` (default 0), `limit` (default 20, maximum 50) | A page of links with namespace, version, path and description. |

`path` is the logical article path, such as `/docs` or `/docs/testing`; it is not a URL or local filename. Text offsets
count UTF-16 code units. Text and link pages return `hasMore` and, when needed, `nextOffset`. Use the returned offset
to avoid splitting a Unicode character. Search and symbol lookup report `hasMore` when additional matches exist;
narrow the query or increase the limit. Titles, summaries and link descriptions are also bounded.

Documentation tools are read-only, idempotent and marked `openWorldHint: true` because a cold cache may access
the package repository. A failed documentation lookup does not change project files or interrupt diagnostics.

## Archive and cache contract

The SDK publishes `io.fluxzero:fluxzero-sdk-java:<version>:agent-docs:zip`. The default source is:

```text
https://packages.fluxzero.io/maven/io/fluxzero/fluxzero-sdk-java/<version>/fluxzero-sdk-java-<version>-agent-docs.zip
```

The adjacent `.sha256` must contain the ZIP's SHA-256 hex digest. The reader validates that digest, schema version 1,
namespace, exact component version, source commit, internal content hash, article inventory, and graph connectivity.
The ZIP contains `manifest.json`, `release.json`, and registered `articles/*.md` files. Internal content hashing follows
the SDK producer: sorted paths excluding `release.json`, each followed by NUL, that file's SHA-256, and a newline;
SHA-256 of this UTF-8 sequence is `release.contentHash`.

Archives are read in memory without extraction. Limits are 8 MiB compressed, 2 MiB per entry, 32 MiB total expanded
content and 4096 entries. The central cache layout is `<cache>/<namespace>/<version>/agent-docs.zip` plus its checksum
and `resolve.lock`. Downloads are coalesced across threads and processes and files replaced atomically under the lock.
Corrupt entries are reacquired and validated. HTTP connections, requests and cache-lock waits are bounded; shutting
down the endpoint cancels active HTTP acquisition. No automatic cache eviction is performed. At most four parsed
graphs are retained in memory per stdio process; other versions remain available from disk.

Cached releases are treated as immutable and are not revalidated over the network. A missing artifact for a known
version never falls back to another release or the dashboard. GitHub release copies are backups for restoration.
For versionless bootstrap only, `<cache>/<namespace>/latest.json` retains the selected release, metadata source,
and last successful check, with `latest.lock` coordinating readers. Metadata is refreshed after one hour. When
refresh fails, the last resolved release can still use its cached graph. `releaseResolution` reports `source`
(`repository`, `cache`, or `offline`) and `checkedAt`, so offline metadata is not represented as freshly checked.
Uncached snapshots require an explicit local archive; timestamped Maven snapshot resolution is not implemented here.

## Local configuration

These settings belong to the stdio MCP process. JVM properties take precedence over environment variables.

| JVM property | Environment variable | Default |
| --- | --- | --- |
| `fluxzero.dev.docs.cacheDirectory` | `FLUXZERO_DEV_DOCS_CACHE_DIRECTORY` | `~/.fluxzero/cache/agent-docs` |
| `fluxzero.dev.docs.repository` | `FLUXZERO_DEV_DOCS_REPOSITORY` | `https://packages.fluxzero.io/maven/` |
| `fluxzero.dev.docs.sdk.archive` | `FLUXZERO_DEV_DOCS_SDK_ARCHIVE` | Unset |

The repository override accepts an HTTP(S) or `file:` Maven root, without credentials, query or fragment. Redirects
are not followed. The explicit local archive bypasses download and must still match the requested namespace/version
and pass graph validation. Its bytes are checked on every call so rebuilding a local snapshot takes effect immediately.
No sibling SDK checkout is discovered or built automatically. For example, set `FLUXZERO_DEV_DOCS_SDK_ARCHIVE` to
the absolute path of a locally produced SDK ZIP and select its exact `release.componentVersion` in `docs_start`.

To verify the standalone distribution against an explicitly supplied SDK archive without publication:

```sh
python3 .github/scripts/verify-agent-docs.py \
  --jar target/fluxzero-dev-server-1-SNAPSHOT-standalone.jar \
  --archive /absolute/path/to/fluxzero-sdk-java-<version>-agent-docs.zip
```

Add `--download` with a release-style version to test the Maven URL and checksum download through a temporary local
HTTP repository. Both modes reconstruct every article through stdio, detect a newly added project SDK, restart with
the repository stopped and the local archive override removed, and verify offline retrieval and EOF shutdown.
Add `--fz /absolute/path/to/fz --plugin-config /absolute/path/to/plugin/.mcp.json` to exercise the generated plugin
arguments through the real CLI, including one-shot `--ensure-dev` with closed stdin, connection from an existing
bridge, repeated start/reuse and explicit stop. This uses an isolated dev-server cache with a private `1.999.0`
alias for the supplied local JAR; it does not test a public release download or alter the installed plugin.
The script does not locate or build an SDK checkout.

## Project availability and lifetime

The stdio tool catalogue is stable: five documentation tools and five project tools are available before and after
project startup. `get_status` returns a normal structured `dev-server-not-running` or `dev-server-unavailable`
response when it cannot access a project environment. Other project tools return the same guidance as a tool error;
the diagnostics resource returns the availability object. These responses contain `projectDirectory`, a `start`
command/argument array, `stdin: closed`, and a short instruction. `docs_start.development` also reports project
availability. It does not start a server or perform an HTTP probe just to read documentation.

To start development, execute the indicated `fz mcp --ensure-dev --project-dir ...` once with stdin closed, then
call `get_status` on the original connection. Alternatively restart that connection with `--ensure-dev`. Avoid
leaving a second bridge running or changing the plugin's global default to start environments in every directory.
The dev server's project/empty-workspace guard remains in force; documentation access does not authorize scaffold
generation over existing files. Starting with an incompatible directory reports the existing startup error.

Project calls discover the current session and token lazily. They subscribe to diagnostics updates when connected
and re-establish that subscription on the next project request after a session changes. There is no background
project-directory scan or automatic project start. Cursor/session-change semantics remain those of the dev server.
EOF closes the stdio server, cancels document acquisition and closes its project client within bounded time.
The separately owned background dev server keeps its existing explicit stop and idle-timeout lifecycle.
