# Local Cloud journey fixture

The optional Cloud demo is for product and interaction review in Devboard. Enable it explicitly for a local
standalone process with `FLUXZERO_CLOUD_DEMO=true` (or `-Dfluxzero.cloud.demo=true`). It is disabled by default;
project configuration cannot enable it. The served shell advertises the mode, and the UI clearly labels simulated
Cloud data and actions. Native account credentials are neither read nor changed in this mode.

```sh
FLUXZERO_CLOUD_DEMO=true java -jar target/fluxzero-dev-server-1-SNAPSHOT-standalone.jar \
  --project-dir=/path/to/local/project --port=4743 --no-tests
```

Cloud navigation is revealed after sign-in, without navigating away from the local page. The navigation has one
entry; the scenario toolbar stays inside the design pages. Open the advertised Devboard URL and choose Publish live
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
| `select-source` | `repository`, `branch` | Select an allowed repository and immutable source context |
| `configure` | `application`, `cluster` (`existing`/`new`); legacy optional `environment` | Prepare a destination |
| `review` | — | Create a revision- and expiry-bound proposal |
| `deploy` | `proposalId` | Apply that proposal once and start a simulated operation |
| `configure-domains` | `applicationDomain`, `authDomain`, `emailDomain` | Prepare example DNS records |
| `check-domain` | `kind` (`application`/`auth`/`email`) | Verify a fixture domain |
| `test-auth`, `test-email` | — | Local sign-in outcome / captured message preview |

Deployment progress is derived from a clock across build, publication, acceptance, rollout and application check.
It performs no work on user code. Proposal expiry, changed organization/configuration, permission loss and capacity
checks reject stale application; repeating an accepted proposal returns the same operation.

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

The Publish live route is sign-in → organization → GitHub source/CI → destination → reviewed deployment → progress
and checked URL. Setup and review remain under publish routes; App preview and Progress receive no publication toolbar.
New organizations in this fixture use Starter capacity, a default team and a proposed new cluster. Starter exists
in Dashboard's versioned capacity catalog with one Starter app and cluster; the catalog entry is not activated.
The fixture asserts no live offer, price, billing activation or provisioned capacity. Existing targets are reused.

Dashboard owns organization, plan, membership, Integration and shared domain/sign-in/email management. A plan or
shared-service action is an explicitly simulated browser handoff, followed by an updated summary here. Domains
are not separate app-owned setup wizards. Teams contain clusters; clusters contain applications. Integrations
are system user profiles associated with an organization and authorized for the target. There is no required
Cloud Production/Staging entity: the legacy fixture environment field is retained only for protocol compatibility.

The GitHub setup disclosure shows a deploy-job fragment based on the public jwt-action v2 and deploy-action v1
inputs. It retains the project's existing immutable build/publication, requests GitHub OIDC, and uses deploy-token
for deployment. Repository variables carry cluster name, application name and image name; github.sha must match
the published tag. A live implementation must discover the actual workflow, its ref/environment claims, selected
Integration and published image identity before reporting CI as ready. Nothing here creates a workflow, variable,
GitHub permission or Integration. The Cloud adapter must produce a reviewable patch or instructions and verified
provider evidence before starting a real pipeline.

Workspace status adds optional per-application sdkVersion/sdkVersionSource fields for versions detected from the
project build. These differ from the Test Server version and are omitted for overrides or unknown/fallback builds.
Test Server memory is displayed separately; other-dev-services totals exclude it, including resource graphs.
