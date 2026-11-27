# Repair Café integration fixture

Maintained application fixture originally imported from the R55 dual-client VM
postflight. `fixture-provenance.json` records the original import and its hashes,
not the current dependency versions. The current versions are in `pom.xml`.

This Java 25 application implements registration, priority queues, repair
start/completion/cancellation and immutable history. Dependency updates are
supported: the required Application E2E job runs `./mvnw -B verify` in this
directory, including the domain, endpoint and Spring context tests.

Run with the standalone dev server and enable monitoring in a temporary
`.fluxzero/dev.yaml` to generate real command/event/HTTP/metric traffic.
The app contains no customer data. Do not commit runtime state, build output
or captured benchmark data.
