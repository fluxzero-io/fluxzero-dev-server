# Dependency maintenance

Dependabot maintains the root Maven dependency graph, GitHub Actions, the Devboard
frontend and both frontend framework fixtures. Each npm fixture has its own
manifest and lockfile. Fluxzero Maven releases come from the explicitly declared
`packages.fluxzero.io/maven` registry.

Every main-targeting PR, including every Dependabot update, runs the complete
platform matrix, Devboard tests, application and frontend E2E, and the standalone
Maven consumer qualification. The strict `Dev Server required checks` rule gates
the combined result. The merge workflow executes no PR code and enables native
rebase auto-merge only for minor/patch updates, using the dedicated merge App.
Majors remain for assessment. Keep the source reader separate from that App.

The existing Auditlog Contents-read App supplies private source input to both
ordinary CI and Dependabot CI. Its client ID and private key belong in their
respective secret stores as `AUDITLOG_APP_CLIENT_ID` and
`AUDITLOG_APP_PRIVATE_KEY`. Each job narrows its installation token to Auditlog
Contents read, disables persisted checkout credentials and revokes the token
afterwards. Fork PRs cannot use these secrets; qualify their complete distribution
on a trusted maintainer branch.

Auditlog's exact bundled source revision is maintained through its existing
release-bot PR. Review that input against the released source and run the packaged
monitoring consumer. A publication that has not passed Auditlog's activation
boundary is not automatically a replacement for the selected release input.

The Maven application fixtures also exercise historical application layouts and
older SDK/Spring Boot compatibility. Preserve those target versions when they
define the scenario; the plain-app fixture receives the SDK selected by the test.
Review their build-tool pins alongside Maven updates rather than migrating a
legacy application as an incidental dependency change.

TypeScript stays on 6.0.x because Angular's compiler and builder require
`>=6.0 <6.1`; assess TypeScript 7 when Angular supports it. The console build and
tests use the Maven-installed Node 24 LTS runtime. Review that exact Node patch
with build maintenance; its Maven plugin configuration is not an npm dependency.
