# Cloud UI adapter

Devboard uses the same Cloud resource pages, organization context and publishing checklist for every provider.
Views do not select a demo implementation or infer provider capabilities from a plan name. The data contract is
`CloudState` in `frontend/src/app/cloud-model.ts`; replace the `CLOUD_API` dependency-injection provider in
`frontend/src/app/cloud-api.ts` to connect another implementation of `CloudApi`.

## Provider contract

`call(command)` returns a version-1 snapshot. `status` reads context; all other commands must be advertised in
`capabilities.actions`. Unsupported commands are rejected locally before transport. Server authorization,
current membership, revisions, capacity and mutation safety must still be revalidated by the provider.

| Snapshot field | UI use |
| --- | --- |
| `availability` | `available`, `offline`, `forbidden` or `not_connected`; the latter offers existing Dashboard handoffs |
| `organizations`, `organizationId` | Authorized organization choices and confirmed selection; the selector appears with more than one organization |
| Organization `plan` | Explicit known plan only; sign-in never implies Free |
| `capabilities.actions` | Supported organization, application and GitHub/workflow actions |
| `applicationSizes`, `branches`, `repositories` | Provider-owned choices; views do not invent examples or derive sizes from plan labels |
| `setup` | Confirmed publishing configuration, separate from running resources |
| `deployment` | Observed deployment and version, separate from proposed configuration or a saved workflow |
| `setup.workflowContents`, `workflowSource` | Suggested, local or GitHub workflow contents for the read-only viewer |
| `setup.workflowApplication`, `workflowCluster` | Previous workflow settings used for the proposed Actions-variable comparison |

Native account state remains owned by `AccountConnection`. A provider snapshot does not sign the user in or
establish account continuity. A real provider must scope context and requests to the current native principal,
refresh on account/rights changes, bound provider calls and preserve the separation between expected settings
and observed deployment state. Credentials and DPoP keys belong in the local execution layer, not in this
browser-facing contract. Secret references and names may be supplied; resolved secret values must not be supplied.

`CloudConnection` keeps only accepted snapshots, guards stale responses and teardown, and polls while a returned
deployment is running. It has no mandatory demo-mode or scenario field. Organization selection waits for the
returned snapshot; failure keeps the previously confirmed organization. Both the Cloud toolbar and Publish live
step 2 render the shared `CloudContextComponent`: plan disc first, then organization name and optional selector.

## Unconnected and fixture providers

The default `UnconnectedCloudApi` returns `not_connected`, empty resources and no mutation capabilities. It
performs no provider requests. The shared screens then offer the existing native account and Dashboard handoffs;
they do not display invented organization, plan, workflow or deployment data.

The opt-in `FLUXZERO_CLOUD_DEMO=true` review server selects `DemoCloudApi` at the transport boundary. That adapter
owns scenario metadata, fixture account projection, example choices and suggested workflow contents. It strips
fixture-only fields before publishing `CloudState`. Scenario controls and the obsolete demo-only page are not
part of the static UI. See [the fixture protocol](cloud-demo-api.md).

Selecting this fixture does not connect live Cloud. A real adapter must supply actual authorized snapshots;
the injection boundary and shared views alone are not evidence of authentication, provider configuration or a
successful deployment.
