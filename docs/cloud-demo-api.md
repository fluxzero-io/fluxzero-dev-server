# Local Cloud journey fixture

The optional Cloud demo is for product and interaction review in Devboard. Enable it explicitly for a local
standalone process with `FLUXZERO_CLOUD_DEMO=true` (or `-Dfluxzero.cloud.demo=true`). It is disabled by default;
project configuration cannot enable it. The served shell advertises the mode, and the UI clearly labels simulated
Cloud data and actions. Native account credentials are neither read nor changed in this mode.

```sh
FLUXZERO_CLOUD_DEMO=true java -jar target/fluxzero-dev-server-1-SNAPSHOT-standalone.jar \
  --project-dir=/path/to/local/project --port=4743 --no-tests
```

Open the advertised Devboard URL and choose Fluxzero Cloud. Scenario selection resets only process-local fixture
state; browser refresh preserves it until the process stops. Local app state and files are independent. This does
not contact Cloud, GitHub, IDP, DNS, a cluster, a registry, billing or SMTP. Example DNS records, prices, image digests,
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
| `choose-plan` | — | Include a plan change in the proposal; no activation yet |
| `connect-github`, `cancel-github` | — | Simulate browser authorization completion/cancellation |
| `select-source` | `repository`, `branch` | Select an allowed repository and immutable source context |
| `configure` | `application`, `environment`, `cluster` (`existing`/`new`) | Prepare a destination |
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
