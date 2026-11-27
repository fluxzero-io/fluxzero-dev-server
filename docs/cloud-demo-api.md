# Local Cloud journey fixture

The optional Cloud demo is for product and interaction review in Devboard. Enable it explicitly for a local
standalone process with `FLUXZERO_CLOUD_DEMO=true` (or `-Dfluxzero.cloud.demo=true`). It is disabled by default;
project configuration cannot enable it. The served shell advertises the fixture mode to the client. Native account credentials are neither read nor changed in this mode.

```sh
FLUXZERO_CLOUD_DEMO=true java -jar target/fluxzero-dev-server-1-SNAPSHOT-standalone.jar \
  --project-dir=/path/to/local/project --port=4743 --no-tests
```

Cloud navigation is revealed after sign-in, without navigating away from the local page. The navigation exposes a small
Clusters / Applications / Integrations submenu with Overview on the Fluxzero Cloud heading. Scenario selection stays
in the local fixture API; Publish live and Cloud pages contain no scenario picker or manual status/setup footer. Open the advertised Devboard URL and choose Publish live
below Startup. This entry is available while signed out and starts with sign-in; Cloud shows live status and resources. Scenario selection resets only process-local fixture
state; browser refresh preserves it until the process stops. Local app state and files are independent. This does
not contact Cloud, GitHub, IDP, DNS, a cluster, a registry, billing or SMTP. Example DNS records, image digests,
URLs and account identities are illustrative. The application preview is a local dialog; no example hostname is
opened. Test email is an in-memory preview, with no delivery attempt.

## Fixture protocol

`POST /_fluxzero/dev/cloud-demo/action` accepts a JSON command, bounded to 4096 bytes. It requires the existing
exact local Origin, a loopback peer and `X-Fluxzero-Console: 1`. Disabled processes return 404. The response is a
version-1 snapshot with `mode: "demo"`; failures return a customer-safe `error` string. Unknown options are rejected.
The transport lives behind the injectable frontend `CloudApi` boundary. This is a design fixture contract, not a
published production API or an authenticated Cloud-MCP implementation.

| Action | Additional inputs | Purpose |
| --- | --- | --- |
| `status` | — | Read the snapshot without provider I/O |
| `scenario` | `scenario` | Reset to an advertised fixture |
| `sign-in`, `sign-out`, `unlock`, `recover` | — | Simulated account/recovery actions |
| `create-organization`, `select-organization` | `name` / `organizationId` | Choose a fixture organization |
| `join-organization` | — | Accept the example invitation |
| `choose-plan` | — | Legacy fixture proposal for a plan change |
| `confirm-plan` | — | Simulate returning from a completed Dashboard plan review; no real activation |
| `connect-github`, `cancel-github` | — | Simulate browser authorization completion/cancellation |
| `select-source` | `repository`, `branch` | Select an allowed repository/ref; this does not verify a commit |
| `configure-application` | `application`, `size` (`Starter`/plan-eligible `Small`), `cluster` (`automatic`/`existing`) | Save intended app settings without provisioning |
| `configure-pipeline` | — | Confirm fixture delivery settings, without committing/pushing or starting CI |
| `configure` | `application`, `cluster` (`existing`/`new`); legacy optional `environment` | Prepare a destination |
| `review` | — | Create a revision- and expiry-bound proposal |
| `deploy` | `proposalId` | Apply that proposal once and start a simulated operation |
| `configure-domains` | `applicationDomain`, `authDomain`, `emailDomain` | Prepare example DNS records |
| `check-domain` | `kind` (`application`/`auth`/`email`) | Verify a fixture domain |
| `test-auth`, `test-email` | — | Local sign-in outcome / captured message preview |

Legacy review/deploy commands and seeded scenarios retain their simulated clock-based outcomes for compatibility.
The current checklist never invokes them and reports no build/push success. It performs no work on user code. Proposal expiry, changed organization/configuration, permission loss and capacity
checks reject stale application; repeating an accepted proposal returns the same operation.

The additive `setup.workflowApplication` and `setup.workflowCluster` fields preserve the destination last saved
with `configure-pipeline`. Editing application settings keeps that baseline for the proposed Actions-variable diff;
changing repository/branch or organization clears it. These are fixture setup values, not observed GitHub variables.
The workflow popup shows deployment-step examples and those proposed variable changes. It neither reads nor writes
the local workflow or provider settings. Actual file discovery, Git diffs and agent-assisted edits belong to the live
Git/CI adapter, and must distinguish YAML changes from Actions variables and secret references.

## Data and actions required from live Cloud

The UI review should settle these boundaries before building the live adapter:

| Capability | Authoritative response / behavior required |
| --- | --- |
| Account and organization context | Existing Dashboard principal, current memberships and action capabilities; no email-based binding |
| Plan and resource summary | Current plan, used/available app and cluster capacity, active app/version, a few useful resource facts |
| Create/join/select | Existing organization/invitation operations, stable IDs, refreshed permissions and context after browser handoff |
| GitHub authorization | Bounded browser authorization intent, verified completion/cancellation, allowed repositories/branches and existing workflows/Integrations |
| Source and configuration | Immutable commit/artifact identity, safe configuration diff and secret references; never raw secret values |
| Proposal | Exact target and diff, resource/plan/cost impact, expiry/revision, required permissions and explicit confirmation |
| Apply and resume | Idempotent operation ID, revalidated permissions/capacity, stage outcomes, safe diagnostic reference, active/previous release and functional URL/check evidence |
| Domains and auth | Ownership challenge, exact DNS records (including optional TTL/priority), provisioning/certificate states and verified auth capability |
| Email | Sender/domain authorization, exact SPF/DKIM/DMARC requirements, current sending capability and an explicit sandbox/test preview |
| Recovery | Structured network, expired-session, denied-access, capacity and provider failures with appropriate retry/handoff actions |

CI retains its existing Integration/workload identity. Local project paths stay local, and connecting a project is
an explicit choice. A live adapter must use the shared native account boundary and Cloud-side authorization;
simulated booleans, role strings, prices, readiness or success messages cannot establish production rights or outcomes.
A future MCP adapter can expose the same domain operations; MCP transport details do not belong in customer screens.

## Compact journey and ownership

The first Cloud target is one Fluxzero application, including its frontend. A standalone frontend is supported
in the local Workspace overview; deploying it independently is outside this Cloud journey. Use a real Maven/SDK
fixture with a managed Test Server to review the first deployment route, rather than a frontend-only process.

The Publish live route is account → organization/plan → app/destination → GitHub workflow → commit/push.
It ends with Sign in / Dashboard while signed out and See your applications / Dashboard while signed in. Commit/push remains pending without evidence; no CI timeline is shown.
App preview and Progress receive no publication toolbar.
New organizations in this fixture use Starter capacity, a default team and a proposed new cluster. Starter exists
in Dashboard's versioned capacity catalog with one Starter app and cluster; the catalog entry is not activated.
The fixture asserts no live offer, price, billing activation or provisioned capacity. Existing targets are reused.

Dashboard owns organization, plan, membership, Integration and shared domain/sign-in/email management. A plan or
shared-service action is an explicitly simulated browser handoff, followed by an updated summary here. Domains
are not separate app-owned setup wizards. Teams contain clusters; clusters contain applications. Integrations
are system user profiles associated with an organization and authorized for the target. There is no required
Cloud Production/Staging entity: the legacy fixture environment field is retained only for protocol compatibility.

The current Publish live preview omits the workflow-instructions disclosure. The earlier fixture fragment is based
on the public jwt-action v2 and deploy-action v1 inputs. It retains the project's existing immutable build/publication, requests GitHub OIDC, and uses deploy-token
for deployment. Repository variables carry cluster name, application name and image name; github.sha must match
the published tag. A live implementation must discover the actual workflow, its ref/environment claims, selected
Integration and published image identity before reporting CI as ready. Nothing here creates a workflow, variable,
GitHub permission or Integration. The Cloud adapter must produce a reviewable patch or instructions and verified
provider evidence before starting a real pipeline.

Workspace status adds optional per-application sdkVersion/sdkVersionSource fields for versions detected from the
project build. These differ from the Test Server version and are omitted for overrides or unknown/fallback builds.
Test Server memory is displayed separately; other-dev-services totals exclude it, including resource graphs.

## Setup versus observed resources

The additive optional setup projection contains applicationConfigured, size, pipelineConfigured, workflowPath and
workflowStatus. The current fixture always reports workflowStatus=not_checked. These are setup facts, not proof of
a committed workflow, a pushed ref, a build or a running task. configure-application accepts no variable/secret values;
optional app settings stay in Dashboard. configure-pipeline neither provisions resources nor invokes legacy deploy.
Changing organization resets setup; changing app/source invalidates delivery configuration.

A selected automatic cluster is planned until it appears in availableClusters. An app without a matching deployment
shows Waiting for first deployment. Saving a different app draft cannot inherit the prior app's release. Known fixture
releases can be inspected in the Cloud submenu, without showing CI-stage progress. The live adapter must eventually
supply independently authorized resource observations and local workflow/commit/push evidence.

## Shared Cloud language and navigation

Publish live names the complete customer journey. GitHub workflow names the automation; deployment names the
actual rollout, matching the Cloud deployment tutorial. Image publication remains the registry step. Avoid an
additional Delivery or Publication concept in customer copy. A planned cluster can be created by the GitHub
workflow on the first deployment; it is not a resource until observed in Cloud.

The Cloud heading opens Overview and expands the submenu. Its separate chevron changes only expansion. The whole
heading shares the Cloud green hover/focus surface. Cloud icons match Dashboard: diagram-3 for clusters, box for
applications, robot for organization integrations, and cloud for the plan badge.

The shell exposes the public Dashboard URL from shared user/process account configuration before sign-in, including
in the fixture; the normal configured production default still applies. No credentials or account files are accessed.
Configured values are validated, query/fragment data is stripped in links, and invalid configuration disables the
Dashboard entry without blocking local development. Explicit unsafe account values cannot fall back to another URL. The organization plan badge uses only an
explicit known Free, Starter or Pro response for the selected organization. Missing organization, failed/loading
context, or sign-in alone cannot establish Free. Payment exemption cannot be used to relabel a Pro plan as Free.

Workspace derives Development profile exclusively from local status. Default configuration means no named profile
is active. The field remains visible with hidden navigation and causes no account, credential-store or Cloud I/O.

Workspace, Publish live and Cloud pages use the same page gutters and title typography as Startup. Cloud pages omit
the Fluxzero Cloud eyebrow and align organization/plan context to the right. The Cloud navigation uses the existing
transparent Dashboard logo. A signed-out Cloud page offers a direct person-icon Sign in action; it does not redirect
the user through publication setup. Publish live keeps the commit/push step without claiming a push or rollout, and
ends with Applications or Sign in plus a shared pill-shaped Dashboard link.
