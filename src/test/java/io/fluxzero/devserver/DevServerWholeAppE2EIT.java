/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.idp.client.Pkce;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.publishing.DefaultRequestHandler;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "fluxzero.devserver.e2e", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class DevServerWholeAppE2EIT {
    private static final String MAIN_CLASS = "com.example.app.App";
    private static final String APP_NAME = "plain-e2e-app";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void retriesSeedCommandAfterHandlerIsAdded(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);
        writeCommand(project, "optional.json", "com.example.app.CreateOptionalGreeting", """
                {
                  "name": "Ada"
                }
                """);

        DevServerConfig config = config(project);
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        try (DevServer ignored = new DevServer(
                config, value -> false,
                new TerminalProgress(false, new PrintStream(terminal, true, UTF_8))).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForAppState(project, "running");
            String startupOutput = terminal.toString(UTF_8);
            assertTrue(startupOutput.contains("Fluxzero dev server ready"), startupOutput);
            assertTrue(startupOutput.contains("Backend       http://localhost:"), startupOutput);
            assertFalse(startupOutput.contains("Fluxzero dev could not start"), startupOutput);
            assertEquals("failed", waitForCommand(project, "optional.json", "failed").state());

            writeHandlers(project, true, false);

            DevCommandStatus.Entry entry = waitForCommand(project, "optional.json", "succeeded");
            assertTrue(entry.detail().contains("processed by app"), entry.detail());
            JsonNode snapshot = waitForGreeting(rawClient, "optional:Ada:v1");
            assertEquals("local", snapshot.path("environment").asText());
            assertEquals("enabled", snapshot.path("devMode").asText());
        }
    }

    @Test
    void appErrorRemainsActiveUntilItsInstanceIsReplaced(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);
        writeRuntimeError(project, true);
        DevServerConfig config = config(project);

        try (DevServer ignored = new DevServer(config).start()) {
            DevSession first = waitForSession(project, session -> "running".equals(session.app().state()),
                                              "first app ready");
            long firstPid = first.app().pid();
            DevProblem problem = waitForProblem(project, config.applicationName());
            assertEquals(config.applicationName(), problem.serviceId());
            assertTrue(problem.instanceId().contains("-build-1"), problem.instanceId());
            assertEquals(DevLogEvent.Level.ERROR, problem.severity());

            writeRuntimeError(project, false);

            waitForSession(project, session -> "running".equals(session.app().state())
                                                  && session.app().pid() != null && session.app().pid() != firstPid,
                           "replacement app ready");
            waitForNoProblem(project, config.applicationName());
            assertTrue(Files.readString(Path.of(first.observability().problems())).contains("resolved"));
        }
    }

    @Test
    void rollingReplacementKeepsLastGoodAppThroughCompileAndStartupFailures(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project, true);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            JsonNode first = waitForVersion(rawClient, "v1");
            long firstPid = waitForSession(project, session -> session.app().pid() != null, "app pid").app().pid();
            assertEquals("enabled", first.path("devMode").asText());

            writeVersion(project, "v2", false);

            waitForVersion(rawClient, "v2");
            waitForSession(project,
                           session -> session.compile().detail() != null
                                      && session.compile().detail().contains("javac-fast"),
                           "javac-fast reload");
            long secondPid = waitForSession(
                    project, session -> session.app().pid() != null && session.app().pid() != firstPid,
                    "replacement pid").app().pid();
            assertNotEquals(firstPid, secondPid);

            writeBrokenVersion(project);

            waitForSession(project, session -> "failed".equals(session.compile().state()), "compile failure");
            assertEquals("v2", rawClient.greetingState().path("version").asText());

            writeVersion(project, "v3", false);

            waitForVersion(rawClient, "v3");
            long thirdPid = waitForSession(
                    project, session -> session.app().pid() != null && session.app().pid() != secondPid,
                    "second replacement pid").app().pid();

            writeVersion(project, "boom", true);

            DevSession failedReload = waitForSession(
                    project, session -> "failed".equals(session.reload().state()), "startup failure");
            assertEquals("running", failedReload.app().state());
            assertEquals(thirdPid, failedReload.app().pid());
            assertEquals("v3", rawClient.greetingState().path("version").asText());

            writeVersion(project, "v4", false);

            waitForVersion(rawClient, "v4");
            long fourthPid = waitForSession(
                    project, session -> session.app().pid() != null && session.app().pid() != thirdPid,
                    "startup recovery pid").app().pid();
            assertNotEquals(thirdPid, fourthPid);
        }
    }

    @Test
    void namedFlavorsOfTheSameApplicationRollIndependentlyFromOneBuild(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);
        String applicationName = APP_NAME + "-flavors-" + UUID.randomUUID();
        DevServerConfig config = new DevServerConfig(
                project, MAIN_CLASS, applicationName, null,
                true, true, false, Duration.ofSeconds(35), Duration.ofSeconds(2), Duration.ofMillis(150),
                FrontendConfig.none(), List.of(), false, "local",
                List.of("plain", "alternate"), 0, IdpMode.MANAGED,
                Map.of("plain", new DevApplicationConfig(MAIN_CLASS, null, Map.of("MODE", "plain"), Map.of()),
                       "alternate", new DevApplicationConfig(
                               MAIN_CLASS, null, Map.of("MODE", "alternate"), Map.of())));

        try (DevServer ignored = new DevServer(config).start()) {
            DevSession first = waitForSession(project, session -> {
                Map<String, String> metadata = session.app().metadata();
                return "running".equals(session.app().state())
                       && "2".equals(metadata.get("count"))
                       && metadata.containsKey("application.plain.pid")
                       && metadata.containsKey("application.alternate.pid");
            }, "both named app flavors ready");
            long plainPid = Long.parseLong(first.app().metadata().get("application.plain.pid"));
            long alternatePid = Long.parseLong(first.app().metadata().get("application.alternate.pid"));
            assertNotEquals(plainPid, alternatePid);
            assertEquals(applicationName, first.app().metadata().get("application.plain.applicationName"));
            assertEquals("MODE", first.app().metadata().get("application.alternate.environment"));

            writeVersion(project, "v2", false);

            DevSession second = waitForSession(project, session -> {
                Map<String, String> metadata = session.app().metadata();
                String newPlain = metadata.get("application.plain.pid");
                String newAlternate = metadata.get("application.alternate.pid");
                return "succeeded".equals(session.reload().state())
                       && newPlain != null && Long.parseLong(newPlain) != plainPid
                       && newAlternate != null && Long.parseLong(newAlternate) != alternatePid;
            }, "both named app flavors replaced");
            assertEquals("2", second.app().metadata().get("count"));
            assertNotEquals(second.app().metadata().get("application.plain.clientId"),
                            second.app().metadata().get("application.alternate.clientId"));
        }
    }

    @Test
    void onlyMatchingClientIdCanCompleteReadinessAndTimeoutKeepsActiveApp(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);
        String applicationName = APP_NAME + "-readiness-" + UUID.randomUUID();
        DevServerConfig config = new DevServerConfig(
                project, MAIN_CLASS, applicationName, null,
                true, true, false, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(150),
                FrontendConfig.none(), List.of(), true);

        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            long activePid = currentAppPid(project);
            writeVersion(project, "disconnected", false, false);
            waitForSession(project, session -> "running".equals(session.reload().state()), "candidate starting");

            DevSession running = new DevSessionStore(project).readSession().orElseThrow();
            WebSocketClient impostor = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl(running.runtime().url())
                    .name(applicationName)
                    .id("wrong-client-id")
                    .build());
            try {
                SerializedMessage metric = new SerializedMessage(
                        new Data<>("{}".getBytes(UTF_8), "ReadinessProbe", 0, Data.JSON_FORMAT),
                        Metadata.empty(), "readiness-probe", System.currentTimeMillis());
                impostor.getGatewayClient(MessageType.METRICS).append(Guarantee.SENT, metric).get(5, TimeUnit.SECONDS);

                DevSession failed = waitForSession(
                        project, session -> "failed".equals(session.reload().state()), "strict readiness timeout");
                assertTrue(failed.reload().detail().contains("readiness timeout"), failed.reload().detail());
                assertEquals("running", failed.app().state());
                assertEquals(activePid, failed.app().pid());
                assertEquals("v1", rawClient.greetingState().path("version").asText());
            } finally {
                impostor.shutDown();
            }

            writeVersion(project, "v2", false);
            waitForVersion(rawClient, "v2");
            DevSession recovered = waitForSession(
                    project, session -> "succeeded".equals(session.reload().state()), "readiness recovery");
            assertNotEquals(activePid, recovered.app().pid());
        }
    }

    @Test
    void liveSeedChangesRunWithoutReloadAndReplayForFreshRuntime(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);
        DevServerConfig firstConfig = config(project);
        String firstSessionId;

        try (DevServer ignored = new DevServer(firstConfig).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            long appPid = currentAppPid(project);
            firstSessionId = new DevSessionStore(project).readSession().orElseThrow().sessionId();

            writeCommand(project, "live.json", "com.example.app.CreateGreeting", """
                    {
                      "name": "Live"
                    }
                    """);

            waitForCommand(project, "live.json", "succeeded");
            waitForGreeting(rawClient, "base:Live:v1");
            assertEquals(appPid, currentAppPid(project));
        }

        DevServerConfig secondConfig = config(project);
        try (DevServer ignored = new DevServer(secondConfig).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            DevCommandStatus.Entry replayed = waitForCommand(project, "live.json", "succeeded");
            assertTrue(replayed.detail().contains("processed by app"), replayed.detail());
            DevCommandStatus status = new DevSessionStore(project).readCommandStatus().orElseThrow();
            assertNotEquals(firstSessionId, status.sessionId());
            waitForGreeting(rawClient, "base:Live:v1");
        }
    }

    @Test
    void liveYamlCommandRecoversWithoutReload(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);

        try (DevServer ignored = new DevServer(config(project)).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            long appPid = currentAppPid(project);

            writeYamlCommand(project, "com.example.app.UnknownGreeting", "Yaml");
            waitForCommandIdentity(project, "commands.live-greeting", "failed");
            assertEquals(appPid, currentAppPid(project));

            writeYamlCommand(project, "com.example.app.CreateGreeting", "Yaml");
            waitForCommandIdentity(project, "commands.live-greeting", "succeeded");
            waitForGreeting(rawClient, "base:Yaml:v1");
            assertEquals(appPid, currentAppPid(project));
        }
    }

    @Test
    void testFixtureChangesRunTestsWithoutRedeployAndHandlerChangesUseImpactIndex(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project, true, true);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            TestStatus initial = waitForTestStatus(project,
                                                   status -> "passed".equals(status.state())
                                                             && "initial test baseline".equals(status.reason()),
                                                   "initial fixture tests");
            assertEquals(List.of(), initial.selectors());
            waitForImpact(project, "com.example.app.GreetingHandlersTest#createsBaseGreeting",
                          "com.example.app.GreetingHandlers");
            long initialPid = currentAppPid(project);

            writeGreetingHandlersTest(project, "wrong:Test:v1");

            TestStatus failed = waitForTestStatus(project,
                                                  status -> "failed".equals(status.state())
                                                            && status.selectors().equals(List.of(
                                                                    "com.example.app.GreetingHandlersTest")),
                                                  "failed changed fixture test");
            assertEquals("changed test class", failed.reason());
            assertTrue(failed.detail().contains("BUILD FAILURE")
                       || failed.detail().contains("GreetingHandlersTest"), failed.detail());
            assertEquals(initialPid, currentAppPid(project));

            writeGreetingHandlersTest(project, "base:Test:v1");

            TestStatus fixed = waitForTestStatus(project,
                                                 status -> "passed".equals(status.state())
                                                           && status.selectors().equals(List.of(
                                                                   "com.example.app.GreetingHandlersTest"))
                                                           && "changed test class".equals(status.reason()),
                                                 "fixed changed fixture test");
            assertEquals(initialPid, currentAppPid(project));

            writeHandlers(project, false, false);

            long replacementPid = waitForSession(
                    project, session -> session.app().pid() != null && session.app().pid() != initialPid,
                    "replacement pid after handler change").app().pid();
            assertNotEquals(initialPid, replacementPid);
            TestStatus impacted = waitForTestStatus(project,
                                                    status -> "passed".equals(status.state())
                                                              && "test impact index".equals(status.reason())
                                                              && status.selectors().equals(List.of(
                                                                      "com.example.app.GreetingHandlersTest#createsBaseGreeting")),
                                                    "impact-index selected fixture test");
            assertTrue(impacted.updatedAt() >= fixed.updatedAt());
        }
    }

    @Test
    void impactIndexSelectsOnlyAffectedFixtureTestsAndFallsBackForSharedHelpers(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project, true, true);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            TestStatus initial = waitForTestStatus(project,
                                                   status -> "passed".equals(status.state())
                                                             && "initial test baseline".equals(status.reason()),
                                                   "initial fixture tests");
            waitForImpact(project, "com.example.app.PrimaryFixtureHandlerTest#createsPrimaryFixture",
                          "com.example.app.PrimaryFixtureHandler");
            waitForImpact(project, "com.example.app.SecondaryFixtureHandlerTest#createsSecondaryFixture",
                          "com.example.app.SecondaryFixtureHandler");

            writePrimaryFixtureHandler(project);

            TestStatus primary = waitForTestStatusAfter(
                    project, initial.updatedAt(),
                    status -> "passed".equals(status.state())
                              && "test impact index".equals(status.reason())
                              && status.selectors().equals(List.of(
                                      "com.example.app.PrimaryFixtureHandlerTest#createsPrimaryFixture")),
                    "primary handler impact test");

            writeSecondaryFixtureHandler(project);

            TestStatus secondary = waitForTestStatusAfter(
                    project, primary.updatedAt(),
                    status -> "passed".equals(status.state())
                              && "test impact index".equals(status.reason())
                              && status.selectors().equals(List.of(
                                      "com.example.app.SecondaryFixtureHandlerTest#createsSecondaryFixture")),
                    "secondary handler impact test");

            writeFixtureFormatting(project);

            TestStatus helperFallback = waitForTestStatusAfter(
                    project, secondary.updatedAt(),
                    status -> "passed".equals(status.state())
                              && "changed app code fallback".equals(status.reason())
                              && status.selectors().isEmpty(),
                    "shared helper fallback tests");
            assertTrue(helperFallback.updatedAt() > secondary.updatedAt());
        }
    }

    @Test
    void successfulAppReloadCanLeaveTestsRedWithoutRollingBackTheApp(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project, true, true);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            TestStatus initial = waitForTestStatus(project,
                                                   status -> "passed".equals(status.state())
                                                             && "initial test baseline".equals(status.reason()),
                                                   "initial fixture tests");
            long initialPid = currentAppPid(project);

            writeVersion(project, "v2", false);

            waitForVersion(rawClient, "v2");
            long replacementPid = waitForSession(
                    project, session -> session.app().pid() != null && session.app().pid() != initialPid,
                    "replacement pid before red tests").app().pid();
            TestStatus failed = waitForTestStatusAfter(
                    project, initial.updatedAt(),
                    status -> "failed".equals(status.state())
                              && "changed app code fallback".equals(status.reason()),
                    "red tests after successful app reload");
            assertTrue(failed.detail().contains("GreetingHandlersTest")
                       || failed.detail().contains("PrimaryFixtureHandlerTest"), failed.detail());
            assertEquals("v2", rawClient.greetingState().path("version").asText());
            assertEquals(replacementPid, currentAppPid(project));
        }
    }

    @Test
    void testCompileFailureDoesNotRedeployApp(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project, true, true);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            TestStatus initial = waitForTestStatus(project,
                                                   status -> "passed".equals(status.state())
                                                             && "initial test baseline".equals(status.reason()),
                                                   "initial fixture tests");
            long initialPid = currentAppPid(project);

            writeBrokenGreetingHandlersTest(project);

            TestStatus failed = waitForTestStatusAfter(
                    project, initial.updatedAt(),
                    status -> "failed".equals(status.state())
                              && "changed test class".equals(status.reason())
                              && status.selectors().equals(List.of("com.example.app.GreetingHandlersTest")),
                    "test compile failure");
            assertTrue(failed.detail().contains("COMPILATION ERROR")
                       || failed.detail().contains("GreetingHandlersTest.java"), failed.detail());
            assertEquals("v1", rawClient.greetingState().path("version").asText());
            assertEquals(initialPid, currentAppPid(project));
        }
    }

    @Test
    void addedHandlerTestAndRemovedHandlerProduceClearStaleImpactFailure(@TempDir Path tempDirectory)
            throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project, true, true);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");
            TestStatus initial = waitForTestStatus(project,
                                                   status -> "passed".equals(status.state())
                                                             && "initial test baseline".equals(status.reason()),
                                                   "initial fixture tests");

            writeTemporaryFixture(project);

            TestStatus added = waitForTestStatusAfter(
                    project, initial.updatedAt(),
                    status -> "passed".equals(status.state())
                              && "changed test class".equals(status.reason())
                              && status.selectors().equals(List.of(
                                      "com.example.app.TemporaryFixtureHandlerTest")),
                    "added temporary fixture test");
            waitForImpact(project, "com.example.app.TemporaryFixtureHandlerTest#handlesTemporaryFixture",
                          "com.example.app.TemporaryFixtureHandler");

            Files.delete(project.resolve("src/main/java/com/example/app/TemporaryFixtureHandler.java"));

            TestStatus removed = waitForTestStatusAfter(
                    project, added.updatedAt(),
                    status -> "failed".equals(status.state())
                              && "test impact index".equals(status.reason())
                              && status.selectors().equals(List.of(
                                      "com.example.app.TemporaryFixtureHandlerTest#handlesTemporaryFixture")),
                    "removed handler stale impact test");
            assertTrue(removed.detail().contains("TemporaryFixtureHandler"), removed.detail());
            assertEquals("v1", rawClient.greetingState().path("version").asText());
        }
    }

    @Test
    void removingHandlerMakesNewSeedCommandFailWithoutKillingApp(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project);
        try (DevServer ignored = new DevServer(config).start();
             RawFluxzeroClient rawClient = new RawFluxzeroClient("ws://localhost:" + waitForRuntimePort(project))) {
            waitForVersion(rawClient, "v1");

            writeHandlers(project, false, true);
            writeCommand(project, "extra-1.json", "com.example.app.CreateExtraGreeting", """
                    {
                      "name": "One"
                    }
                    """);

            waitForCommand(project, "extra-1.json", "succeeded");
            waitForGreeting(rawClient, "extra:One:v1");

            writeHandlers(project, false, false);
            writeCommand(project, "extra-2.json", "com.example.app.CreateExtraGreeting", """
                    {
                      "name": "Two"
                    }
                    """);

            waitForCommand(project, "extra-2.json", "failed");
            JsonNode snapshot = rawClient.greetingState();
            assertEquals("v1", snapshot.path("version").asText());
            assertFalse(containsGreeting(snapshot, "extra:Two:v1"));
        }
    }

    @Test
    void authenticatedAppEndpointAcceptsManagedIdpToken(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture(tempDirectory);

        DevServerConfig config = config(project);
        try (DevServer ignored = new DevServer(config).start()) {
            DevSession session = waitForSession(project, s -> "running".equals(s.app().state()), "app running");
            String proxyUrl = session.proxy().url();

            HttpResponse<String> anonymous = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/secure/me"))
                    .GET()
                    .build());
            assertEquals(401, anonymous.statusCode(), anonymous.body());

            String accessToken = accessToken(proxyUrl, "rene@example.com");
            HttpResponse<String> authenticated = await("authenticated app endpoint", () -> {
                HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/secure/me"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build());
                return response.statusCode() == 200 ? response : null;
            });

            JsonNode identity = objectMapper.readTree(authenticated.body());
            assertEquals("rene@example.com", identity.path("subject").asText());
            assertEquals("rene@example.com", identity.path("email").asText());
            assertEquals("local-auth", identity.path("tenantId").asText());
            assertTrue(identity.path("authenticated").asBoolean());
            assertEquals("v1", identity.path("version").asText());
        }
    }

    private static DevServerConfig config(Path projectDirectory) {
        return config(projectDirectory, false);
    }

    private static DevServerConfig config(Path projectDirectory, boolean fastCompilerEnabled) {
        return config(projectDirectory, fastCompilerEnabled, false);
    }

    private static DevServerConfig config(Path projectDirectory, boolean fastCompilerEnabled, boolean testsEnabled) {
        return new DevServerConfig(
                projectDirectory, MAIN_CLASS, APP_NAME + "-" + UUID.randomUUID(), null,
                true, true, testsEnabled, Duration.ofSeconds(35), Duration.ofSeconds(2), Duration.ofMillis(150),
                FrontendConfig.none(), List.of(), fastCompilerEnabled);
    }

    private static Path copyFixture(Path tempDirectory) throws IOException, URISyntaxException {
        URL resource = DevServerWholeAppE2EIT.class.getResource("/e2e-fixtures/plain-app");
        if (resource == null) {
            throw new IllegalStateException("Missing plain-app E2E fixture");
        }
        Path source = Path.of(resource.toURI());
        Path target = tempDirectory.resolve("plain-app");
        copyTree(source, target);
        copyMavenWrapper(devServerRepositoryRoot(), target);
        Path pom = target.resolve("pom.xml");
        String version = System.getProperty("fluxzero.project.version", "0-SNAPSHOT");
        Files.writeString(pom, Files.readString(pom)
                .replace("@FLUXZERO_VERSION@", version), UTF_8);
        return target;
    }

    private static void writeRuntimeError(Path project, boolean enabled) throws IOException {
        Path source = project.resolve("src/main/java/com/example/app/App.java");
        String marker = "        System.out.println(\"plain fixture app ready \" + AppVersion.VALUE);";
        String error = System.lineSeparator()
                       + "        System.err.println(\"ERROR fixture runtime failure \" + AppVersion.VALUE);";
        String content = Files.readString(source);
        content = content.replace(marker + error, marker);
        Files.writeString(source, enabled ? content.replace(marker, marker + error) : content, UTF_8);
    }

    private static DevProblem waitForProblem(Path project, String serviceId) throws Exception {
        Path diagnostics = project.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevLogStore.DIAGNOSTICS_FILE);
        return await("app problem active", () -> {
            if (!Files.isRegularFile(diagnostics)) {
                return null;
            }
            DevDiagnostics status = objectMapper.readValue(diagnostics.toFile(), DevDiagnostics.class);
            DevProblem problem = status.problems().stream()
                    .filter(candidate -> serviceId.equals(candidate.serviceId())
                                         && "application".equals(candidate.serviceType())
                                         && candidate.severity() == DevLogEvent.Level.ERROR
                                         && candidate.summary().contains("fixture runtime failure"))
                    .findFirst().orElse(null);
            return problem;
        });
    }

    private static void waitForNoProblem(Path project, String serviceId) throws Exception {
        Path diagnostics = project.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevLogStore.DIAGNOSTICS_FILE);
        await("app problem resolved", () -> {
            if (!Files.isRegularFile(diagnostics)) {
                return null;
            }
            DevDiagnostics status = objectMapper.readValue(diagnostics.toFile(), DevDiagnostics.class);
            boolean present = status.problems().stream().anyMatch(candidate -> serviceId.equals(candidate.serviceId())
                                                                             && "application".equals(
                    candidate.serviceType()) && candidate.severity() == DevLogEvent.Level.ERROR
                                                                             && candidate.summary().contains(
                    "fixture runtime failure"));
            return present ? null : Boolean.TRUE;
        });
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(file).toString());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyMavenWrapper(Path root, Path target) throws IOException {
        Files.copy(root.resolve("mvnw"), target.resolve("mvnw"), StandardCopyOption.REPLACE_EXISTING);
        target.resolve("mvnw").toFile().setExecutable(true);
        copyTree(root.resolve(".mvn"), target.resolve(".mvn"));
    }

    private static Path devServerRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("mvnw"))
                && Files.isDirectory(current.resolve("src/test/resources/e2e-fixtures"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate Fluxzero dev server repository root");
    }

    private static int waitForRuntimePort(Path projectDirectory) throws Exception {
        return waitForSession(projectDirectory, session -> session.runtime().port() != null, "runtime port")
                .runtime().port();
    }

    private static void waitForAppState(Path projectDirectory, String state) throws Exception {
        waitForSession(projectDirectory, session -> state.equals(session.app().state()), "app state " + state);
    }

    private static DevSession waitForSession(Path projectDirectory, Predicate<DevSession> predicate, String reason)
            throws Exception {
        DevSessionStore store = new DevSessionStore(projectDirectory);
        return await(reason, () -> store.readSession()
                .filter(predicate)
                .orElse(null));
    }

    private static DevCommandStatus.Entry waitForCommand(Path projectDirectory, String fileName, String state)
            throws Exception {
        String path = DevCommandPipeline.COMMAND_DIRECTORY.resolve(fileName).toString();
        return waitForCommandIdentity(projectDirectory, path, state);
    }

    private static DevCommandStatus.Entry waitForCommandIdentity(Path projectDirectory, String identity, String state)
            throws Exception {
        DevSessionStore store = new DevSessionStore(projectDirectory);
        return await("command " + identity + " " + state, () -> store.readCommandStatus()
                .flatMap(status -> status.commands().stream()
                        .filter(entry -> identity.equals(entry.path()) && state.equals(entry.state()))
                        .findFirst())
                .orElse(null));
    }

    private static TestStatus waitForTestStatus(Path projectDirectory, Predicate<TestStatus> predicate, String reason)
            throws Exception {
        DevSessionStore store = new DevSessionStore(projectDirectory);
        return await(reason, () -> store.readTestStatus()
                .filter(predicate)
                .orElse(null));
    }

    private static TestStatus waitForTestStatusAfter(Path projectDirectory, long updatedAfter,
                                                     Predicate<TestStatus> predicate, String reason)
            throws Exception {
        return waitForTestStatus(projectDirectory,
                                 status -> status.updatedAt() > updatedAfter && predicate.test(status),
                                 reason);
    }

    private static long currentAppPid(Path projectDirectory) throws Exception {
        return waitForSession(projectDirectory, session -> session.app().pid() != null, "app pid").app().pid();
    }

    private static JsonNode waitForImpact(Path projectDirectory, String selector, String handlerClass)
            throws Exception {
        Path impact = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                .resolve(DevSessionStore.TEST_IMPACT_FILE);
        return await("test impact " + selector, () -> {
            if (!Files.isRegularFile(impact)) {
                return null;
            }
            JsonNode test = objectMapper.readTree(impact.toFile()).path("tests").path(selector);
            for (JsonNode handler : test.path("handlers")) {
                if (handler.asText().startsWith(handlerClass + "#")) {
                    return test;
                }
            }
            return null;
        });
    }

    private static JsonNode waitForVersion(RawFluxzeroClient rawClient, String version) throws Exception {
        return await("app version " + version, () -> {
            JsonNode snapshot = rawClient.greetingState();
            return version.equals(snapshot.path("version").asText()) ? snapshot : null;
        });
    }

    private static JsonNode waitForGreeting(RawFluxzeroClient rawClient, String greeting) throws Exception {
        return await("greeting " + greeting, () -> {
            JsonNode snapshot = rawClient.greetingState();
            return containsGreeting(snapshot, greeting) ? snapshot : null;
        });
    }

    private static boolean containsGreeting(JsonNode snapshot, String greeting) {
        for (JsonNode node : snapshot.path("greetings")) {
            if (greeting.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String accessToken(String proxyUrl, String username) throws Exception {
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        String state = "e2e-state-" + UUID.randomUUID();
        String redirectUri = proxyUrl + "/app/callback";
        HttpResponse<String> authorize = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/oauth2/auth?"
                + form(Map.of(
                        "response_type", "code",
                        "client_id", ManagedIdpService.CLIENT_ID,
                        "redirect_uri", redirectUri,
                        "scope", ManagedIdpService.SCOPE,
                        "state", state,
                        "code_challenge", Pkce.challenge(verifier),
                        "code_challenge_method", "S256"))))
                .GET()
                .build());
        assertEquals(302, authorize.statusCode(), authorize.body());

        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/login"))
                .header("Cookie", cookie(authorize, "fz_local_stub_login_request"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", username))))
                .build());
        assertEquals(302, login.statusCode(), login.body());
        URI callback = URI.create(location(login));
        assertEquals(state, queryParam(callback, "state"));

        HttpResponse<String> token = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "grant_type", "authorization_code",
                        "code", queryParam(callback, "code"),
                        "redirect_uri", redirectUri,
                        "client_id", ManagedIdpService.CLIENT_ID,
                        "code_verifier", verifier))))
                .build());
        assertEquals(200, token.statusCode(), token.body());
        return objectMapper.readTree(token.body()).path("access_token").asText();
    }

    private static <T> T await(String reason, ThrowingSupplier<T> supplier) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                T value = supplier.get();
                if (value != null) {
                    return value;
                }
            } catch (Throwable e) {
                lastFailure = e;
            }
            Thread.sleep(100);
        }
        AssertionError error = new AssertionError("Timed out waiting for " + reason);
        if (lastFailure != null) {
            error.addSuppressed(lastFailure);
        }
        throw error;
    }

    private static void writeCommand(Path projectDirectory, String fileName, String type, String payload)
            throws IOException {
        Path command = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY).resolve(fileName);
        Files.createDirectories(command.getParent());
        Files.writeString(command, """
                {
                  "type": "%s",
                  "payload": %s
                }
                """.formatted(type, payload), UTF_8);
    }

    private static void writeYamlCommand(Path projectDirectory, String type, String name) throws IOException {
        Path config = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                version: 1
                commands:
                  live-greeting:
                    type: %s
                    payload:
                      name: %s
                """.formatted(type, name), UTF_8);
    }

    private static void writeVersion(Path projectDirectory, String version, boolean failOnStart) throws IOException {
        writeVersion(projectDirectory, version, failOnStart, true);
    }

    private static void writeVersion(Path projectDirectory, String version, boolean failOnStart,
                                     boolean connectToRuntime) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/AppVersion.java"), """
                package com.example.app;

                public final class AppVersion {
                    public static final String VALUE = "%s";
                    public static final boolean FAIL_ON_START = %s;
                    public static final boolean CONNECT_TO_RUNTIME = %s;

                    private AppVersion() {
                    }
                }
                """.formatted(version, failOnStart, connectToRuntime), UTF_8);
    }

    private static void writeBrokenVersion(Path projectDirectory) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/AppVersion.java"), """
                package com.example.app;

                public final class AppVersion {
                    public static final String VALUE = "broken"
                    public static final boolean FAIL_ON_START = false;
                    public static final boolean CONNECT_TO_RUNTIME = true;
                }
                """, UTF_8);
    }

    private static void writeGreetingHandlersTest(Path projectDirectory, String expectedGreeting)
            throws IOException {
        Files.writeString(projectDirectory.resolve(
                                  "src/test/java/com/example/app/GreetingHandlersTest.java"), """
                package com.example.app;

                import io.fluxzero.sdk.test.TestFixture;
                import org.junit.jupiter.api.Test;

                class GreetingHandlersTest {

                    @Test
                    void createsBaseGreeting() {
                        TestFixture.create(new GreetingHandlers("enabled"))
                                .whenCommand(new CreateGreeting("Test"))
                                .expectResult(new GreetingCreated("%s"));
                    }
                }
                """.formatted(expectedGreeting), UTF_8);
    }

    private static void writeBrokenGreetingHandlersTest(Path projectDirectory) throws IOException {
        Files.writeString(projectDirectory.resolve(
                                  "src/test/java/com/example/app/GreetingHandlersTest.java"), """
                package com.example.app;

                import io.fluxzero.sdk.test.TestFixture;
                import org.junit.jupiter.api.Test;

                class GreetingHandlersTest {

                    @Test
                    void createsBaseGreeting() {
                        TestFixture.create(new GreetingHandlers("enabled"))
                                .whenCommand(new CreateGreeting("Test"))
                                .expectResult(new GreetingCreated("base:Test:v1"))
                    }
                }
                """, UTF_8);
    }

    private static void writePrimaryFixtureHandler(Path projectDirectory) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/PrimaryFixtureHandler.java"), """
                package com.example.app;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class PrimaryFixtureHandler {

                    @HandleCommand
                    public PrimaryFixtureCreated handle(CreatePrimaryFixture command) {
                        String value = FixtureFormatting.format("primary", command.name());
                        return new PrimaryFixtureCreated(value);
                    }
                }
                """, UTF_8);
    }

    private static void writeSecondaryFixtureHandler(Path projectDirectory) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/SecondaryFixtureHandler.java"), """
                package com.example.app;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class SecondaryFixtureHandler {

                    @HandleCommand
                    public SecondaryFixtureCreated handle(CreateSecondaryFixture command) {
                        String value = FixtureFormatting.format("secondary", command.name());
                        return new SecondaryFixtureCreated(value);
                    }
                }
                """, UTF_8);
    }

    private static void writeFixtureFormatting(Path projectDirectory) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/FixtureFormatting.java"), """
                package com.example.app;

                public final class FixtureFormatting {
                    private FixtureFormatting() {
                    }

                    public static String format(String prefix, String name) {
                        String separator = ":";
                        return prefix + separator + name + separator + AppVersion.VALUE;
                    }
                }
                """, UTF_8);
    }

    private static void writeTemporaryFixture(Path projectDirectory) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/CreateTemporaryFixture.java"), """
                package com.example.app;

                public record CreateTemporaryFixture(String name) {
                }
                """, UTF_8);
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/TemporaryFixtureCreated.java"), """
                package com.example.app;

                public record TemporaryFixtureCreated(String value) {
                }
                """, UTF_8);
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/TemporaryFixtureHandler.java"), """
                package com.example.app;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;

                public class TemporaryFixtureHandler {

                    @HandleCommand
                    public TemporaryFixtureCreated handle(CreateTemporaryFixture command) {
                        return new TemporaryFixtureCreated("temporary:" + command.name() + ":" + AppVersion.VALUE);
                    }
                }
                """, UTF_8);
        Files.writeString(projectDirectory.resolve(
                                  "src/test/java/com/example/app/TemporaryFixtureHandlerTest.java"), """
                package com.example.app;

                import io.fluxzero.sdk.test.TestFixture;
                import org.junit.jupiter.api.Test;

                class TemporaryFixtureHandlerTest {

                    @Test
                    void handlesTemporaryFixture() {
                        TestFixture.create(new TemporaryFixtureHandler())
                                .whenCommand(new CreateTemporaryFixture("Test"))
                                .expectResult(new TemporaryFixtureCreated("temporary:Test:v1"));
                    }
                }
                """, UTF_8);
    }

    private static void writeHandlers(Path projectDirectory, boolean optionalHandler, boolean extraHandler)
            throws IOException {
        StringBuilder handlers = new StringBuilder("""
                package com.example.app;

                import io.fluxzero.sdk.tracking.handling.HandleCommand;
                import io.fluxzero.sdk.tracking.handling.HandleQuery;

                public class GreetingHandlers {
                    private final String devMode;

                    public GreetingHandlers(String devMode) {
                        this.devMode = devMode;
                    }

                    @HandleCommand
                    public GreetingCreated handle(CreateGreeting command) {
                        String value = "base:" + command.name() + ":" + AppVersion.VALUE;
                        GreetingState.add(value);
                        return new GreetingCreated(value);
                    }
                """);
        if (optionalHandler) {
            handlers.append("""

                    @HandleCommand
                    public GreetingCreated handle(CreateOptionalGreeting command) {
                        String value = "optional:" + command.name() + ":" + AppVersion.VALUE;
                        GreetingState.add(value);
                        return new GreetingCreated(value);
                    }
                    """);
        }
        if (extraHandler) {
            handlers.append("""

                    @HandleCommand
                    public GreetingCreated handle(CreateExtraGreeting command) {
                        String value = "extra:" + command.name() + ":" + AppVersion.VALUE;
                        GreetingState.add(value);
                        return new GreetingCreated(value);
                    }
                    """);
        }
        handlers.append("""

                    @HandleQuery
                    public GreetingSnapshot handle(GetGreetingState query) {
                        return new GreetingSnapshot(AppVersion.VALUE, System.getenv("ENVIRONMENT"), devMode,
                                                    GreetingState.entries());
                    }
                }
                """);
        Files.writeString(projectDirectory.resolve("src/main/java/com/example/app/GreetingHandlers.java"),
                          handlers.toString(), UTF_8);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Set-Cookie " + name));
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("location")
                .orElseThrow(() -> new AssertionError("Missing Location header"));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    private static String queryParam(URI uri, String name) {
        for (String parameter : uri.getRawQuery().split("&")) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            if (name.equals(URLDecoder.decode(key, UTF_8))) {
                String value = separator < 0 ? "" : parameter.substring(separator + 1);
                return URLDecoder.decode(value, UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter " + name + " in " + uri);
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class RawFluxzeroClient implements AutoCloseable {
        private final WebSocketClient client;
        private final GatewayClient queryGateway;
        private final RequestHandler requestHandler;

        private RawFluxzeroClient(String runtimeBaseUrl) {
            client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                 .runtimeBaseUrl(runtimeBaseUrl)
                                                 .name("plain-e2e-client")
                                                 .id("plain-e2e-client-" + UUID.randomUUID())
                                                 .build());
            queryGateway = client.getGatewayClient(MessageType.QUERY);
            requestHandler = new DefaultRequestHandler(client, MessageType.RESULT, Duration.ofSeconds(10),
                                                       "plain-e2e-query-results");
        }

        private JsonNode greetingState() throws Exception {
            SerializedMessage response = requestHandler.sendRequest(
                    message("com.example.app.GetGreetingState", "{}"),
                    message -> queryGateway.append(Guarantee.SENT, message),
                    Duration.ofSeconds(10)).get();
            return objectMapper.readTree(response.data().getValue());
        }

        private SerializedMessage message(String type, String payload) throws IOException {
            return new SerializedMessage(
                    new Data<>(payload.getBytes(UTF_8), type, 0, Data.JSON_FORMAT),
                    Metadata.empty(),
                    "e2e:" + UUID.randomUUID(),
                    Instant.now().toEpochMilli());
        }

        @Override
        public void close() {
            requestHandler.close();
            queryGateway.close();
            client.shutDown();
        }
    }
}
