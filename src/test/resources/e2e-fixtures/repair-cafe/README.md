# Repair Café integration fixture

Unchanged application and tests from the R55 dual-client VM postflight.
`fixture-provenance.json` records the source and file hashes. This fixture needs
Java 25 and the published Fluxzero SDK 1.268.0. It implements registration,
priority queues, repair start/completion/cancellation and immutable history.

Run with the standalone dev server and enable monitoring in a temporary
`.fluxzero/dev.yaml` to generate real command/event/HTTP/metric traffic.
The app contains no customer data. Runtime state, build output and captured
benchmark data must stay outside this fixture directory.
