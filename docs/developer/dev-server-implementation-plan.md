# Fluxzero Dev Environment Implementation Plan

Last updated: 2026-07-10

## Summary

Bouw clean vanaf `main`, zonder spike-branch code te oogsten. Het productdoel is een lokale Fluxzero dev environment die test-runtime, proxy, lokale IDP, app-reload, seed commands en slimme tests orkestreert alsof lokaal deployen werkt zoals Fluxzero Cloud: nieuwe app-instance erbij, readiness afwachten, oude instance graceful weg.

Defaults:

- Nieuwe Maven module in deze SDK repo: `dev-server`, package `io.fluxzero.devserver`.
- Test-server en proxy draaien embedded in de dev-server JVM.
- Lokale dev services, zoals IDP, draaien managed vanuit de dev-server in plaats van vanuit app-code.
- De Fluxzero app draait als apart child process.
- De Fluxzero app krijgt standaard `ENVIRONMENT=local`, configureerbaar via `--environment` of
  `FLUXZERO_ENVIRONMENT`, zodat bestaande `application-local.properties` native meedoet.
- P0 compile gebruikt project-local Maven wrapper voor correcte annotation-processor/build-semantiek.
- CLI-integratie gebeurt in `/Users/rene/Git/flux/fluxzero-cli` als dunne launcher bovenop de SDK dev server.

Legend:

- `[x]` implemented and covered by focused verification.
- `[ ]` not done, or only partially done without the intended DoD/test coverage.

## Current Position

Phase 1 through 9, Phase 12 and Phase 13 are complete for their documented scope. The plan has been reframed from a standalone dev server to a full dev environment. The environment contract now covers project-local session ownership, configurable `ENVIRONMENT` with `local` as default, managed local IDP, retryable session-scoped seed commands, automated whole-app Java E2E coverage against a real app main, live smoke coverage against `flux-basic-java` and `flux-basic-kotlin`, and compile/app/readiness telemetry. Phase 11 has a P0 `javac-fast` implementation: Maven primes build metadata, every accepted compile publishes immutable app output, and opt-in Java source reloads compile the full main source set from the last active snapshot. Live speed validation against `flux-basic-java` improved hot reloads from roughly 3.0-3.4s with Maven-only to 1.3-1.8s total with `javac-fast`; the Phase 13 fixture measured successful hot replacements down to roughly 0.7-1.0s.

Next execution order:

1. Finish the remaining Phase 11 hardening: background Maven verification, richer session drift status and dedicated processor fixture coverage.
2. Finish CLI/Maven launchers.
3. Re-enable and tune background tests for real example-app development.
4. Broaden test-impact refinement.

## Phase 1: Dev Server Skeleton Met Dynamic Ports/Status

Goal: een lokale supervisor die test-server en proxy betrouwbaar embedded start op vrije poorten en een duidelijke status/session publiceert.

DoD status:

- `dev-server` module bouwt mee in de SDK reactor.
- `io.fluxzero.devserver.DevServerMain` kan lokaal gestart worden.
- Test-server start met port `0`; proxy start met expliciete runtime URL en port `0`.
- `.fluxzero/dev/session.json` bevat runtime/proxy URLs, ports, status en pid.
- Terminal toont compacte status: runtime, proxy, app, tests.

Backlog:

- [x] Slice 1.1: maak `dev-server` Maven module met minimale main en unit test.
- [x] Slice 1.2: start/stop embedded `TestServer` op dynamische poort en lees effectieve port.
- [x] Slice 1.3: voeg configurable proxy start API toe en start proxy tegen de test-server URL.
- [x] Slice 1.4: schrijf `.fluxzero/dev/session.json` atomisch.
- [x] Slice 1.5: terminalstatus en shutdown hook voor gecontroleerde cleanup.
- [x] Slice 1.6: tests voor port allocation, session JSON en lifecycle cleanup.

## Phase 2: App Process Lifecycle & Readiness

Goal: dev server kan de echte Fluxzero app main als child process starten met geinjecteerde dev-environment config, readiness herkennen en netjes stoppen.

DoD status:

- App start met env/system properties voor runtime/proxy URL, namespace en application name.
- App start standaard met `ENVIRONMENT=local`, zodat `application-local.properties` wordt geladen;
  `--environment` kan dit per dev environment overschrijven.
- App start de echte app main, bijvoorbeeld `com.example.app.App`, niet een `TestApp` die runtime/proxy zelf bootstrapt.
- App readiness betekent: Fluxzero app is connected/registered bij de test runtime.
- Startup failure laat runtime/proxy doorlopen en toont foutstatus.
- Stop is graceful met harde timeout.
- Whole-app fixture tests bewijzen readiness, startup failure en graceful rolling replacement met een echte Fluxzero app main.

Backlog:

- [x] Slice 2.1: app launch config en `ProcessBuilder` runner.
- [x] Slice 2.2: project classpath verzamelen via Maven dependency output.
- [x] Slice 2.3: app env/system properties injecteren.
- [x] Slice 2.4: readiness detectie op app registration/connectie.
- [x] Slice 2.5: graceful shutdown + hard timeout.
- [x] Slice 2.6: injecteer configureerbaar `ENVIRONMENT` (standaard `local`) en lokale auth properties zonder expliciete user config via app-code te vereisen.
- [x] Slice 2.7: documenteer en test met fixture/example dat `TestApp`-style runtime/proxy bootstrap niet het dev-server contract is.

## Phase 3: Compile Pipeline & Rolling Replacement

Goal: source changes leiden tot betrouwbare compile en zero-downtime lokale app replacement.

DoD status:

- Source/resource watcher detecteert wijzigingen.
- Compile is single-flight: lopende compile wordt niet gecanceld.
- Wijzigingen tijdens compile worden gecoalesced tot maximaal een volgende run.
- Compile failure houdt laatst werkende app live.
- Compile success start app N+1, wacht readiness af, stopt daarna app N.
- Repeated saves tijdens compile worden gecoalesced en recovery na compile failure is direct getest.
- Inner-loop Maven path is gesplitst: pom/missing dependencies blijven full Maven, gewone `src/main` changes draaien alleen `compile`, test-only changes triggeren geen app compile.
- Compile/app-start/readiness/switch timings worden in terminal/status zichtbaar gemaakt.

Backlog:

- [x] Slice 3.1: watcher met debounce en ignore-regels.
- [x] Slice 3.2: single-flight compile state machine met dirty flag.
- [x] Slice 3.3: build snapshot model met compile status/errors.
- [x] Slice 3.4: rolling app replacement: start N+1 before stopping N.
- [x] Slice 3.5: failure behavior: compile/startup failure houdt N live.
- [x] Slice 3.6: integratietest voor repeated saves en failed compile recovery.
- [x] Slice 3.7: fast-safe Maven path met dependency cache, test-only skip en reload telemetry.

## Phase 4: Background Test Pipeline

Goal: tests worden first-class dev feedback zonder de app reload flow te blokkeren.

DoD status:

- Changed test class draait zichzelf.
- Previously failing tests draaien opnieuw.
- Changed main code draait fallback tests.
- Test results verschijnen in status en breken de draaiende app niet.
- Full Maven verification blijft expliciet, niet automatisch in de inner loop.
- Non-blocking/coalesced behavior en retry van eerder falende selectors zijn direct getest.

Backlog:

- [x] Slice 4.1: file-change classificatie: main/test/resource/pom.
- [x] Slice 4.2: changed test class detection en test selector.
- [x] Slice 4.3: fallback planner voor changed app code.
- [x] Slice 4.4: background test runner met coalesced requests.
- [x] Slice 4.5: test status storage en terminal rendering.
- [x] Slice 4.6: tests voor plannerregels en non-blocking behavior.

## Phase 5: TestFixture Impact Index

Goal: Fluxzero-tests die eerder geraakt gedrag observeerden worden automatisch opnieuw gekozen bij relevante app wijzigingen.

DoD status:

- `TestFixture` publiceert gestructureerde observation events naast `FixtureTrace`.
- Een JUnit listener koppelt observations aan test identifiers.
- `.fluxzero/dev/test-impact.json` bevat test -> handlers/messages/web/document/schedule usage.
- Planner gebruikt index voor fallbackregels.
- Remaining gap: selectie is P0 class-based; topic/path/collection matching kan rijker.

Backlog:

- [x] Slice 5.1: `TestFixtureObservation` model en sink SPI.
- [x] Slice 5.2: hook observation in bestaande dispatch/handler interceptors.
- [x] Slice 5.3: JUnit listener die observations per test wegschrijft.
- [x] Slice 5.4: `test-impact.json` merge/update logic.
- [x] Slice 5.5: planner gebruikt observed handler/message impact.
- [x] Slice 5.6: tests met twee fixtures en gerichte affected-test selectie.

## Phase 6: Session Ownership & Dev Environment Status

Goal: een project heeft maximaal een actieve dev environment, stale sessies worden betrouwbaar herkend, en extern gekillde processen worden zichtbaar in status/logs.

DoD status:

- `.fluxzero/dev/session.json` is project-local en gebaseerd op een genormaliseerde absolute project directory.
- `.fluxzero/dev/session.lock` voorkomt twee actieve dev environments voor hetzelfde project.
- Session bevat `sessionId`, dev-server pid, service pids, URLs, `startedAt` en `heartbeatAt`.
- Bij startup wordt een levende sessie geweigerd of expliciet overgenomen met een future `--replace`/`--cleanup-stale`-achtige optie.
- Stale sessies en orphan app/frontend processen worden herkend en gecontroleerd opgeruimd.
- Als app/frontend extern stopt, wordt session/status bijgewerkt zonder runtime/proxy meteen te slopen.

Backlog:

- [x] Slice 6.1: normalize project directory voor alle session/dev-artifact paths.
- [x] Slice 6.2: session lock met `FileChannel.tryLock`.
- [x] Slice 6.3: heartbeat writer.
- [x] Slice 6.4: session id, dev-server pid, service pids en service ownership opnemen in session JSON.
- [x] Slice 6.5: external app/frontend process exit monitoring en status updates.
- [x] Slice 6.6: tests voor duplicate server, session JSON en command/session persistence.
- [x] Slice 6.7: stale-session detectie en veilige orphan cleanup.

## Phase 7: Managed Local IDP

Goal: lokale authentication hoort bij de dev environment, niet bij app bootstrap code.

DoD status:

- Dev-server kan een lokale IDP managed starten op een vrije poort.
- IDP issuer/client/dev-login config wordt in de app geinjecteerd via env/system properties.
- Examples kunnen met `com.example.app.App` draaien zonder `TestApp` runtime/proxy bootstrap.
- Session JSON en terminalstatus tonen IDP URL/status.
- Managed IDP ondersteunt authorization-code, token en userinfo flow via de dev proxy.
- Een echte app endpoint kan de managed IDP access token accepteren via de SDK `OidcUserProvider`.
- IDP failure laat runtime/proxy/app status begrijpelijk achter.
- Apps met een eigen externe IDP kunnen `--idp external` gebruiken; de dev-server start dan geen test-IDP en injecteert geen `fluxzero.auth.oidc.*` overrides.

Backlog:

- [x] Slice 7.1: bepaal/maak managed local IDP start API.
- [x] Slice 7.2: start managed local IDP als Fluxzero dev service achter de dynamische proxy en lees issuer/effective URLs.
- [x] Slice 7.3: injecteer `fluxzero.auth.*` dev properties in app process.
- [x] Slice 7.4: session JSON en terminalstatus uitbreiden met IDP service.
- [x] Slice 7.5: test managed IDP authorization-code/userinfo flow via proxy zonder `TestApp`.
- [x] Slice 7.6: test met authenticated app fixture endpoint zonder `TestApp`.
- [x] Slice 7.7: smoke test met `flux-basic-java` en later `flux-basic-kotlin`.
- [x] Slice 7.8: expliciete `managed`/`external` IDP-mode via CLI, environment en Maven-plugin, met lifecycle- en app-processbewijs dat external mode geen lokale IDP of auth-overrides activeert.

## Phase 8: Dev Seed Commands

Goal: een lege lokale test-runtime wordt automatisch bruikbaar door retryable app seed commands uit het project.

DoD status:

- Commands staan conventioneel onder `src/test/resources/fluxzero/dev/commands/**/*.json`.
- Dev-server voert commands pas uit nadat runtime draait en app ready is.
- `.fluxzero/dev/command-status.json` bewaart per command path + content hash de laatste status.
- Succesvolle commands worden niet opnieuw uitgevoerd zolang de inhoud gelijk blijft.
- Mislukte commands en hun geblokkeerde opvolgers worden opnieuw geprobeerd na een succesvolle app reload.
- Logs/status leggen uit welke commands draaien, slagen, falen of opnieuw geprobeerd worden.
- Commands worden sequentieel uitgevoerd in lexicografische volgorde van hun genormaliseerde relatieve pad; numerieke prefixes maken de bedoelde bootstrap-volgorde zichtbaar.
- Een mislukte command blokkeert alle nog niet uitgevoerde opvolgers. Een retry hervat bij de failure en gaat pas daarna verder.

Backlog:

- [x] Slice 8.1: command file discovery en stabiele path/hash identity.
- [x] Slice 8.2: command status storage in `.fluxzero/dev/command-status.json`.
- [x] Slice 8.3: command executor die typed JSON commands via de runtime command gateway submit.
- [x] Slice 8.4: retry pending/failed commands na app readiness.
- [x] Slice 8.5: terminalstatus en logs voor command bootstrap.
- [x] Slice 8.6: test voor succesvolle command discovery/submission/status.
- [x] Slice 8.7: tests voor command failure en retry after reload.
- [x] Slice 8.8: test voor changed hash op hetzelfde command-bestand.
- [x] Slice 8.9: cross-platform stabiele command-order op genormaliseerd relatief pad.
- [x] Slice 8.10: fail-fast execution met expliciete `blocked` status en blocker-detail.
- [x] Slice 8.11: tests voor order, failure, blocked opvolgers en resume zonder eerdere successes opnieuw uit te voeren.

## Phase 9: Whole-App Dev Environment E2E

Goal: de echte productervaring is bewezen met volledige Fluxzero apps waarvan sources tijdens runtime wijzigen.

DoD status:

- Testapp gebruikt de echte app main en bevat geen embedded runtime/proxy bootstrap.
- Dev environment start runtime, proxy, eventueel IDP, app en seed commands.
- Source wijziging leidt tot compile, app N+1 readiness en graceful stop van app N.
- Compile/startup failure houdt de laatst werkende app live.
- Pending seed commands worden na een nieuwe ready app opnieuw geprobeerd.
- Automated Java fixture tests zijn groen; live Java/Kotlin example smoke tests zijn handmatig uitgevoerd tegen tijdelijke kopieen van `flux-basic-java` en `flux-basic-kotlin`.

Backlog:

- [x] Slice 9.1: fixture app template voor Java whole-app tests, gekopieerd naar tijdelijke project directory.
- [x] Slice 9.2: start dev environment tegen fixture met echte app main.
- [x] Slice 9.3: mutatie van source bewijst rolling replacement.
- [x] Slice 9.4: compile/startup failure recovery test.
- [x] Slice 9.5: seed-command retry test gekoppeld aan app reload.
- [x] Slice 9.6: handler toevoegen en verwijderen in een draaiende app.
- [x] Slice 9.7: live smoke test met `flux-basic-java`, daarna `flux-basic-kotlin`.

## Phase 10: Frontend Adapter & Launchers

Goal: dev environment wordt bruikbaar als productervaring zonder frontend stack te dicteren.

DoD status:

- Frontend kan `none`, `external-url` of `command` zijn in de SDK dev server.
- Voor `command` kan de dev server een custom frontend command starten.
- Session JSON bevat proxy status/URL en frontend status/URL voor external frontend.
- CLI, Maven launcher en frontend URL-detectie zijn afgerond in Phases 14 en 17.
- Gedeelde projectdefaults staan versioned en Git-trackable in `.fluxzero/dev.yaml`; runtime state blijft onder de genegeerde `.fluxzero/dev/` directory.

Backlog:

- [x] Slice 10.1: frontend config model en `none/external-url/command`.
- [x] Slice 10.2: frontend process lifecycle en log/status integratie.
- [x] Slice 10.3: session JSON bevat frontend URL en Fluxzero proxy URL.
- [x] Slice 10.4: `fluxzero dev` command in CLI als thin launcher.
- [x] Slice 10.5: `fluxzero:dev` Maven mojo als fallback launcher.
- [x] Slice 10.6: smoke tests voor CLI/Maven launcher behavior.
- [x] Slice 10.7: versioned projectconfig voor apps, environment, gatewayport, IDP-mode, fast compiler en frontend; precedence `CLI > environment > dev.yaml > defaults` met strict schema validation.

## Phase 11: Maven-Correct Fast Compiler

Goal: combineer Maven-correctheid met een veel snellere inner loop door Maven als build-oracle te gebruiken, maar gewone Java source changes via een direct compiler pad naar immutable per-build app output te compileren.

DoD status:

- Maven blijft de bron van waarheid voor runtime dependency cache en compile classpath metadata.
- Bij dev-server start draait een priming Maven build die de app in een volledig correcte baseline brengt.
- Gewone `src/main/java` changes kunnen opt-in via een fast compiler path worden verwerkt zonder Maven lifecycle startup.
- De P0 fast compiler compileert bewust de volledige main Java source set, niet alleen changed files, zodat Java-constant inlining en afhankelijke classes correct blijven.
- Changes aan `pom.xml`, resources, ontbrekende metadata of onbekende paths vallen terug naar Maven.
- Fast compiler failures houden de laatst werkende app live en tonen duidelijke diagnostics; bij failure wordt Maven compile als correctness fallback gedraaid.
- Telemetry toont per reload of `maven-full`, `maven-compile` of `javac-fast` is gebruikt en waar de tijd zit.
- Remaining gaps: background Maven verification, rijkere drift/statusvelden en dedicated Lombok/annotation-processor fixture coverage.

Backlog:

- [x] Slice 11.1: Maven build-introspection P0: runtime dependency directory, compile classpath cache, generated source output, compiler release en classes output vastleggen na priming build.
- [x] Slice 11.2: change classifier uitbreiden met fast-eligible, maven-compile, maven-full en app-compile-skip redenen.
- [x] Slice 11.3: direct `javac` compiler runner voor Java source reloads met Maven-derived release/classpath/output.
- [x] Slice 11.4: P0 safety rules: pom/resources/missing metadata/onbekende app changes vallen terug naar Maven; fast failure verifieert via Maven compile.
- [ ] Slice 11.5: persistent compiler/daemon experiment voor lagere startup overhead dan losse `javac` processen.
- [x] Slice 11.6: rolling replacement koppelen aan `javac-fast` build snapshots en failure behavior gelijk houden aan Maven.
- [ ] Slice 11.7: background Maven verification na idle window of N fast compiles; drift/failure markeert fast path unhealthy en valt terug naar Maven.
- [ ] Slice 11.8: session JSON uitbreiden met compile mode, stale/drift status en last verification status.
- [x] Slice 11.9: whole-app E2E: simpele source change reloadt via `javac-fast` en blijft functioneel gelijk aan Maven.
- [ ] Slice 11.10: whole-app E2E: handler toevoegen/verwijderen, compile error, startup error en recovery met fast path + Maven fallback.
- [ ] Slice 11.11: Lombok/annotation-processor fixture test die bewijst dat fast path correct fallbackt of processor-aware werkt.
- [x] Slice 11.12: config flag toevoegen om fast compiler expliciet aan te zetten; default blijft Maven voor reproduceerbaarheid.

## Phase 12: Background Test & Impact Scenario Hardening

Goal: bewijzen dat background tests en impact-selectie zich productwaardig gedragen in realistische dev-loop situaties: gericht waar mogelijk, fallback waar nodig, en nooit met app rollback of onduidelijke status als tests rood zijn.

DoD status:

- Test-only changes draaien tests zonder app redeploy.
- Rode tests zijn zichtbaar in compacte terminalregels en `.fluxzero/dev/test-status.json`.
- Main-code reloads kunnen succesvol zijn terwijl tests rood worden; de nieuwe app blijft draaien.
- Test compile failures breken de app niet.
- Coalescing houdt voortgang tijdens lopende test-runs en neemt previously failing selectors mee.
- Impact index selecteert gericht voor meerdere handlers/payloads en valt terug voor gedeelde helpers.
- Stale impact bij handler removal leidt tot uitlegbare test failure/fallback, niet tot stille misselectie.
- Web/auth/document/schedule impact wordt minimaal planner-level bewezen; rijke runtime coverage mag daarna per feature worden verdiept.

Backlog:

- [x] Slice 12.1: test-impact precisie met twee handlers/twee tests plus helper fallback.
- [x] Slice 12.2: succesvolle app reload waarna tests rood worden zonder app rollback.
- [x] Slice 12.3: test compile failure zonder redeploy en met duidelijke status/log.
- [x] Slice 12.4: race/coalescing: meerdere saves tijdens actieve test-run plus previously failing selectors.
- [x] Slice 12.5: handler toevoegen/verwijderen met stale impact-index en duidelijke failure/fallback.
- [x] Slice 12.6: web/auth/document/schedule impact-selectie bewezen met planner/runtime-appropriate tests.

## Phase 13: Runtime Isolation & Lifecycle Correctness

Goal: de dev environment bewaakt dezelfde harde deployment-invarianten als Fluxzero Cloud: alleen de bedoelde ready app-instance wordt actief, iedere app draait uit immutable build-output, background tests kunnen een reload niet vervuilen, en alle session-owned state/processen hebben een eenduidige lifecycle.

DoD status:

- Iedere kandidaat krijgt een unieke Fluxzero client/task id; alleen een matching `ConnectEvent` maakt die kandidaat ready.
- Een readiness-timeout faalt de kandidaat en houdt app N actief.
- Active app en laatste reloadpoging hebben afzonderlijke status, zodat een candidate failure de PID/status van N niet wist.
- Iedere succesvolle compile publiceert een immutable classes-snapshot met een expliciete, versiezuivere runtime classpath.
- Maven compile heeft voorrang op background tests; een actieve test-run wordt gecontroleerd onderbroken, gecoalesced en opnieuw gepland zonder een rode teststatus te fabriceren.
- Seed-command-success geldt alleen binnen dezelfde dev runtime/session; een nieuwe embedded runtime voert dezelfde commands opnieuw uit.
- Nieuwe of gewijzigde commandbestanden worden binnen een draaiende session verwerkt zonder app-reload af te wachten.
- App/frontend/buildprocessen worden als process tree gestopt en stale frontend ownership is herkenbaar.
- Module-brede test failures leveren gerichte previously-failing selectors op waar Surefire-resultaten dat toelaten, met module fallback wanneer dat niet kan.

Backlog:

- [x] Slice 13.1: unieke app-instance id, strict readiness en timeout-failure zonder promotie.
- [x] Slice 13.2: active-app en candidate/reload status scheiden en PID van N behouden bij failure.
- [x] Slice 13.3: immutable per-build classes snapshot plus expliciete Maven runtime classpath.
- [x] Slice 13.4: compile-priority coördinatie met veilige cancellation/requeue van background Maven-tests.
- [x] Slice 13.5: seed status per session en directe trigger bij command-file changes.
- [x] Slice 13.6: owned process-tree shutdown en stale frontend cleanup.
- [x] Slice 13.7: module-run failures terugbrengen tot previously-failing selectors of veilige module fallback.
- [x] Slice 13.8: unit/E2E-bewijs voor readiness mismatch/timeout, snapshotisolatie, runtime restart seeds en process cleanup.

## Phase 14: Single-Origin Frontend Dev Gateway

Goal: browser, UI dev-server en Fluxzero backend vormen lokaal een enkele origin, zonder frontendkennis van dynamische Fluxzero-poorten, CORS-configuratie of een voorgeschreven frontendstack.

DoD status:

- Een dev environment met frontend publiceert een enkele browser-facing gateway URL.
- `/_fluxzero/**` wordt met prefix-stripping naar de interne Fluxzero proxy gestuurd; overige requests gaan naar de UI dev-server.
- `/api/**` wordt standaard zonder path-rewrite naar de Fluxzero proxy gestuurd, inclusief WebSocket upgrades; backend pass-through paths zijn configureerbaar.
- De frontend ontvangt een door de dev-server gekozen interne poort via environment en `{port}` command substitution.
- De gateway ondersteunt streaming HTTP, headers/cookies/redirects en transparante UI websocket upgrades voor HMR.
- UI startup/restart failure stopt runtime, proxy en app niet; de gateway geeft tijdelijk een uitlegbare unavailable response.
- App en managed IDP gebruiken de publieke gateway als external base URL, zodat interne poorten niet lekken.
- `session.json` en terminalstatus onderscheiden publieke dev URL van interne proxy/frontend URLs.
- Geautomatiseerde gatewaytests en echte Vite- en Angular-smokes bewijzen HTTP, backendrouting en HMR-compatibiliteit.

Backlog:

- [x] Slice 14.1: frontend config uitbreiden met dynamische port placeholder, internal URL en readiness.
- [x] Slice 14.2: `DevGateway` met een dynamische publieke poort en expliciete `/_fluxzero/**` route.
- [x] Slice 14.3: reverse HTTP proxying naar frontend en Fluxzero met correcte URI/header/cookie semantics.
- [x] Slice 14.4: transparante frontend websocket bridge voor HMR.
- [x] Slice 14.5: lifecycle, session/status en public external-base-url integratie.
- [x] Slice 14.6: unit/integratietests voor routing, prefix stripping, unavailable/recovery en process cleanup.
- [x] Slice 14.7: echte Vite smoke via de publieke gateway, inclusief HMR websocket.
- [x] Slice 14.8: echte Angular smoke via de publieke gateway, inclusief live-reload websocket.
- [x] Slice 14.9: configureerbare backend pass-through paths met `/api` default voor HTTP en WebSockets.
- [x] Slice 14.10: optionele publieke gatewaypoort via `--port`, `FLUXZERO_DEV_PORT` en Maven-pluginconfiguratie; een interactieve poortbotsing biedt vóór infrastructuurstart een dynamische fallback aan, terwijl non-interactive gebruik compact faalt.
- [x] Slice 14.11: frontend-HMR WebSocket requests herschrijven naar interne frontend Host/Origin, met upstream-headerbewijs; backend-WebSockets behouden de publieke Origin.
- [x] Slice 14.12: browser URL eerst als wachtend tonen en na echte frontend-readiness als prominente `OPEN IN BROWSER` call-to-action publiceren.
- [x] Slice 14.13: environment-shutdown stopt frontend, Maven en app-process trees direct, sluit gateway/MCP zonder connection drain en heeft een harde launcher-watchdog; rolling app replacement behoudt afzonderlijk graceful shutdown.

Operating contract:

- Start een frontend met bijvoorbeeld `--frontend-command "npm run dev -- --host 127.0.0.1 --port {port} --strictPort"` voor Vite of `--frontend-command "npx ng serve --host 127.0.0.1 --port {port}"` voor Angular.
- De dev server vervangt `{port}`, zet ook `PORT` en `FLUXZERO_FRONTEND_PORT`, en geeft de frontend alleen het relatieve backendpad `FLUXZERO_PROXY_URL=/_fluxzero`.
- UI-calls naar `/api/**` behouden hun volledige pad richting Fluxzero. Gebruik herhaalbare `--backend-path` opties om de default `/api` door projectspecifieke roots te vervangen.
- Open in de browser de `gateway.url` uit `.fluxzero/dev/session.json`; `frontend.url` en `proxy.url` zijn interne supervisor-adressen.
- Gebruik `--port 4200` wanneer een vaste publieke origin nodig is. Bij een bezette poort vraagt een interactieve terminal of een vrije dynamische poort acceptabel is; non-interactive runs wachten nooit op input en falen direct.
- De herhaalbare frameworktests staan bewust achter `./mvnw -Pdev-server-frontend-e2e verify`, omdat dit profiel Node-packages installeert. Node en npm kunnen voor CI worden aangewezen met `-Dfluxzero.node=...` en `-Dfluxzero.npm.cli=...`.

## Phase 15: Structured Dev Logging & Active Diagnostics

Goal: mensen en coding agents kunnen een complete Fluxzero dev environment onderzoeken via gecorreleerde, begrensd leesbare logs en een machineleesbaar antwoord op de vraag: welke problemen zijn nu nog actueel?

DoD status:

- Iedere dev session schrijft een gecombineerd human-readable log, een NDJSON eventlog en een afzonderlijke warning/error-history onder `.fluxzero/dev/logs/<session-id>/`.
- `.fluxzero/dev/diagnostics.json` bevat uitsluitend actuele problemen en verwijst naar de owning session, service en waar van toepassing app-instance.
- Ieder event heeft een monotone sequence, timestamp, level, source, service type, service id en optionele instance/operation/stream context.
- Het observability-model neemt nergens aan dat een environment maar een applicatie bevat: verschillende Fluxzero apps en meerdere instanties van dezelfde app blijven afzonderlijk filterbaar terwijl zij dezelfde runtime gebruiken.
- Compile-, reload-, test-, command- en processproblemen sluiten automatisch bij een aantoonbare success/stop/replacement transition.
- Nieuwe app warnings/errors worden gededupliceerd als open observaties voor de specifieke app-instance; replacement of shutdown van die instance sluit ze in actuele diagnostics, terwijl de historie behouden blijft.
- Child-process stdout en stderr blijven afzonderlijk herkenbaar; ingebedde runtime/proxy/IDP/dev-server logging komt in dezelfde tijdlijn.
- Logging is thread-safe, sessie-geisoleerd en begrensd door rotatie/retentie; terminaloutput blijft compact en ongewijzigd bruikbaar.

Backlog:

- [x] Slice 15.1: `DevLogEvent`, `DevProblem` en `DevDiagnostics` modellen plus session-scoped log store.
- [x] Slice 15.2: gecombineerd `dev.log`, `events.ndjson`, `problems.ndjson` en atomisch `diagnostics.json` schrijven.
- [x] Slice 15.3: alle dev pipelines, embedded services en gescheiden child stdout/stderr door de centrale sink leiden.
- [x] Slice 15.4: lifecycle diagnostics voor compile, reload, tests, commands, frontend en processen met automatische resolution.
- [x] Slice 15.5: app warning/error detectie, deduplicatie en instance-scoped resolution zonder single-app aanname.
- [x] Slice 15.6: rotatie/retentie, session discovery metadata en begrensde cursor/tail reads.
- [x] Slice 15.7: unit-, lifecycle- en hele-app tests voor correlatie, actuele diagnostics en replacement cleanup.
- [x] Slice 15.8: toon applicatiefouten met een exacte `dev.log:<regel>`-locatie en maak die via een OSC-8
  `file://`-link klikbaar in interactieve terminals, met leesbare path-line fallback voor andere terminals.

## Phase 16: Agent Control Plane & MCP

Goal: AI clients kunnen de gestructureerde dev-state efficiënt ontdekken, begrensd uitlezen en gericht afwachten zonder volledige logs in hun context window te laden of Fluxzero-internals te kennen; MCP blijft een dunne read-only adapter boven een transport-onafhankelijke query core.

DoD status:

- De observability-files uit Phase 15 blijven de transport-onafhankelijke source of truth; de query core werkt ook zonder MCP-client.
- Iedere cursor bevat `sessionId` en `sequence`, zodat restart en logrotatie niet stil events kunnen verwisselen of overslaan.
- Read-only tools bieden session status, active diagnostics, test/command status en begrensde log-delta's met filters voor service/app, instance, subsystem en minimumlevel.
- `wait_for_change` wacht selector-aware op een hogere cursor en retourneert een compacte delta; een globale, ambigue `wait_until_idle` wordt niet aangeboden.
- `fluxzero://environment/current/diagnostics` is subscribeable en pusht alleen een update-signaal bij open/escalated/resolved transitions; clients lezen daarna gericht de delta.
- De embedded MCP-server draait eenmaal per dev environment op een eigen dynamische `127.0.0.1`-poort, los van runtime/proxy/gateway, en wordt door de dev-server gestart en gestopt.
- `session.json` adverteert endpoint, status en tokenbestand; Bearer-authenticatie, Host/Origin-validatie, begrensde requests en read-only capabilities beschermen de localhost control plane.
- Een environment met meerdere Fluxzero apps blijft via service id en instance id gericht bevraagbaar; cursors en subscriptions zijn per client onafhankelijk.
- MCP-startup failure maakt de MCP-service en diagnostics rood, maar stopt runtime, apps en tests niet.
- De optionele stdio discovery-adapter leest de actieve project-session en verbindt door naar de embedded server; muterende tools blijven buiten deze phase.

Backlog:

- [x] Slice 16.1: `AgentCursor`, selectors, snapshots en transport-onafhankelijke query service boven Phase 15.
- [x] Slice 16.2: begrensde `get_status`, `get_active_problems`, `get_logs` en test/command-status queries.
- [x] Slice 16.3: selector-aware `wait_for_change` met timeout, session replacement en compacte delta.
- [x] Slice 16.4: subscribeable diagnostics-resource en change notifications zonder volledige probleempayloads te pushen.
- [x] Slice 16.5: embedded localhost Streamable HTTP MCP endpoint, discovery metadata en security constraints.
- [x] Slice 16.6: optionele stdio discovery/forwarding launcher voor hosts zonder remote-MCP-configuratie.
- [x] Slice 16.7: unit-, lifecycle-, security-, subscription- en multi-app MCP contracttests.

## Phase 17: CLI & Maven Dev Control

Goal: gebruikers en agents starten de dev environment via stabiele Fluxzero-commando's; artifact-resolutie, JVM-classpath, dynamische poorten en MCP-tokens blijven interne implementatiedetails.

DoD status:

- Een gedeelde launcher resolveert de nieuwste stabiele `io.fluxzero.tools:fluxzero-dev-server` `1.x` release met de project-local Maven of Gradle wrapper.
- `fluxzero dev` start de dev server foreground, geeft relevante configuratie door en laat `Ctrl+C` de child JVM graceful stoppen.
- De enige Java- of Kotlin-entrypoint met JVM-signature `public static void main(String[])` wordt na compile automatisch gedetecteerd; een expliciete main class is alleen nodig bij ambiguiteit.
- `fluxzero mcp` is een protocol-schone stdio-ingang die alleen de projectdirectory nodig heeft en via `DevMcpStdioMain` de actieve session ontdekt.
- Installatie en self-upgrade bieden zowel `fz` als het equivalente `fluxzero` executable commando.
- `fluxzero:dev` in de Fluxzero Maven plugin biedt dezelfde launcher als fallback wanneer de globale CLI niet is geinstalleerd.
- Maven wordt niet als MCP stdio-wrapper gebruikt, omdat Maven-output stdout kan vervuilen; agents gebruiken daarvoor de CLI-ingang.
- De concrete dev-serverversie wordt in `session.json` vastgelegd, zodat attach, status, logs, stop en MCP dezelfde versie blijven gebruiken terwijl de environment draait.
- `--dev-server-version` en `FLUXZERO_DEV_SERVER_VERSION` kunnen de nieuwste stabiele versie overschrijven voor lokale of prerelease-development.
- Versiemetadata en resolver-output worden project-local gecachet; bij een tijdelijk onbereikbaar Maven Central kan een eerder gevonden compatibele `1.x` release worden gebruikt.

Backlog:

- [x] Slice 17.1: gedeelde compatible-versieresolutie, wrapperselectie, launcher-POM/-build en classpath-resolutie.
- [x] Slice 17.2: gedeelde foreground child-process lifecycle en volledige dev-server argumentdoorgifte.
- [x] Slice 17.3: `fluxzero dev` en protocol-schone `fluxzero mcp` CLI-commands.
- [x] Slice 17.4: `fluxzero:dev` Maven Mojo boven dezelfde launcher-core.
- [x] Slice 17.5: unit- en plugin-tests voor detectie, caching, argumenten, exitcodes en Mojo-configuratie.
- [x] Slice 17.6: live smoke-test van CLI en Maven plugin tegen een volledige app met lokale `0-SNAPSHOT` dev-server.
- [x] Slice 17.7: reflectieloze classfile-detectie van een unieke Java/Kotlin main class met expliciete override bij ambiguiteit.
- [x] Slice 17.8: `fluxzero` executable alias naast `fz` voor Unix, Windows, self-upgrade en installer-CI.
- [x] Slice 17.9: full Maven compile gebruikt een enkele `test-compile` lifecycle zodat lifecycle-bound plugins eenmaal draaien.
- [x] Slice 17.10: interactieve terminalspinner met compile-, app-start-, readiness- en frontendfasen plus elapsed time; output naar pipes, CI en `TERM=dumb` blijft vrij van control sequences.

## Phase 18: Multi-App Maven Reactor Environments

Goal: een Maven-reactor met meerdere Fluxzero Spring Boot apps draait als een lokale environment op een gedeelde runtime, met per app correcte classpath-isolatie, readiness, diagnostics en rolling replacement.

DoD status:

- Alle reactor-modules worden recursief ontdekt; iedere module met een geldige main class wordt een afzonderlijke app in dezelfde dev environment.
- Iedere app gebruikt haar eigen runtime dependency metadata plus alleen de live classes van haar transitieve reactor-afhankelijkheden.
- Kandidaten starten parallel en worden per app beoordeeld; een mislukte app blokkeert gezonde apps niet en vervangt nooit haar laatst werkende instance.
- Session metadata en diagnostics publiceren alle actieve app-PIDs/client IDs en afzonderlijke startup failures.
- De watcher volgt geneste module-sources en negeert module-buildoutput en directory-events.
- Een frontend command start parallel aan de eerste Maven-build; stabiele frontend-readiness voorkomt dat een tijdelijke tussenbuild als gereed wordt gepubliceerd. Projecten met niet-atomisch gegenereerde frontend-artifacts blijven vervolgwerk voor een expliciete handoff-strategie.
- Maven-, frontend- en applogs blijven volledig in de session logs, terwijl de terminal alleen status, lifecycle en compacte waarschuwingen/fouten toont.

Backlog:

- [x] Slice 18.1: Maven reactor discovery met module-main-classdetectie en module-eigen runtime classpath metadata.
- [x] Slice 18.2: transitieve reactor-classpathisolatie en immutable multi-module build snapshots.
- [x] Slice 18.3: multi-app candidate lifecycle, per-app readiness/failure en gedeeltelijke rolling activation.
- [x] Slice 18.4: recursieve module-watcher zonder `target`/directory feedback loops.
- [x] Slice 18.5: parallelle frontend/backend-start met afzonderlijke terminalprogressie en gezamenlijke success-readiness.
- [x] Slice 18.6: unitbewijs voor reactor-isolatie/watcher plus live zes-app dashboard-proef met twee geisoleerde app failures.
- [ ] Slice 18.7: affected-module Maven planning (`-pl`/`-am`) zodat backend changes geen ongerelateerde apps of frontend production build compileren.
- [ ] Slice 18.8: multi-app whole-reactor E2E-fixture voor partial failure, herstel en onafhankelijke rolling replacement.
- [x] Slice 18.9a: herhaalbare `--app`-selectie op reactor artifact-id/module/main class en configureerbaar `--environment`, met `local` als backwards-compatible projectdefault.
- [x] Slice 18.9c: expliciete test-appselectie op simple/FQCN of `module:app`, met package-private main launcher, test classes/resources/dependencies, Spring-profiel `main`, legacy embedded-port reuse en test-source rolling reload; test-apps blijven uitgesloten van automatische discovery.
- [ ] Slice 18.9b: persistente projectconfig voor app include/exclude en per-app environment/properties voor speciale lokale services.
- [ ] Slice 18.10: frontend/build barrier of atomische generated-artifact handoff voor Maven-plugins die door een draaiende UI-watcher gebruikte files niet-atomisch herschrijven.

## Phase 19: Named App Configurations & Secret-Safe Environments

Goal: dezelfde Fluxzero app kan in een lokale environment in een of meer benoemde smaken draaien, met per smaak gewone environmentconfig en teamgedeelde 1Password-referenties zonder ontsleutelde secrets in projectconfig, supervisorstatus of agentdiagnostics.

DoD status:

- `apps` selecteert zowel directe applicaties als keys onder `applicationConfig`; `--app rebound` blijft zonder extra configuratie werken.
- Een benoemde configuratie heeft een eigen supervisor-identiteit en verwijst via `application` naar een module, simple main class of fully qualified main class.
- Meerdere configuraties mogen dezelfde bronapp tegelijk starten; ze delen compile-output maar krijgen eigen client-id, proces, readiness, diagnostics en rolling lifecycle.
- `env` wordt alleen aan de gekozen app flavor toegevoegd. `secrets` accepteert alleen environmentnamen met `op://`-referenties.
- Secretwaarden worden door `op run` rechtstreeks in het childproces geinjecteerd; de dev-server resolveert of bewaart de waarde niet.
- Session metadata, lifecyclelogs en objectrepresentaties bevatten configuratienaam, runtime-appnaam en variabelenamen, maar geen `op://`-referenties of waarden.
- Een ontbrekende/mislukte 1Password-launch maakt alleen die configuratie rood wanneer andere gekozen apps wel kunnen starten.
- CLI en Maven plugin blijven dunne launchers: hun bestaande herhaalbare `--app`/applications-doorgifte werkt ook voor configuratienamen.

Backlog:

- [x] Slice 19.1: tracked `applicationConfig` model met alias -> applicatieselector en backwards-compatible directe selectie.
- [x] Slice 19.2: scheid launch/configuratie-id van Fluxzero runtime application name en lifecycle-mapkey.
- [x] Slice 19.3: per-configuratie `env`, validatie en child-processinjectie.
- [x] Slice 19.4: per-configuratie `secrets` via reference-only env-file en `op run` child wrapper.
- [x] Slice 19.5: redacted logging/sessionmetadata, reference-filecleanup en compacte 1Password-startupfouten.
- [x] Slice 19.6: meerdere flavors van dezelfde test-app delen een build maar starten als onafhankelijke instances.
- [x] Slice 19.7: config-, reactor-, process- en fake-`op` regressietests zonder echte secrettoegang.
- [ ] Slice 19.8: live dashboardproef met `rebound-encrypted`, echte 1Password-authenticatie en rolling source reload.

## Phase 20: Outcome-Focused Startup UX

Goal: de terminal presenteert startup als een enkele betrouwbare uitkomst: een volledig bruikbare environment met de juiste URL, of een concrete failure zonder een misleidende browserlink.

DoD status:

- De success-link verschijnt pas nadat de eerste build compileert, alle geselecteerde apps ready zijn en een geconfigureerde frontend ready is.
- Een gedeeltelijk gestarte multi-app environment is tijdens initial startup een failure en publiceert geen browserlink.
- Compile-, app- en frontendfailures stoppen de spinner en tonen een compact issueblok met diagnostics- en loglocatie; de environment blijft watchen voor herstel.
- Een frontend die na app-readiness niet binnen de startup-timeout ready wordt, wordt expliciet als failure gemarkeerd en kan later alsnog naar success herstellen.
- Runtime-, proxy-, MCP-, lifecycle-, frontend- en compile-details blijven in het gezamenlijke log en vervuilen de normale startup-terminal niet.
- Na success blijft relevante failure/testfeedback zichtbaar, terwijl routine compile-, reload- en frontendmeldingen stil blijven.

Backlog:

- [x] Slice 20.1: modelleer initial startup success als compile + volledige app readiness + frontend readiness.
- [x] Slice 20.2: publiceer URL uitsluitend vanuit de gezamenlijke startup-outcome state machine.
- [x] Slice 20.3: compact failureblok met bron, foutdetail, `diagnostics.json`, gecombineerd log en watch-status.
- [x] Slice 20.4: begrens frontend-readiness en sta failure-naar-success herstel toe.
- [x] Slice 20.5: verplaats infrastructuur- en routine lifecycleoutput naar het gezamenlijke log.
- [x] Slice 20.6: terminalregressies voor frontend-zonder-app en compile failure zonder URL.
- [x] Slice 20.7: whole-app E2E-bewijs dat success en backendlink pas na app-registration verschijnen.
- [x] Slice 20.8: een enkele `Ctrl+C` tijdens Maven-compile stopt de volledige procesboom snel en publiceert geen startup failure na shutdown.
- [x] Slice 20.9: terminalshutdown eindigt na cleanup met exact één `Fluxzero dev server stopped.`-regel, ook wanneer `Ctrl+C` al tijdens SDK-resolutie valt.
- [x] Slice 20.10: startup ruimt ook bij incompatibele/linkage-foutieve artifacts alle gedeeltelijk gestarte resources en de spinner op en eindigt met één compacte herstelmelding.
- [x] Slice 20.11: de launcher bezit shutdown-output, wacht op definitieve child-exit en kan daardoor nooit de shellprompt vóór de stopregel vrijgeven.
- [x] Slice 20.12: resolver, server-launch en signal-exit delen één immutable per-launch shutdown outcome; races tussen de main thread en shutdown hooks kunnen de afsluitregel niet meer onderdrukken of dupliceren.
- [x] Slice 20.13: de CLI start de dev-server uit één attached `standalone` artifact, zodat alle SDK-, test-server-, proxy- en MCP-klassen gegarandeerd uit dezelfde gepubliceerde versie komen.
- [x] Slice 20.14: één launch-scoped process supervisor blijft actief over resolver-, overgangs- en serverfasen; ook een interrupt tussen twee childprocessen publiceert na cleanup exact één afsluitregel.
- [x] Slice 20.15: CLI-subcommands initialiseren interactieve terminalinfrastructuur lazy; het ongebruikte `init`-command kan daardoor geen JLine `SIGINT` handler installeren die shutdown van `dev` onderschept.
- [x] Slice 20.16: plain en runnable CLI jars hebben verschillende outputnamen; `check` valideert de `Main-Class` van het uiteindelijke shadow artifact zodat buildvolgorde de dev-launcher niet onbruikbaar kan maken.
- [x] Slice 20.17: startup-spinner vertaalt Maven-output naar rustige tweeregelige progressie met `Building` plus een stabiele `##.#s` totaalklok als vaste kop en `<module>: <fase>` plus een eigen `##.#s` stapklok als aparte detailregel; ready/failure-output gebruikt title case en subtiele terminalkleuren in plaats van schreeuwerige hoofdletters.
- [x] Slice 20.18: frontend-readiness vereist een stabiele gezonde periode en gebruikt hysterese bij korte uitval; de browserlink verschijnt daardoor pas wanneer de publieke gateway de frontend betrouwbaar kan serveren.
- [x] Slice 20.19: backend en managed frontend bouwen parallel met afzonderlijke `Backend:`/`Frontend:` progressregels; een levende maar trage frontend of tijdelijke compilefout op nog gegenereerde Maven-output veroorzaakt geen voortijdige failure-uitkomst, en `--no-frontend`/`fluxzero.dev.frontendEnabled=false` ondersteunen expliciete backend-only runs.
- [x] Slice 20.20: achtergrondtests voeren na de Maven-correcte `test-compile` uitsluitend Surefire uit; productie-lifecycleplugins, frontendbuilds en gegenereerde frontend-input worden daardoor niet opnieuw of tussentijds gemuteerd.
- [x] Slice 20.21: de backendwatcher accepteert alleen Maven-projectinput (`pom.xml`, `src/main` en `src/test`); IDE-state, run-configuraties, Angular-cache, frontend-devsources en buildoutput veroorzaken geen compile- of testruns.
- [x] Slice 20.22: `Ctrl+C` publiceert direct `Stopping Fluxzero dev server and all started applications...`, ruimt daarna de volledige procesboom op en eindigt pas vervolgens met exact één `Fluxzero dev server stopped.`.

Architecture contract:

- De CLI is eigenaar van de terminaluitkomst. Per `dev`-invocatie bestaat exact één tweefasige shutdown outcome: `stopping` wordt vóór cleanup gepubliceerd en `stopped` pas nadat de actieve resolver- of server-child definitief is gestopt.
- De dev-server is eigenaar van environment-resources en ruimt app-, frontend-, compile-, runtime-, proxy-, IDP- en MCP-processen op. Een watchdog begrenst cleanup, maar publiceert bij CLI-launches geen concurrerende terminaluitkomst.
- De process-supervisor-hook wordt eenmaal aan het begin van de volledige launch geregistreerd. Child-starts wisselen daarbinnen atomisch van process-attempt, zodat noch tijdens process-start noch tussen resolver en dev-server een ongedekt shutdownvenster bestaat.
- Alleen het daadwerkelijk gekozen CLI-command mag terminal- of signal-handlers initialiseren. `fz dev` behoudt de JVM `SIGINT` lifecycle; `Init` maakt zijn JLine prompt pas bij de eerste echte interactieve vraag.
- De dunne `dev-server` jar blijft beschikbaar als programmeerbare API. Launchers gebruiken uitsluitend de attached `standalone` classifier met uitgesloten transitieve dependencies: één proces, één version-coherent artifact.

## Phase 21: Declarative Dev Commands

Goal: testdata en lokale scenario's kunnen rechtstreeks in de gedeelde `.fluxzero/dev.yaml` worden beschreven, zonder de bestaande herhaalbare en retrybare commandsemantiek te verliezen.

DoD status:

- File-command ids blijven hun relatieve JSON-pad; YAML-command ids zijn hun mapkey onder `commands`.
- YAML-declaratievolgorde is uitvoervolgorde; daarna volgen file-commands lexicografisch.
- Type, revision, payload en metadata zijn inline configureerbaar en worden met dezelfde statusledger uitgevoerd.
- Een gewijzigde commanddefinitie wordt opnieuw uitgevoerd; succesvolle ongewijzigde commands worden overgeslagen.
- Een wijziging aan `.fluxzero/dev.yaml` herlaadt commands live zonder app-redeploy of achtergrondtests.
- Config- en uitvoerfouten noemen de command-id en blijven zichtbaar in diagnostics en commandstatus.

Backlog:

- [x] Slice 21.1: YAML commandconfigmodel met mapkey als stabiele id en behouden declaratievolgorde.
- [x] Slice 21.2: verenig YAML- en JSON-commanddiscovery, hashing, metadata en ledgerstatus.
- [x] Slice 21.3: live watcherpad voor `.fluxzero/dev.yaml` zonder compile/redeploy/testbijwerking.
- [x] Slice 21.4: regressies voor volgorde, retry, gewijzigde payload, dubbele payloads en backwards compatibility.
- [x] Slice 21.5: whole-appbewijs voor live toevoegen en herstellen van een YAML-command.

## Phase 22: Explainable Rebuild And Test Feedback

Goal: iedere automatische rebuild en testrun legt compact uit wat er gebeurt, waardoor het gebeurt en welke concrete selectie daaruit volgt.

DoD status:

- Rebuildfeedback noemt de relevante gewijzigde bestanden, gekozen compilemodus en betrokken applicaties.
- Rolling replacement meldt kandidaat-, readiness- en activatieresultaat zonder routine-Mavenoutput te tonen.
- Testfeedback onderscheidt changed-test, previously-failing, impact-index en modulefallback.
- Iedere geselecteerde test heeft een machineleesbare en terminalzichtbare selectiereden.
- Coalesced wijzigingen leveren een enkele samengevatte activiteit op; succesoutput blijft compact en fouten blijven dominant.

Backlog:

- [x] Slice 22.1: change-summarymodel voor stabiele relatieve paden en compacte redenweergave.
- [x] Slice 22.2: rebuild/start/readiness/activation feedback bovenop bestaande terminalprogressie.
- [x] Slice 22.3: per-selector testredenen vanuit changed tests, failures en TestFixture impactindex.
- [x] Slice 22.4: compacte teststart/-resultaatweergave met duur en behoud van volledige logs.
- [x] Slice 22.5: terminal-, planner- en coalescingregressies voor verklaarbare output.
- [x] Slice 22.6: toon maximaal vier compacte, ondubbelzinnige testselectors in de terminalscope en val bij grotere
  of te lange selecties terug op een telling.

## Phase 23: Architecture Review And Hardening

Goal: het volledige dev-environment wordt als production-grade supervisor gereviewd en concrete findings worden opgelost en met regressies bewezen.

DoD status:

- Review dekt lifecycle/concurrency, procesownership, snapshots, watchers, multi-app, secrets, diagnostics, config en packaging.
- Findings worden op ernst en impact gerangschikt voordat implementatie start.
- Iedere geaccepteerde finding krijgt een gerichte regressie of whole-appbewijs.
- Geen open P0/P1 correctnessfinding blijft zonder expliciete vervolgstatus achter.

Backlog:

- [x] Slice 23.1: architecture- en code-review met geordende findings en invarianten.
- [x] Slice 23.2: los lifecycle/concurrency- en cleanupfindings op.
- [x] Slice 23.3: los command/test/watch/config correctnessfindings op.
- [x] Slice 23.4: los diagnostics/security/packagingfindings op.
- [x] Slice 23.5: volledige reactor-, E2E- en gerichte liveverificatie.
- [x] Slice 23.6: laat gerichte Surefire-selectors veilig door multi-module reactors lopen en sluit testdiagnostics
  pas na een aantoonbaar groene retry.

Reviewresultaat:

- P1 opgelost: gewijzigde Java-testsource en testresources gebruiken gerichte Maven-goals vóór Surefire; gewijzigde Kotlin-tests behouden bewust de volledige `test-compile` lifecycle voor plugin-correctheid.
- P1 opgelost: changed-testselectors gebruiken fully-qualified classnamen zodat gelijknamige tests niet samen worden geselecteerd.
- P1 opgelost: tijdelijke infrastructuur-logproblemen verdwijnen uit `diagnostics.json` zodra de betreffende service weer aantoonbaar draait.
- P1 opgelost: gerichte tests falen niet langer in reactor-modules zonder match; een groene retry verwijdert zowel de
  statusfailure als Maven-testoutput uit de actieve diagnostics.
- P2 opgelost: commandhashes zijn semantisch en deterministisch; alleen JSON/YAML-formattering voert een geslaagd command niet opnieuw uit.
- P2 opgelost: command- en testexecutors hebben een begrensde shutdown en terminalfouten blijven compact terwijl volledige details in de gezamenlijke logs staan.
- Geen open P0/P1-correctnessfinding binnen de scope van Phases 21-23.

## Phase 24: Detached Lifecycle & Session Recovery

Goal: de dev environment kan expliciet zonder terminalownership blijven draaien, maar behoudt een eenvoudige en
betrouwbare lifecycle met maximaal één sessie per project en eerlijke state na onverwachte uitval.

DoD status:

- Foreground blijft de default; detached/background is een expliciete keuze.
- `dev status`, `dev logs --follow` en `dev stop` besturen dezelfde projectlokale sessie.
- De project-lock weigert een tweede actieve sessie.
- Status-, MCP- en nieuwe startpaden reconciliëren een verdwenen supervisor als `stopped-unexpectedly`.
- Commandresultaten uit de verloren in-memory runtime worden stale en alle startup commands draaien in de volgende
  sessie opnieuw.
- Detached bootstrapoutput blijft beschikbaar onder `.fluxzero/dev/bootstrap.log`.

Backlog:

- [x] Slice 24.1: lifecycle control main voor status, JSON-status, gefilterde/follow logs en begrensde stop/force-stop.
- [x] Slice 24.2: gedeelde launcher ondersteunt detached processstart zonder terminal- of shutdown-hookownership.
- [x] Slice 24.3: CLI-acties `dev --background`, `dev status`, `dev logs` en `dev stop`.
- [x] Slice 24.4: stale-sessionreconciliatie met expliciet verlies van in-memory runtime state.
- [x] Slice 24.5: commandledger invalidation en replay in een nieuwe session-id.
- [x] Slice 24.6: subprocessregressies voor control-stop, abrupt-killreconciliatie en detached child survival.

## Phase 25: Gradle Parity & Product Documentation

Goal: Maven- en Gradle-projecten gebruiken dezelfde dev-environment en dezelfde projectconfig, waarbij iedere build
tool eigenaar blijft van zijn eigen correcte compile-, annotation-processor-, resource- en classpathsemantiek.

DoD status:

- De CLI resolveert de standalone dev-server via de projectlokale Maven- of Gradle-wrapper.
- De Gradle plugin levert `fluxzeroDev` en een machineleesbaar `fluxzeroDevMetadata` buildcontract.
- Gradle main/test outputs, resources en runtime classpaths worden immutable snapshots voor rolling replacement.
- Gradle achtergrondtests ondersteunen modulefallback en gerichte testselectors.
- Maven en Gradle plugins bieden foreground/background en dezelfde belangrijke launchopties.
- CLI-help en README's leggen controls, data-loss, startup commands, app flavors, secrets, frontends en alle overrides uit.

Backlog:

- [x] Slice 25.1: build-tooldetectie en Gradle wrappercommand.
- [x] Slice 25.2: Gradle metadata task voor multi-project outputs en classpaths.
- [x] Slice 25.3: Gradle compile/snapshot/application discovery en rolling lifecycle.
- [x] Slice 25.4: Gradle test runner en TestFixture impact-propertydoorgifte.
- [x] Slice 25.5: `fluxzeroDev` task en Maven `fluxzero.dev.background` parity.
- [x] Slice 25.6: CLI-, Maven- en Gradle-gebruiksdocumentatie en option reference.
- [x] Slice 25.7: volledige Maven-reactor, CLI-build en live Gradle whole-app smoke.

## Phase 26: Transient Frontend Reload Handoff

Goal: een frontendframework kan de browser tijdens een rebuild automatisch herladen zonder dat de publieke gateway
de tijdelijke upstream-dip omzet in een blijvende `Frontend dev server is not ready yet`-pagina.

DoD status:

- De eerste publieke frontendrequest blijft door stabiele startup-readiness beschermd.
- Na eerder bewezen readiness wacht een request tijdens een rebuild maximaal 30 seconden op herstel in plaats van
  onmiddellijk `503` te retourneren.
- Herstel na een tijdelijke uitval kwalificeert direct; alleen de allereerste readiness vereist drie stabiele seconden.
- Backendroutes blijven tijdens frontend-rebuilds zonder vertraging beschikbaar.
- Een wachtende reloadrequest vertraagt of vervuilt gateway-shutdown niet.
- Vite- en Angular-E2E gebruiken de frameworkgegenereerde HMR-token en bewijzen source update plus directe publieke
  navigatie zonder `503`.

Backlog:

- [x] Slice 26.1: reproduceer de ready/unavailable/reload-race met een deterministische gatewaytest.
- [x] Slice 26.2: voeg begrensde async frontend recovery toe zonder initiële startupsemantiek te versoepelen.
- [x] Slice 26.3: maak post-startup readiness recovery onmiddellijk.
- [x] Slice 26.4: dek shutdown met een wachtende reloadrequest af.
- [x] Slice 26.5: moderniseer Vite/Angular HMR-E2E voor tokenized websocketconnecties en directe navigatie.

## Phase 27: Persistent Test Input Baseline

Goal: een ongewijzigde devomgeving start zonder telkens alle moduletests uit te voeren, terwijl wijzigingen die zijn
gemaakt toen de dev server niet draaide nog steeds correct en uitlegbaar worden getest.

DoD status:

- De initiële compile wordt niet langer als een fictieve `pom.xml`-wijziging aan de testplanner aangeboden.
- De eerste testrun legt hashes van relevante source-, resource- en buildinputs vast in
  `.fluxzero/dev/test-inputs.json`.
- Een volgende start zonder gewijzigde inputs slaat de initiële testrun over.
- Offline gewijzigde inputs worden bij startup door dezelfde impact- en fallbackplanner verwerkt als live changes.
- Een echte buildfilewijziging blijft alle moduletests draaien.
- Een mislukte, queued of onderbroken vorige run wordt na restart opnieuw geprobeerd.
- Secretbestanden, buildoutputs, frontenddependencies en caches worden niet gelezen of geïndexeerd.

Backlog:

- [x] Slice 27.1: maak een versieerbare, content-based test-inputsnapshot met directory pruning.
- [x] Slice 27.2: schrijf en lees `test-inputs.json` atomisch via de session store.
- [x] Slice 27.3: modelleer initial compile expliciet en verwijder de synthetische testtrigger.
- [x] Slice 27.4: vergelijk startupinputs en plan alleen offline wijzigingen of eerdere failures.
- [x] Slice 27.5: sluit `.angular` ook uit van live watcherregistratie.
- [x] Slice 27.6: dek first run, unchanged restart, offline testwijziging en echte POM-wijziging af.
- [x] Slice 27.7: compileer gewijzigde main sources/resources naar de Maven output voordat nieuwe tests starten.

## Phase 28: Packaging-aware Frontend Build And Truthful Progress

Goal: een door de dev server beheerde frontend wordt niet nogmaals als Maven-packagingwerk gebouwd tijdens de
backendcompile, terwijl terminalstatus en staptijd steeds de actuele Maven-taak tonen.

DoD status:

- Frontend `npm install` en productiebuild kunnen aan `prepare-package` worden gekoppeld zonder ze tijdens
  `test-compile` uit te voeren.
- `package` en `install` voeren eerst `npm install` en daarna de frontendbuild uit.
- De Maven-progressparser herkent ook generieke pluginexecuties, waaronder `exec-maven-plugin`.
- De tweede progressregel wisselt bij iedere waargenomen Maven-pluginstap en de staptimer begint daarbij opnieuw;
  de totale buildtimer blijft doorlopen.

Backlog:

- [x] Slice 28.1: verplaats in de dashboardreferentie `npm-install` en `npm-build` naar `prepare-package`.
- [x] Slice 28.2: valideer dat de dev-compile geen frontendproductiebouw meer uitvoert.
- [x] Slice 28.3: valideer dat Maven `package` install en build in de juiste volgorde blijft uitvoeren.
- [x] Slice 28.4: rapporteer iedere Maven-pluginexecution als actuele progressstap.
- [x] Slice 28.5: dek npm-, Fluxzero-plugin- en bestaande compiler/dependencylabels af met regressietests.

## Phase 29: Stack-neutral Frontend Setup And Recovery

Goal: een managed frontend start betrouwbaar vanaf een verse checkout en kan eenmaal automatisch herstellen van een
vastgelopen dev server, zonder lifecyclebeslissingen te baseren op Angular-, Vite- of andere tooloutput.

DoD status:

- Een optioneel `frontend.setupCommand` draait eenmaal vóór iedere volledige frontendstart.
- `frontend.directory` is de werkmap voor zowel setup als serve; shellconstructies zoals `cd frontend && ...` zijn
  niet nodig.
- Setup en frontendcommand blijven volledig stackneutraal en worden ook via CLI-, Maven- en Gradleopties ondersteund.
- Frontend stdout/stderr is uitsluitend diagnostische output en verandert geen status, readiness of retrygedrag.
- Een onverwachte procesexit krijgt maximaal één automatische restart.
- Een eerder ready frontend die onafgebroken HTTP-unavailable blijft, wordt na de recoverytermijn eenmaal hard
  vervangen op dezelfde interne poort.
- Na succesvolle readiness is opnieuw één herstelpoging beschikbaar; na een mislukte herstelpoging ontstaat een
  duidelijke diagnostic zonder restartloop.

Backlog:

- [x] Slice 29.1: voeg `directory` en `setupCommand` toe aan projectconfig en de publieke frontendconfig.
- [x] Slice 29.2: voer setup gecontroleerd uit vóór de managed frontend en stop setup hard bij shutdown.
- [x] Slice 29.3: verwijder herkenning van frontend compilerteksten uit status- en terminalbesturing.
- [x] Slice 29.4: implementeer één process-exit-retry en één HTTP-unavailability-retry per failure-episode.
- [x] Slice 29.5: expose configuratie via CLI, Maven plugin en Gradle plugin.
- [x] Slice 29.6: configureer dashboard met een snelle npm-setup en een echte frontendwerkmap.
- [x] Slice 29.7: dek setup, tekstneutraliteit, process-exit en blijvende unavailability frameworkvrij af.

## Phase 30: Test-app Source Classification

Goal: Java- en Kotlincode onder `src/test` kan als expliciete dev-app hot reloaden zonder dat iedere gewijzigde class
blind als JUnit/Surefire-selector wordt uitgevoerd.

DoD status:

- Een gewijzigde testclass wordt direct geselecteerd op basis van gangbare testnaamconventies of herkenbaar gebruik
  van JUnit, TestNG, Kotlin Test, Kotest of Spock.
- Een test-app main of gewone helper onder `src/test` wordt niet als directe testselector aangeboden.
- Persisted failures die naar een bestaande non-test source wijzen worden niet opnieuw geprobeerd.
- Een selector voor een werkelijk verwijderde test blijft zichtbaar en retryable, zodat stale impact niet stil wordt
  verborgen.

Backlog:

- [x] Slice 30.1: classificeer gewijzigde test sources vóór directe selectie.
- [x] Slice 30.2: filter persisted non-test selectors via Maven-reactor source roots.
- [x] Slice 30.3: behoud ontbrekende testselectors als expliciete stale failure.
- [x] Slice 30.4: dek conventionele, onconventionele, test-app en verwijderde testclasses af.
- [x] Slice 30.5: bewijs gedrag met een live rolling dashboard test-app replacement.

## Phase 31: Startup Traffic Gate

Goal: een bestaande browsertab kan tijdens een verse dev-sessie geen applicatierequest of WebSocket-handshake naar
de Fluxzero proxy sturen voordat de eerste backend-app ready en geactiveerd is.

DoD status:

- Configureerbare applicatieroutes zoals `/api` antwoorden vóór backend-readiness direct met `503` en `Retry-After`.
- Een vroege WebSocket-upgrade op een applicatieroute wordt eveneens direct met `503` afgewezen en blijft niet op
  de normale Fluxzero request-timeout wachten.
- Fluxzero-infrastructuurroutes onder `/_fluxzero` blijven tijdens startup beschikbaar voor managed IDP en health.
- Na activatie opent dezelfde gateway zonder restart; bij rolling replacement blijft hij open zolang N actief is.
- Als de laatste actieve app onverwacht verdwijnt, sluit de gate opnieuw voor nieuwe applicatierequests.

Backlog:

- [x] Slice 31.1: voeg een onafhankelijke backend-readiness supplier toe aan `DevGateway`.
- [x] Slice 31.2: gate HTTP- en WebSocketverkeer voor pass-through applicatieroutes.
- [x] Slice 31.3: koppel readiness aan het bestaan van minstens één actieve app-instance.
- [x] Slice 31.4: dek startup, readiness-herstel, terugval en infrastructuurexcepties af.

## Phase 32: Terminal-independent Dev Sessions

Goal: de dev environment is vanaf de start onafhankelijk van de terminal, terwijl `fz dev` standaard een directe,
interactieve live view biedt die zonder verlies kan worden losgekoppeld en later hervat.

DoD status:

- `fz dev` start de supervisor detached en attach't daarna de huidige terminal; een actieve projectsessie wordt
  hergebruikt in plaats van een tweede environment te starten.
- `d`/`detach` laat de environment direct doorlopen; `q`/`quit` opent een keuze tussen detach, volledig stoppen en
  terugkeren naar de live view.
- `fz dev attach` en een nieuwe kale `fz dev` hervatten dezelfde sessie en tonen alleen gemiste semantische output.
- `Ctrl+C` stopt de supervisor en alle app- en frontendprocessen; `d`/`detach` is de expliciete detachactie. Een
  onverwacht gesloten stdin of verdwenen attachmentclient laat de terminal-onafhankelijke environment doorlopen.
- De replaycursor is project- en sessiegebonden; een nieuwe sessie erft geen cursor van een oude omgeving.
- Lifecycle-control blijft werken wanneer macOS dezelfde projectdirectory via bijvoorbeeld `/tmp` en `/private/tmp`
  anders representeert.

Backlog:

- [x] Slice 32.1: maak de launcher daemon-first en attach standaard na detached startup.
- [x] Slice 32.2: voeg een projectlokale attachmentcursor en gemiste-outputreplay toe.
- [x] Slice 32.3: implementeer `d`/`detach`, `q`/`quit`, stopbevestiging en compacte command hints.
- [x] Slice 32.4: voeg `fz dev attach` en automatische re-attach via kale `fz dev` toe.
- [x] Slice 32.5: maak launcher-shutdown afhankelijk van attachment versus startup/background ownership.
- [x] Slice 32.6: documenteer attach, detach, background, logs, status en stop in CLI- en plugindocumentatie.
- [x] Slice 32.7: dek cursor, replay, commands, stale sessies, subprocesssignalen en projectpadaliases af.
- [x] Slice 32.8: maak `Ctrl+C` een expliciete environment-stop, behoud fail-safe detach bij clientverlies en toon de
  semantiek in de interactieve terminalhint.
- [x] Slice 32.9: vervang de regelgebaseerde quitvraag in interactieve terminals door een selectielijst met
  pijltoetsnavigatie, Enter-bevestiging en Escape-annulering; behoud tekstinvoer als non-interactieve fallback.
- [x] Slice 32.10: behoud `q`/`quit` en `d`/`detach` als regelcommando's die Enter vereisen en schakel pas binnen het
  quit-menu over op directe pijltoetsnavigatie.

## Phase 33: Independent 1.x Releases And Compatible Launchers

Goal: de dev server wordt onafhankelijk van SDK en CLI uitgebracht, terwijl iedere launcher automatisch de nieuwste
compatibele stabiele release gebruikt zonder een draaiende environment onderweg van versie te laten wisselen.

DoD status:

- Maven Central publiceert `io.fluxzero.tools:fluxzero-dev-server` als dunne en `standalone` artifactlijn.
- De eerste release is `1.0.0`; iedere groene push naar `main` krijgt daarna volgens Conventional Commits een
  SemVer-release binnen major 1, totdat een bewuste incompatibele wijziging major 2 vereist.
- Pull requests doorlopen Linux, macOS, Windows, whole-app en echte Vite/Angular-verificatie voordat ze kunnen mergen.
- Dependabot volgt de Fluxzero BOM; een niet-major SDK-update wordt pas na dezelfde verificatie automatisch gemerged
  en veroorzaakt vervolgens een patchrelease van de dev server.
- CLI, Maven plugin en Gradle plugin kiezen voor een nieuwe environment de nieuwste stabiele `1.x` uit Maven Central.
- `session.json` bewaart de concrete dev-serverversie; attach, status, logs, stop en MCP blijven diezelfde versie
  gebruiken zolang de environment bestaat.
- Een expliciete versieoverride blijft beschikbaar voor lokale snapshots en prereleases; bij tijdelijk onbereikbaar
  Maven Central mag de laatst succesvol gevonden compatibele release worden gebruikt.

Backlog:

- [x] Slice 33.1: publieke `io.fluxzero.tools` coordinates, release-manifestmetadata en veilige `--version` smoke-entrypoint.
- [x] Slice 33.2: gated push-to-main releaseworkflow met herhaalbare tagberekening, signing, Central-publicatie en fresh-repository smoke.
- [x] Slice 33.3: Dependabot SDK-BOM updates met expliciete volledige verificatie vóór auto-merge.
- [x] Slice 33.4: latest-compatible `1.x` resolver, offline cache, session pinning en Maven/Gradle artifactresolutie.
- [x] Slice 33.5: unit-, packaging-, whole-app-, frontend- en volledige CLI/pluginverificatie.
- [ ] Slice 33.6: repository publiek maken, Actions/Dependabot-secrets toekennen en release `1.0.0` plus Central/CLI smoke uitvoeren.

## Verification So Far

- [x] Phase 33 default suite: 183 dev-servertests groen.
- [x] Phase 33 whole-app profiel: 14 complete source-, compile-, rolling-reload-, command- en testimpactscenario's groen.
- [x] Phase 33 frontend profiel: echte Vite- en Angular-installatie, gateway, websocket en hot-reloadproeven groen.
- [x] Phase 33 release packaging: dunne, standalone, sources- en Javadoc-artifacts plus Central deploy-profiel groen;
  standalone manifest bevat dev-server- en exacte SDK-versie en `java -jar ... --version` is zelfstandig uitvoerbaar.
- [x] Phase 33 CLI: volledige Gradle-build en gerichte launcher/Maven/Gradle-plugintests groen, inclusief stable-versieselectie,
  offline fallback en version-pinned actieve sessies.
- [x] `./mvnw clean install`
- [x] `./mvnw -pl sdk -am -Dtest=TestFixtureObservationRecorderTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am -Dtest=DevServerConfigTest,DevSessionStoreTest,TestPlannerTest,DevServerLifecycleTest,AppProcessRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am -DskipTests package`
- [x] `./mvnw -pl proxy -am -Dtest='ProxyServerTest$Basic#healthCheck,ProxyServerTest$Basic#healthEndpointCanBeConfigured' -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am -Dtest=DevServerConfigTest,DevSessionStoreTest,DevServerLifecycleTest,AppProcessRunnerTest,DevCommandPipelineTest,TestPlannerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am test`
- [x] `./mvnw -pl dev-server -am -Dtest=DevCommandPipelineTest,DevSessionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test` after slices 8.9-8.11 (8 tests green).
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e '-Dit.test=DevServerWholeAppE2EIT#retriesSeedCommandAfterHandlerIsAdded+liveSeedChangesRunWithoutReloadAndReplayForFreshRuntime+removingHandlerMakesNewSeedCommandFailWithoutKillingApp' verify` after slices 8.9-8.11 (3 whole-app scenarios green).
- [x] `./mvnw -B clean install` after slices 8.9-8.11 (all 10 reactor modules green).
- [x] `./mvnw -pl sdk -am -Dtest=OidcUserProviderTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e -Dit.test=DevServerWholeAppE2EIT#authenticatedAppEndpointAcceptsManagedIdpToken verify`
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e verify`
- [x] `./mvnw -pl dev-server -am -Dtest=AgentQueryServiceTest,DevMcpServerTest,DevServerLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test` after Phase 16 (13 focused tests green).
- [x] `./mvnw -pl dev-server -am test` after Phase 16 (all standard reactor and dev-server tests green; 14 profile-bound E2E tests skipped).
- [x] `./mvnw -B clean install` after Phase 16 (all 10 reactor modules green, including annotation processor and Java/Kotlin downstream projects).
- [x] `./mvnw -B install`
- [x] `./mvnw -pl dev-server -am -Dtest=CompilePipelineTest,DevServerConfigTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am -Dtest=TestPipelineTest,TestPlannerTest,CompilePipelineTest,DevSessionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am "-Dtest=DevServerWholeAppE2EIT#testFixtureChangesRunTestsWithoutRedeployAndHandlerChangesUseImpactIndex+impactIndexSelectsOnlyAffectedFixtureTestsAndFallsBackForSharedHelpers+successfulAppReloadCanLeaveTestsRedWithoutRollingBackTheApp+testCompileFailureDoesNotRedeployApp+addedHandlerTestAndRemovedHandlerProduceClearStaleImpactFailure" -Dfluxzero.devserver.e2e=true -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am test`
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e -Dit.test=DevServerWholeAppE2EIT#rollingReplacementKeepsLastGoodAppThroughCompileAndStartupFailures verify`
- [x] `./mvnw -pl dev-server -am -Dtest=CompilePipelineTest,TestPipelineTest,DevCommandPipelineTest,FrontendProcessTest,AppProcessRunnerTest,DevSessionStoreTest,DevServerLifecycleTest,DevServerCompileLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e '-Dit.test=DevServerWholeAppE2EIT#onlyMatchingClientIdCanCompleteReadinessAndTimeoutKeepsActiveApp+liveSeedChangesRunWithoutReloadAndReplayForFreshRuntime' verify`
- [x] `./mvnw -pl dev-server -am test` (53 dev-server tests, 11 E2E tests skipped by profile)
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e verify` (11 whole-app E2E tests)
- [x] `./mvnw -B clean install` (10-module reactor inclusief annotation-processor-, Java- en Kotlin-downstreamtests)
- [x] Live `flux-basic-java` smoke met `--fast-compiler`: initial `maven-full 3310ms`, hot reloads `javac-fast 766ms`/`314ms`, totals `1788ms`/`1326ms`.
- [x] `./mvnw -pl dev-server -am -Dtest=DevGatewayTest,DevServerFrontendGatewayTest,FrontendProcessTest -Dsurefire.failIfNoSpecifiedTests=false test` (6 gateway/lifecycle tests)
- [x] `./mvnw -pl dev-server -am -Pdev-server-frontend-e2e verify` (Vite 8.1.4 en Angular 22.0.6, inclusief source change en HMR/live-reload websocket)
- [x] `./mvnw -B clean install` na Phase 14 (10-module reactor, annotation-processor-tests en Java/Kotlin-downstreamtests)
- [x] `./mvnw -pl dev-server -am test` na Slice 14.9 (61 dev-server-tests; `/api` HTTP/WebSocket pass-through en routegrenzen gedekt)
- [x] `./mvnw -B clean install` na Slice 14.9 (10-module reactor en downstreamtests, 38.8s)
- [x] `./mvnw -pl dev-server -am -Dtest=DevLogStoreTest,DevSessionStoreTest,AppProcessRunnerTest,DevServerCompileLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test` na Phase 15 (13 gerichte tests groen).
- [x] `./mvnw -pl dev-server -am test` na Phase 15 (70 dev-server-tests; 14 profielgebonden E2E-tests overgeslagen).
- [x] `./mvnw -pl dev-server -am -Dtest=DevLogStoreTest -Dsurefire.failIfNoSpecifiedTests=false test` na SLF4J-levelherkenning (8 tests groen).
- [x] `./mvnw -pl dev-server -am -Pdev-server-e2e '-Dit.test=DevServerWholeAppE2EIT#appErrorRemainsActiveUntilItsInstanceIsReplaced' verify` (hele-app error-diagnostic en instance replacement bewezen).
- [x] `./mvnw -B clean install` na Phase 15 (10-module reactor, 71 dev-server-tests en annotation-processor-/Java-/Kotlin-downstreamtests, 38.9s).
- [x] `./gradlew build` in `fluxzero-cli` na Phase 17 (CLI, gedeelde dev-launcher, Maven/Gradle plugins, API, publishing, templates en project-files groen).
- [x] Live `fz dev` tegen `flux-basic-java` met lokale `0-SNAPSHOT`: runtime, proxy, IDP, MCP en Spring-app ready; `Ctrl+C` stopt app en services gecontroleerd en verwijdert het MCP-token.
- [x] Live `fz mcp` tegen dezelfde environment: initialize-response via stdio ontvangen en bevestigd dat stdout uitsluitend MCP-JSON bevat.
- [x] Live `fluxzero:dev` Maven-goal tegen `flux-basic-java`: volledige environment en app ready, gevolgd door gecontroleerde `Ctrl+C` shutdown.
- [x] `./mvnw -pl dev-server -am -Dtest=TestPipelineTest -Dsurefire.failIfNoSpecifiedTests=false test` na het deterministisch maken van de coalescing-test (6 tests groen).
- [x] `./mvnw -B clean install` na Phase 17 (10-module reactor, 79 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen, plus annotation-processor- en Java/Kotlin-downstreamtests).
- [x] `./mvnw -Dgpg.skip -DskipPublishing=true -B install -P deploy` na Phase 17 (release-POM, sources en Javadoc voor `dev-server` groen).
- [x] `./mvnw -pl dev-server -am -Dtest=MainClassDetectorTest,CompilePipelineTest,DevServerCompileLifecycleTest,AppProcessRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test` (15 main-detectie- en compiletests groen).
- [x] Live `fz dev` zonder `--main-class` tegen `flux-basic-java`: `com.example.app.App` gedetecteerd, app ready en `sync-project-files` exact eenmaal uitgevoerd.
- [x] Live `fz dev` zonder `--main-class` tegen `flux-basic-kotlin`: `com.example.app.App` gedetecteerd, app ready en `sync-project-files` exact eenmaal uitgevoerd.
- [x] `./mvnw -B clean install` na main-detectie en single-lifecycle compile (10 modules groen; 82 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen).
- [x] CLI installer-tests bewijzen `fz`/`fluxzero` bij fresh install en reparatie van een ontbrekende alias bij `fz upgrade` op de huidige versie.
- [x] `./gradlew build` in `fluxzero-cli` na main-class UX en command-aliases (alle 59 taken groen) plus syntaxchecks voor Unix install/uninstall en workflow-YAML.
- [x] Multi-app unitregressies voor Maven-reactorclasspaths, nested-module watching, frontend launch barrier en bestaande compile/app lifecycle.
- [x] `./mvnw -pl dev-server test` na hard-shutdown (92 tests, 14 profielgebonden E2E-tests overgeslagen), inclusief een open frontend-WebSocket tijdens gateway-stop.
- [x] Live dashboard shutdown met backend-app, Angular en gateway actief: `Ctrl+C` eindigde in 350ms zonder shutdownstacktrace; launcher-, app- en frontendprocessen waren daarna gestopt en `session.json` stond op `stopped`.
- [x] `./mvnw -B install` na Slice 14.13 (10 modules groen; 92 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen; 27.1s).
- [x] Live `dashboard.fluxzero.io` smoke: zes Spring Boot apps ontdekt; vier gezonde apps onafhankelijk geactiveerd; `app` legde de incompatibiliteit van de toenmalige `ENVIRONMENT=dev` default bloot en `registry` faalde afzonderlijk op een bezette hardcoded `8080`; een echte `core` source change rolde de vier gezonde apps naar build 2.
- [x] `./mvnw -B clean install` na Phase 18.1-18.6 (alle 10 reactor-modules groen; 84 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen, plus annotation-processor- en Java/Kotlin-downstreamtests).
- [x] CLI-, config-, reactor- en process-tests voor herhaalbare `--app`-selectie en configureerbaar `--environment`; whole-app seed/reload E2E groen met de `local` default en `application-local.properties`.
- [x] Live `dashboard.fluxzero.io` proef met `fz dev --app app --no-tests`: uitsluitend `host.flux.service.FluxHost` gestart als applicatie `app`, ready onder `ENVIRONMENT=local`, daarna gecontroleerd gestopt.
- [x] `.fluxzero/dev/.gitignore` wordt automatisch met `*` aangemaakt; dashboard-root negeert daarnaast expliciet `.fluxzero/dev/`.
- [x] Vroege bezette-gatewaypoortdetectie live bewezen: non-interactive één compacte fout zonder gestarte infrastructuur; interactieve bevestiging kiest een dynamische gateway. `--idp external` start daarbij geen managed IDP en injecteert geen OIDC-overrides.
- [x] `.fluxzero/dev.yaml` parser- en precedence-tests plus live dashboard discovery zonder equivalente CLI-config; onbekende keys falen compact en Maven-plugindefaults overrulen het projectbestand niet.
- [x] `./mvnw -B clean install` na tracked projectconfig (alle 10 reactor-modules groen; 91 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen, plus annotation-processor- en Java/Kotlin-downstreamtests).
- [x] Dashboard gatewaydiagnose: publieke en interne index identiek; `/api/query` via `localhost:4200` bereikt `CoreUiEndpoint`; Angular local config gebruikt dezelfde origin; HMR upstream Host/Origin en duidelijke ready-URL gedekt door gatewaytests.
- [x] Live `dashboard.fluxzero.io` met `--app rebound`: test-main uit `app/src/test` geselecteerd, eigen runtime/proxy-bootstrap overgeslagen ten gunste van de embedded services, readiness in 1.7s; touch van `Rebound.java` gaf `test-compile` en rolling activation van build 2.
- [x] `./mvnw -B install` na expliciete test-appsupport (10 modules groen; 97 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen; 27.2s).
- [x] Terminalspinner unitbewijs voor geanimeerde/interleaved output en control-sequence-vrije non-interactive output; live `flux-basic-java` TTY-smoke doorliep compile, app-start en readiness.
- [x] `./mvnw -B install` na terminalspinner (10 modules groen; 99 dev-server-tests waarvan 14 profielgebonden E2E-tests overgeslagen; 28.5s).
- [x] Phase 19 focused tests: 19 config-, reactor- en app-processproeven groen, inclusief twee flavors van dezelfde test-app, gereserveerde supervisorvariabelen, ontbrekende CLI en secretinjectie via een fake `op` childproces.
- [x] Whole-app Phase 19 E2E: twee benoemde flavors starten als afzonderlijke Fluxzero instances uit een build en worden beide rolling vervangen na een source change.
- [x] `./mvnw -B install` na Phase 19 (alle 10 reactor-modules groen; 106 dev-server-tests waarvan 15 profielgebonden E2E-tests overgeslagen, plus annotation-processor- en Java/Kotlin-downstreamtests; 27.5s).
- [x] `./gradlew build` in `fluxzero-cli` na Phase 19 CLI-help (alle 59 taken groen; CLI, Maven/Gradle plugins, launcher, templates en packaging).
- [x] `./mvnw -pl dev-server -am test` na Phase 20 (113 dev-server-tests groen; 15 profielgebonden E2E-tests overgeslagen).
- [x] `DevServerWholeAppE2EIT#retriesSeedCommandAfterHandlerIsAdded` met `fluxzero.devserver.e2e=true`: success-link pas na app-readiness, 1 whole-app scenario groen.
- [x] `./mvnw -pl dev-server test` na Slice 20.8 (116 dev-server-tests groen; 15 profielgebonden E2E-tests overgeslagen), inclusief hard-kill van een compile-child die `INT` en `TERM` negeert.
- [x] Live dashboardproef tijdens actieve Maven-compile: één `Ctrl+C` stopte CLI en volledige child-process tree in 0.6s, zonder failureblok; poort 4200 was vrij en alle session-services stonden op `stopped`.
- [x] `./mvnw -pl dev-server -am install` na Slice 20.9 (117 dev-server-tests groen; 15 profielgebonden E2E-tests overgeslagen) en `./gradlew :dev-launcher:test :cli:test :cli:shadowJar` groen.
- [x] Live dashboardproeven tijdens zowel `0-SNAPSHOT`-resolutie als actieve app-compile: elk eindigde na één `Ctrl+C` met exact één afsluitregel; compile-shutdown duurde 0.58s en liet geen listener op 4200 achter.
- [x] Schone lokale reactor-install na een gelijktijdige `0-SNAPSHOT`-overschrijving; de geïnstalleerde test-server-jar bevat de readiness monitor weer en dashboard bereikte `compile=running`. Een expres gestript artifact eindigde binnen 0.3s met exitcode 2 en één compacte reinstall-melding.
- [x] `./mvnw -pl dev-server test` na startup-linkage-hardening (118 tests groen; 15 profielgebonden E2E-tests overgeslagen).
- [x] Launcher-subprocessregressie bewijst de volgorde `Stopping Fluxzero dev server...`, `child stopped`, `Fluxzero dev server stopped.` en pas daarna parent-exit; volledige CLI-/launcher-tests en shadow jar groen.
- [x] Live dashboardproef ná volledige app- en Angular-readiness: één `Ctrl+C`, stopregel vóór launcher-exit, 0.64s shutdown, geen processen/listener op 4200 en alle session-services `stopped`.
- [x] Launcher-regressies voor interrupt tijdens dependency-resolutie en gelijktijdige callback/exitcode publiceren exact één stopregel; de subprocessproef wacht aantoonbaar op child-cleanup voordat de launcher eindigt.
- [x] De `standalone` dev-server bevat `DevServerMain`, test-server readiness metrics, proxy en MCP plus service descriptors; dashboard-resolutie levert exact één classpath-entry op.
- [x] Live dashboard shutdown-matrix met de standalone launcher: drie interrupts tijdens resolutie/overgang, één tijdens actieve Maven-compile en één na volledige Angular/app-readiness; iedere run eindigde binnen 0.7s met exact één stopregel en zonder listener op 4200.
- [x] `./mvnw -B -pl dev-server -am test` na de shutdown-architectuur (118 dev-server-tests groen; 15 profielgebonden E2E-tests overgeslagen).
- [x] `./mvnw -Dgpg.skip -DskipPublishing=true -B install -P deploy`: alle 10 modules, downstream-projecten, sources/Javadoc en installatie van zowel de dunne als `standalone` dev-server artifacts groen.
- [x] Geforceerde `./gradlew :dev-launcher:test --rerun-tasks` (8 launchertests groen), inclusief resolver-interrupt, outcome-deduplicatie en echte parent/child signal ordering.
- [x] Verse dashboardproef met de definitieve artifacts: interrupt tijdens actieve compile gaf alleen `^C` en exact één `Fluxzero dev server stopped.`, exitcode 130 in 0.59s en geen listener op 4200.
- [x] Launch-scoped shutdownregressie: 10 geforceerde launchertests groen, inclusief een echt subprocess dat tijdens de processloze overgang tussen children wordt gesignaleerd; verse dashboardinterrupts tijdens resolutie en compile eindigden beide binnen 0.6s met exact één stopregel en zonder listener op 4200.
- [x] Echte Terminal.app-reproductie identificeerde eager `Init()`/JLine-initialisatie als `SIGINT`-interceptor; een subprocessregressie bouwt de volledige CLI-command graph met `Init` naast `Dev`, stuurt echte `SIGINT` en bewijst exitcode 130 plus uitvoering van de dev-shutdown hook.
- [x] Twee Terminal.app-proeven met echte ETX na de JLine-fix: tijdens environment-start en tijdens `Compiling application (4.2s)` verscheen exact één `Fluxzero dev server stopped.`; `session.json` eindigde op `stopped` en poort 4200 was vrij.
- [x] Tweefasige shutdownregressie: 11 gerichte dev-server-tests en alle CLI-/launcher-tests groen; een echt `SIGINT`-subprocess bewijst dat de stopping-regel vóór child-cleanup verschijnt en de stopped-regel erna.
- [x] CLI packagingregressie: `fluxzero-cli-dev-plain.jar` en runnable `fluxzero-cli-dev.jar` zijn afzonderlijke artifacts; volledige `./gradlew build` (60 taken) en `verifyRunnableJar` groen, gevolgd door succesvolle `java -jar ... --help`.
- [x] Progress/outputregressie: 120 dev-servertests groen, inclusief parsercases voor Maven-module, resources, Java-sourceaantal, dependencies en fast-compiler fallback plus ANSI-/non-ANSI-terminalrendering.
- [x] Echte Terminal.app dashboardproef wisselde tijdens de reactorbuild dynamisch tot `Compiling application: registry: resolving dependencies`; failure verscheen als gekleurde `Fluxzero dev could not start` met title-case labels en cleanup liet poort 4200 vrij.
- [x] Stabiele frontend-readiness: deterministische kloktests plus gatewayintegratie bewijzen dat een eerste succesvolle probe nog geen publieke readiness geeft, de publieke route tijdens kwalificatie `503` blijft en pas na drie gezonde seconden `200` levert; `./mvnw -B -pl dev-server -am install` groen met 123 dev-servertests waarvan 15 profielgebonden E2E-tests overgeslagen.
- [x] Dashboard-testpipelineproef: `surefire:test` draaide 144 tests zonder Angular-productiebuild en liet `core/target/typescript-generator/core.d.ts` byte- en timestamp-identiek; 16.5s tegenover 23.7s voor de muterende Maven `test` lifecycle.
- [x] `./mvnw -B -pl dev-server test` na Slice 20.20 (137 dev-servertests, 15 profielgebonden E2E-tests overgeslagen) en installatie van het bijgewerkte `dev-server-0-SNAPSHOT-standalone.jar` groen.
- [x] Watcherregressie na Slice 20.21: Java-source, testresources, TypeScript-generatorinput en module-POM worden gedetecteerd; `target`, `.idea`, `.run`, `.angular` en Angular `src/app` blijven buiten de backendpipeline. Volledige dev-serversuite opnieuw groen (137 tests, 15 profielgebonden E2E-tests overgeslagen).
- [x] Phases 21-23 focused tests: 50 command-, planner-, testpipeline-, terminal- en diagnosticsregressies groen.
- [x] `./mvnw -B -pl dev-server -am test` na Phases 21-23: 143 dev-servertests groen, 16 profielgebonden E2E-tests overgeslagen.
- [x] Whole-app YAML-commandproef: live onbekend command faalt, gewijzigde definitie slaagt zonder app-reload en de YAML map-key blijft de stabiele id.
- [x] Whole-app testimpactproef: gewijzigde test compileert en faalt echt, herstel draait dezelfde FQCN opnieuw zonder redeploy en een handlerwijziging selecteert daarna exact de observerende fixturetest.
- [x] `./mvnw -B install` na Phases 21-23: alle 10 reactor-modules groen, inclusief annotation-processor- en Java/Kotlin-downstreamtests (36.0s).
- [x] Phase 24 lifecycleproeven: een tweede sessie wordt door het project-lock geweigerd; control `status` reconcilieert een hard gekillde supervisor naar `stopped-unexpectedly`; commandstatus wordt stale zodat startup commands in de volgende runtime opnieuw lopen; `logs --follow` eindigt na stop.
- [x] Live detached Maven-smoke op macOS: `fz dev --background` overleeft het launcherproces via een projectgebonden `launchd` job; `status` vindt de omgeving en `stop` ruimt haar in 2.21s op.
- [x] Live Gradle whole-app smoke: `fluxzeroDevMetadata` compileert de fixture, `fluxzeroDev -Pfluxzero.dev.background=true` start runtime en app, een Java-handlerwijziging activeert rolling build 2 met een nieuwe app-PID en `fz dev stop` ruimt de sessie op.
- [x] `./gradlew build` in `fluxzero-cli` na Phases 24-25: alle 60 taken groen, inclusief CLI-, detached-launcher-, Maven-plugin- en Gradle-pluginfunctional tests (36s).
- [x] `fz dev --help` en een Gradle TestKit/consumerproef van `./gradlew help --task fluxzeroDev` tonen de lifecycle-, app-, frontend-, test- en backgroundopties inclusief native `--no-*` varianten.
- [x] `./mvnw -B install` na Phases 24-25: alle 10 reactor-modules groen; 149 dev-servertests waarvan 16 profielgebonden E2E-tests overgeslagen, plus annotation-processor- en Java/Kotlin-downstreamtests (1m10s wall clock).
- [x] Phase 26 focused regressies: 16 readiness-, frontendprocess- en gatewaytests groen; een reloadrequest wacht tijdens transient unavailability, herstelt met `200` en houdt shutdown onder 1 seconde.
- [x] Vite 8.1.4 en Angular 22.0.6 framework-E2E met Node 24: tokenized HMR-connectie, echte sourcewijziging, directe publieke navigatie zonder `503` en gewijzigde bundle groen.
- [x] `./mvnw -B install` na Phase 26: alle 10 reactor-modules groen, inclusief 151 dev-servertests waarvan 16 profielgebonden E2E-tests overgeslagen en Java/Kotlin downstreamprojecten (36.8s wall clock).
- [x] Phase 27 focused regressies: 17 baseline/session/pipeline-tests plus 12 pipeline/watcher-tests groen.
- [x] Phase 27 whole-app regressies: 5 fixture-appscenario's groen, inclusief initial baseline, gerichte impactselectie, rode tests zonder rollback en atomair toevoegen/verwijderen van handler plus test.
- [x] `./mvnw -B install` na Phase 27: alle 10 reactor-modules groen, inclusief 156 dev-servertests waarvan 16 profielgebonden E2E-tests overgeslagen en Java/Kotlin downstreamprojecten (36.7s wall clock).
- [x] Dashboard live rebuild na Slice 28.1: Maven-backendcompile daalde van 21.2s naar 2.6s; de frontendmodule zelf van 18.8s naar 0.01s.
- [x] Dashboard `./mvnw -pl frontend -am -DskipTests package`: `npm-install` gevolgd door `npm-build`, frontendmodule groen in 12.2s.
- [x] Phase 28 progressregressies: 7 parser- en terminaltests groen, inclusief actuele npm- en Fluxzero-pluginstappen.
- [x] Dashboard npm-setupbenchmark: up-to-date `npm install --prefer-offline --no-audit --no-fund` inclusief `postinstall` in 1.31s; manifest en lockfile bleven byte-identiek.
- [x] Phase 29 frontendprocessregressies: 7 frameworkvrije proeven groen, inclusief genegeerde compilerteksten, setupwerkmap, bounded process-exit en herstel na blijvende HTTP-unavailability.
- [x] `./mvnw -B -pl dev-server -am test` na Phase 29: 159 dev-servertests groen, 16 profielgebonden E2E-tests overgeslagen.
- [x] `./gradlew build` in `fluxzero-cli` na Phase 29: alle 60 taken groen, inclusief CLI-, Maven-plugin-, Gradle-plugin- en functional tests.
- [x] Live dashboardproef: npm-setup eenmaal in 1.4s, backend en Angular parallel ready in 12.7s, publieke gateway `200`; vervolgproef ready in 11.3s.
- [x] Phase 29 shutdownbarrier: 27 gerichte lifecycle/config/frontendtests groen; live `Ctrl+C` liet status, frontend en gateway direct en na 500ms op `stopped` en poort 4200 vrij.
- [x] Live dashboard backendproef: wijziging in de expliciete `Rebound` test-app compileerde in 3.2s en activeerde build 2 in totaal 6.7s; herstel compileerde in 3.0s en activeerde build 3 in 6.3s.
- [x] Phase 30 plannerregressies: 12 planner- en 9 pipelineproeven groen; `Rebound.java` selecteert geen test, een onconventioneel benoemde JUnitclass wel en een verwijderde test blijft retryable.
- [x] Phase 31 gatewayregressies: 13 gerichte HTTP/WebSocket- en supervisorproeven groen; vroege `/api`-requests krijgen direct `503`, `/_fluxzero` blijft bereikbaar en readiness opent en sluit de gate dynamisch.
- [x] `./mvnw -B -pl dev-server -am test` na Phase 31: 166 dev-servertests groen, 16 profielgebonden E2E-tests overgeslagen.
- [x] Live daemon-first smoke: `d` en `detach` laten supervisor/app doorlopen; een backendwijziging tijdens detach wordt
  na `fz dev attach` eenmaal gereplayed; kale `fz dev` attach't direct; `q` gevolgd door `s` stopt supervisor en app
  en zet de sessie op `stopped`.
- [x] Phase 32 lifecycle-/attachmentregressies: short en long commands, gemiste-outputreplay, sessiegebonden cursor,
  EOF-detach, signal-stop en stop via een afwijkende projectpadalias zijn groen.
- [x] Live PTY-signaalproef na Slice 32.8: `Ctrl+C` publiceerde exact één stopregel en stopte supervisor, app, runtime
  en proxy; een tweede run met `d` liet dezelfde onderdelen aantoonbaar actief tot de expliciete `fz dev stop`.
- [x] `./mvnw -B install` na Phase 32: alle 10 modules groen, inclusief 177 dev-servertests waarvan 16
  profielgebonden E2E-tests overgeslagen, annotation-processor-tests en Java/Kotlin-downstreamprojecten.
- [x] `./gradlew build` plus geforceerde launcher-, CLI-, Maven-plugin- en Gradle-pluginfunctional tests in
  `fluxzero-cli` na Phase 32 zijn groen; de runnable CLI-jar en verpakte `dev --help` zijn geverifieerd.
- [x] Multi-module Surefire recovery live bewezen in `dashboard.fluxzero.io`: root- en niet-matchende modules werden
  overgeslagen, `MolliePaymentTest` en `MollieTest` draaiden 38 tests groen in een reactorbuild van 3.1s.
- [x] `./mvnw -B -pl dev-server test` na IntelliJ-compatibele klikbare loglocaties: 187 tests groen, 16 profielgebonden
  E2E-tests overgeslagen, inclusief teststatus- en Maven-outputdiagnostics die samen sluiten na een groene retry.
- [x] Live `fz dev` PTY-proef met een echte terminalidentiteit na Slice 32.10: een losse `q` bleef invoer en opende pas
  na Enter het menu;
  pijl-omlaag plus Enter stopte daarna de omgeving.

## Open Implementation Notes

- Maven uses one `test-compile dependency:build-classpath` lifecycle for a baseline with an explicit runtime classpath;
  Gradle uses `fluxzeroDevMetadata` after its own `classes`/`testClasses` tasks. Successful builds are copied into
  immutable session-owned snapshots before an app process starts; Maven `javac-fast` falls back to Maven on failure.
- Test-app mains worden nooit automatisch ontdekt, maar kunnen expliciet worden gekozen wanneer hun testgedrag onderdeel van de gewenste dev-omgeving is. De supervisor levert ook dan runtime/proxy/IDP extern; test-apps horen eigen bootstrap te skippen en startup-seeding idempotent of via dev commands uit te voeren.
- De supervisor ondersteunt meerdere apps binnen een Maven-reactor of Gradle multi-project build op een gedeelde
  runtime. Meerdere losstaande projectroots in een environment blijven vervolgwerk in Phase 18.
- Browser runtime, blueprint, reflectionless/Jackson-free work and persistent dev-store remain intentionally outside this roadmap.
