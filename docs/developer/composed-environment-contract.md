# Composed Development Environments

This contract covers development setups that consist of more than one build project or frontend while sharing one
Fluxzero runtime, proxy, IDP, public gateway, MCP server, diagnostics store, and lifecycle.

## Configuration Shape

A named profile is a complete environment. `projects` are independent Maven or Gradle roots. `frontends` are routed by
the public gateway, with the longest matching path winning. Exactly one frontend must own `/` when frontends are
configured.

```yaml
version: 1
defaultProfile: dashboard
profiles:
  dashboard:
    environment: local
    port: 4200
    idp: external
    projects:
      dashboard:
        directory: .
        apps: [rebound-encrypted]
        applicationConfig:
          rebound-encrypted:
            application: rebound
            env:
              SPRING_PROFILES_ACTIVE: main
            secrets:
              ENCRYPTION_KEY: "op://Fluxzero Cloud/flux_cloud_flux-encryption-key/local encryption-key"
      auditlog:
        directory: ../fluxzero-auditlog/backend
        apps: [auditlog]
        applicationConfig:
          auditlog:
            application: auditlog
            applicationName: Auditlog
            namespace: fluxzero_mp_prod-logs
            env:
              TARGET_NAMESPACE: fluxzero_mp_prod-logs
              AUDITLOG_PUBLISHER: victorialogs
              VL_INGEST_DEBUG: "false"
              VL_INGEST_URL: http://localhost:9428
              VL_QUERY_URL: http://localhost:9428
    frontends:
      dashboard:
        path: /
        directory: frontend
        setupCommand: "npm install --prefer-offline --no-audit --no-fund"
        command: "npx ng serve --host 127.0.0.1 --port {port}"
      auditlog:
        path: /marketplace/logs/1
        directory: ../fluxzero-auditlog/frontend
        setupCommand: "npm install --prefer-offline --no-audit --no-fund"
        command: "npm run start-dashboard -- --host 127.0.0.1 --port {port}"
```

VictoriaLogs remains an explicit external dependency in this profile. Generic managed support services are a separate
capability from composing Fluxzero projects and frontends.

## Resolution Rules

- Profile-level `environment`, `port`, `idp`, lifecycle, and startup commands belong to the shared environment.
- Project directories and frontend directories resolve relative to the directory containing `.fluxzero/dev.yaml`.
- Every project compiles, reloads, watches, and tests independently. A failure in one project keeps the last ready apps
  from every project running.
- All application processes receive the same runtime and public proxy URLs. An application configuration can override
  its Fluxzero namespace without exposing that supervisor-owned variable through `env`.
- Application launch ids are scoped by project id, so equally named modules in different roots cannot collide.
- Frontend route paths are preserved upstream. This is required for Angular applications whose `baseHref` includes the
  mounted path.
- HTTP and WebSocket requests use the same longest-prefix route selection. Backend paths such as `/api` take precedence
  over frontend routes.
- Startup is ready only when every selected project has a ready application and every configured frontend is ready.
  Compilation, application replacement, frontend recovery, and testing remain independent after startup.

## Backward Compatibility

Existing top-level configuration and existing profile bodies remain valid:

- `apps`, `applicationConfig`, build settings, and commands describe the primary project at `.`.
- `frontend` describes one frontend mounted at `/`.
- `frontend.backendPaths` remains supported and normalizes to environment-level backend routes.
- A profile cannot mix `projects` with primary-project fields or `frontends` with `frontend`; ambiguous configuration
  fails at startup.
- The additive shape remains `version: 1`. Unknown fields continue to fail instead of being ignored.

## Reference Acceptance

The Dashboard and Auditlog reference is complete when one `fz dev --profile dashboard` invocation:

1. starts Dashboard Rebound and Auditlog against one embedded test runtime;
2. serves Dashboard at `/` and Auditlog at `/marketplace/logs/1` through one public port;
3. routes Dashboard and Auditlog browser traffic through one Fluxzero proxy, including WebSockets;
4. recompiles and rolls only the project whose backend changed;
5. delegates frontend changes to only the matching frontend dev server;
6. keeps healthy projects and frontends available when another component fails; and
7. terminates every managed child process on stop.
