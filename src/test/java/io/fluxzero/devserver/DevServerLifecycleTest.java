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
import io.fluxzero.devserver.fixture.FixtureAppMain;
import io.fluxzero.idp.client.Pkce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerLifecycleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serviceExitDuringStartupDoesNotDeadlock(@TempDir Path project) throws Exception {
        Path control = project.resolve("service-control");
        Files.createDirectories(control);
        Path devConfig = project.resolve(DevProjectConfig.FILE);
        Files.createDirectories(devConfig.getParent());
        String command = shellQuote(javaExecutable()) + " -cp " + shellQuote(testClassesDirectory().toString())
                         + " " + DevServiceFixtureServer.class.getName() + " exit-gate "
                         + shellQuote(control.toString());
        Files.writeString(devConfig, "version: 1\nidp: external\nservices:\n  gate:\n    command: '"
                                     + command.replace("'", "''")
                                     + "'\n    readiness:\n      log: NEVER_READY\n      timeout: PT5S\n");
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", project.toString(), "--no-watch", "--no-compile-on-start", "--no-tests",
                "--no-frontend", "--idp", "external", "--port", "0"});
        DevServer server = new DevServer(config);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = Thread.ofPlatform().name("service-exit-startup-test").start(() -> {
            try {
                server.start();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        try {
            assertTrue(await(() -> Files.isRegularFile(control.resolve("started"))));
            assertTrue(await(() -> Arrays.stream(starter.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("awaitReadiness"))));
            Files.writeString(control.resolve("exit"), "exit");
            starter.join(Duration.ofSeconds(5));
            assertFalse(starter.isAlive(), "service failure left startup deadlocked");
            assertTrue(failure.get() instanceof DevServerStartupException, () -> "Unexpected failure: " + failure.get());
        } finally {
            server.close();
            if (starter.isAlive()) {
                starter.interrupt();
            }
        }
    }

    @Test
    void exclusiveVerificationPreservesSessionAndReturnsCommandFailure(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--idp", "external", "--no-watch", "--no-compile-on-start"});
        try (DevServer server = new DevServer(config).start()) {
            String id = server.session().sessionId();
            long runtime = server.session().runtime().pid();
            String java = Path.of(System.getProperty("java.home"), "bin", ProcessUtils.isWindows() ? "java.exe" : "java").toString();
            assertEquals(7, DevVerificationMain.run(project, List.of(java, "-cp", System.getProperty("java.class.path"),
                    VerificationFailure.class.getName()), Duration.ofSeconds(10)));
            assertEquals(id, server.session().sessionId());
            assertEquals(runtime, server.session().runtime().pid());
            assertTrue(ProcessHandle.of(runtime).orElseThrow().isAlive());
            Path childPid = project.resolve("verification-child.pid");
            var timeoutFailure = assertThrows(IllegalStateException.class, () -> DevVerificationMain.run(project,
                    List.of(java, "-cp", System.getProperty("java.class.path"), VerificationWait.class.getName(),
                            childPid.toString()), Duration.ofSeconds(2)));
            assertTrue(timeoutFailure.getMessage().contains("timed out"));
            assertFalse(ProcessUtils.isAlive(Long.parseLong(Files.readString(childPid))));
            assertEquals(id, server.session().sessionId());
            assertEquals(runtime, server.session().runtime().pid());
            assertEquals(0, DevVerificationMain.run(project, List.of(java, "-version"), Duration.ofSeconds(10)));
        }
    }

    public static class VerificationWait {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
        }
    }

    public static class VerificationFailure {
        public static void main(String[] args) { System.exit(7); }
    }

    @Test
    void consoleUpdatesCountsFromReportsDuringRun(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--idp", "external", "--no-watch", "--no-compile-on-start"});
        new DevSessionStore(project).writeTestStatus(TestStatus.completed(List.of(), "baseline", 0, "done").withCounts(new TestCounts(4, 0, 0)));
        try (DevServer server = new DevServer(config).start(); HttpClient http = HttpClient.newHttpClient()) {
            var update = DevServer.class.getDeclaredMethod("updateTestStatus", String.class, TestStatus.class);
            update.setAccessible(true);
            update.invoke(server, config.projects().getFirst().id(), TestStatus.running(List.of(), "progress check"));
            String base = server.session().gateway().url();
            var initial = awaitConsoleStatus(http, base, status -> status.path("testResults").path("running").asBoolean())
                    .path("testResults");
            assertTrue(initial.path("running").asBoolean());
            assertEquals(0, initial.path("total").asInt());
            assertEquals(4, initial.path("expectedTotal").asInt());
            Path reports = Files.createDirectories(project.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-first.xml"), "<testsuite><testcase name='a'/></testsuite>");
            var partial = awaitConsoleStatus(http, base, status -> status.path("testResults").path("passed").asInt() == 1)
                    .path("testResults");
            assertEquals(1, partial.path("passed").asInt());
            assertEquals(4, partial.path("expectedTotal").asInt());
            Files.writeString(reports.resolve("TEST-second.xml"), "<testsuite><testcase name='b'><failure/></testcase></testsuite>");
            var later = awaitConsoleStatus(http, base, status -> status.path("testResults").path("total").asInt() == 2)
                    .path("testResults");
            assertEquals(2, later.path("total").asInt());
            assertEquals(1, later.path("failed").asInt());
            assertTrue(later.path("running").asBoolean());
        }
    }

    @Test
    void uncertainDynamicInventoryKeepsCatalogCountsDuringSelectiveRun(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--idp", "external",
                "--no-watch", "--no-compile-on-start"});
        var inventory = new TestInventory(new DevSessionStore(project).directory());
        inventory.discover("module", java.util.Set.of("demo.A#passed", "demo.A#failed"),
                java.util.Set.of("[engine:junit-jupiter]/[class:demo.B]/[test-template:newCase(int)]"));
        inventory.event("module", "passed", "demo.A#passed");
        inventory.event("module", "failed", "demo.A#failed");
        inventory.save();
        assertFalse(inventory.snapshot().known());
        try (DevServer server = new DevServer(config).start(); HttpClient http = HttpClient.newHttpClient()) {
            var update = DevServer.class.getDeclaredMethod("updateTestStatus", String.class, TestStatus.class);
            update.setAccessible(true);
            update.invoke(server, config.projects().getFirst().id(), TestStatus.running(List.of("demo.B"), "selective run"));
            String base = server.session().gateway().url();
            var results = awaitConsoleStatus(http, base, status -> status.path("testResults").path("running").asBoolean())
                    .path("testResults");
            assertFalse(results.path("totalKnown").asBoolean());
            assertEquals(2, results.path("total").asInt());
            assertEquals(2, results.path("expectedTotal").asInt());
            assertEquals(1, results.path("passed").asInt());
            assertEquals(1, results.path("failed").asInt());
            update.invoke(server, config.projects().getFirst().id(),
                    TestStatus.completed(List.of("demo.B"), "selective run", 0, "done").withCounts(new TestCounts(1, 0, 0)));
            results = awaitConsoleStatus(http, base, status -> !status.path("testResults").path("running").asBoolean())
                    .path("testResults");
            assertEquals(1, results.path("passed").asInt());
            assertEquals(1, results.path("failed").asInt());
            assertEquals("failed", results.path("state").asText());
        }
    }

    @Test
    void restoredIncompleteTestResultIsReplacedByANewCompletedRun(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--idp", "external",
                "--no-watch", "--no-compile-on-start"});
        new DevSessionStore(project).writeTestStatus(TestStatus.incomplete(List.of(), "previous build failed", Map.of(),
                1, "packaging failed", 100).withCounts(new TestCounts(2, 0, 0)));
        try (DevServer server = new DevServer(config).start(); HttpClient http = HttpClient.newHttpClient()) {
            String base = server.session().gateway().url();
            var restored = awaitConsoleStatus(http, base, status -> status.path("testResults").path("available").asBoolean())
                    .path("testResults");
            assertEquals("incomplete", restored.path("state").asText());
            assertTrue(restored.path("incomplete").asBoolean());
            assertFalse(restored.path("running").asBoolean());
            assertEquals(2, restored.path("passed").asInt());
            assertEquals(2, restored.path("total").asInt());

            var update = DevServer.class.getDeclaredMethod("updateTestStatus", String.class, TestStatus.class);
            update.setAccessible(true);
            update.invoke(server, config.projects().getFirst().id(),
                    TestStatus.completed(List.of(), "new run", 0, "done").withCounts(new TestCounts(3, 0, 0)));
            var completed = awaitConsoleStatus(http, base, status -> status.path("testResults").path("total").asInt() == 3)
                    .path("testResults");
            assertEquals("passed", completed.path("state").asText());
            assertFalse(completed.path("incomplete").asBoolean());
            assertFalse(completed.path("running").asBoolean());
            assertEquals(3, completed.path("passed").asInt());
        }
    }

    @Test
    void truncatesDataWithoutReplacingRuntimeOrProxy(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--idp", "external", "--no-watch", "--no-compile-on-start"});
        new DevSessionStore(project).writeTestStatus(TestStatus.incomplete(List.of(), "build failed after tests", Map.of(),
                1, "packaging failed", 100).withCounts(new TestCounts(2, 0, 0)));
        try (DevServer server = new DevServer(config).start(); HttpClient http = HttpClient.newHttpClient()) {
            DevSession original = server.session();
            String base = original.gateway().url();
            var request = HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "actions/truncate-testserver-data"))
                    .header("Origin", base).header("X-Fluxzero-Console", "1").POST(HttpRequest.BodyPublishers.noBody()).build();
            var before = awaitConsoleStatus(http, base, status -> status.path("testResults").path("available").asBoolean());
            assertEquals("incomplete", before.path("testResults").path("state").asText());
            assertEquals(2, before.path("testResults").path("total").asInt());
            if (!before.path("maintenance").path("resetSupported").asBoolean()) {
                assertEquals(409, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                return; // Older selected SDKs remain supported, with the optional reset capability disabled.
            }
            assertEquals(202, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            JsonNode status;
            do {
                status = objectMapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "status.json")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
                if (!status.path("maintenance").path("busy").asBoolean()) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertFalse(status.path("maintenance").path("busy").asBoolean());
            assertEquals("", status.path("maintenance").path("error").asText());
            assertEquals(original.runtime().pid(), server.session().runtime().pid());
            assertTrue(ProcessUtils.isAlive(original.runtime().pid()));
            assertEquals(original.runtime().port(), server.session().runtime().port());
            assertEquals(original.proxy().port(), server.session().proxy().port());
            assertEquals(original.gateway().url(), server.session().gateway().url());
            assertEquals(200, healthStatus(server.session().proxy().url()));
            assertTrue(status.path("components").get(0).path("memoryBytes").asLong() > 0);
        }
    }

    @Test
    void startsAgentControlPlaneAndDefersCompilationInEmptyGreenfieldWorkspace(@TempDir Path projectDirectory)
            throws Exception {
        DevServerConfig config = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString(), "--idp", "external"});

        try (DevServer devServer = new DevServer(config).start()) {
            DevSession session = devServer.session();
            assertEquals("running", session.status());
            assertEquals("running", session.mcp().state());
            assertEquals("waiting-for-project", session.runtime().state());
            assertEquals("waiting-for-project", session.proxy().state());
            assertEquals("waiting-for-project", session.idp().state());
            assertEquals("waiting-for-project", session.compile().state());
            assertTrue(session.compile().detail().contains("create the project in that exact root"));
            assertNull(session.runtime().url());
            assertNull(session.proxy().url());
            assertEquals(projectDirectory.toAbsolutePath().normalize().toString(), session.projectDirectory());
            assertTrue(Files.isRegularFile(projectDirectory.resolve(".fluxzero/dev/session.json")));
            assertFalse(DevProjectLayout.isBuildProject(projectDirectory));
        }
    }

    @Test
    void activatesGeneratedProjectWithReloadedConfigurationAndPreservesAgentSession(@TempDir Path projectDirectory)
            throws Exception {
        String[] initialArguments = {"--project-dir", projectDirectory.toString(), "--idp", "managed"};
        String[] reloadedArguments = {
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start"
        };
        AtomicInteger reloads = new AtomicInteger();
        DevServerConfig initial = DevServerConfig.fromArgs(initialArguments);

        try (DevServer devServer = new DevServer(initial, () -> {
            reloads.incrementAndGet();
            return DevServerConfig.fromArgs(reloadedArguments);
        }).start()) {
            DevSession bootstrap = devServer.session();
            AgentCursor cursor = devServer.agentQueryService().getStatus().cursor();

            Path projectConfig = projectDirectory.resolve(DevProjectConfig.FILE);
            Files.createDirectories(projectConfig.getParent());
            Files.writeString(projectConfig, "version: 1\nidp: external\n");
            Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>");

            assertTrue(await(() -> "running".equals(devServer.session().runtime().state())
                                   && "external".equals(devServer.session().idp().state())),
                       () -> "Generated project did not activate: " + devServer.session());
            DevSession activated = devServer.session();
            assertEquals(1, reloads.get());
            assertEquals(bootstrap.sessionId(), activated.sessionId());
            assertEquals(bootstrap.mcp().url(), activated.mcp().url());
            assertEquals(cursor.sessionId(), devServer.agentQueryService().getStatus().cursor().sessionId());
            assertEquals("running", activated.runtime().state());
            assertEquals("running", activated.proxy().state());
            assertEquals("external", activated.idp().state());
            assertEquals("stopped", activated.compile().state());
            assertEquals(200, healthStatus(activated.proxy().url()));
            AgentChange activation = devServer.agentQueryService()
                    .waitForChange(cursor, AgentSelector.all(), Duration.ZERO, 200);
            assertFalse(activation.sessionChanged());
            assertTrue(activation.events().stream().anyMatch(event ->
                    event.message().contains("build project detected")));
        }
    }

    @Test
    void keepsMcpAvailableAndRetriesAfterGeneratedConfigurationIsCorrected(@TempDir Path projectDirectory)
            throws Exception {
        String[] initialArguments = {"--project-dir", projectDirectory.toString()};
        String[] reloadedArguments = {
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start"
        };
        DevServerConfig initial = DevServerConfig.fromArgs(initialArguments);
        AgentSelector projectProblems = new AgentSelector(
                Set.of("project"), Set.of(), Set.of("project"), null);

        try (DevServer devServer = new DevServer(
                initial, () -> DevServerConfig.fromArgs(reloadedArguments)).start()) {
            String sessionId = devServer.session().sessionId();
            Path projectConfig = projectDirectory.resolve(DevProjectConfig.FILE);
            Files.createDirectories(projectConfig.getParent());
            Files.writeString(projectConfig, "version: 99\n");
            Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>");

            assertTrue(await(() -> "failed".equals(devServer.session().compile().state())),
                       () -> "Invalid generated config was not reported: " + devServer.session());
            assertEquals("running", devServer.session().status());
            assertEquals("running", devServer.session().mcp().state());
            assertEquals("waiting-for-project", devServer.session().runtime().state());
            assertFalse(devServer.agentQueryService().getActiveProblems(projectProblems, 20).problems().isEmpty());

            Files.writeString(projectConfig, "version: 1\nidp: external\n");

            assertTrue(await(() -> "running".equals(devServer.session().runtime().state())
                                   && "external".equals(devServer.session().idp().state())),
                       () -> "Corrected generated config did not activate: " + devServer.session());
            assertEquals(sessionId, devServer.session().sessionId());
            assertEquals("external", devServer.session().idp().state());
            assertTrue(await(() -> devServer.agentQueryService()
                                       .getActiveProblems(projectProblems, 20).problems().isEmpty()),
                         () -> "Problems remained after recovery: "
                               + devServer.agentQueryService().getActiveProblems(projectProblems, 20));
        }
    }

    @Test
    void startsVersionAlignedRuntimeAndProxyOnDynamicPorts(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        DevSession session;
        Path sessionFile;
        Path mcpTokenFile;
        try (DevServer devServer = new DevServer(config).start()) {
            session = devServer.session();
            sessionFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                    .resolve(DevSessionStore.SESSION_FILE);
            mcpTokenFile = Path.of(session.mcp().metadata().get("tokenFile"));

            assertEquals("running", session.status());
            assertEquals("running", session.runtime().state());
            assertEquals("running", session.proxy().state());
            assertEquals("running", session.idp().state());
            assertEquals("running", session.mcp().state());
            assertTrue(session.runtime().port() > 0);
            assertTrue(session.proxy().port() > 0);
            assertNotNull(session.runtime().pid());
            assertEquals(session.runtime().pid(), session.proxy().pid());
            assertNotEquals(ProcessHandle.current().pid(), session.runtime().pid());
            assertEquals(DevServerVersion.sdkVersion(), session.runtime().metadata().get("sdkVersion"));
            assertEquals("isolated", session.runtime().metadata().get("mode"));
            assertTrue(Set.of("hit", "miss").contains(session.runtime().metadata().get("artifactCache")));
            assertEquals("fallback", session.runtime().metadata().get("versionDetection"));
            assertEquals("project", session.runtime().metadata().get("fallbackProjects"));
            assertEquals(DevServerVersion.sdkVersion(), session.runtime().metadata().get("runtimeSdkVersion"));
            assertEquals("unverified", session.runtime().metadata().get("runtimeCompatibility"));
            assertEquals("fallback", session.runtime().metadata().get("project.project.sdkVersionSource"));
            assertTrue(session.mcp().port() > 0);
            assertNotEquals(session.runtime().port(), session.mcp().port());
            assertNotEquals(session.proxy().port(), session.mcp().port());
            assertEquals("ws://localhost:" + session.runtime().port(), session.runtime().url());
            assertEquals("http://localhost:" + session.proxy().port(), session.proxy().url());
            assertEquals("http://127.0.0.1:" + session.mcp().port() + DevMcpServer.ENDPOINT, session.mcp().url());
            assertEquals(session.gateway().url() + DevGateway.BACKEND_PREFIX, session.idp().url());
            assertEquals("streamable-http", session.mcp().metadata().get("transport"));
            assertTrue(Files.isRegularFile(mcpTokenFile));
            assertEquals(200, healthStatus(session.proxy().url()));

            JsonNode json = objectMapper.readTree(sessionFile.toFile());
            assertEquals(session.proxy().url(), json.path("proxy").path("url").asText());
            assertEquals(session.idp().url(), json.path("idp").path("url").asText());
            assertEquals(session.mcp().url(), json.path("mcp").path("url").asText());
            assertEquals(mcpTokenFile.toString(), json.path("mcp").path("metadata").path("tokenFile").asText());
            assertTrue(json.path("sessionId").isTextual());
            assertEquals("development", json.path("devServerVersion").asText());
            assertTrue(json.path("heartbeatAt").asLong() > 0);
        }

        JsonNode stoppedJson = objectMapper.readTree(sessionFile.toFile());
        assertEquals("stopped", stoppedJson.path("status").asText());
        assertEquals("stopped", stoppedJson.path("runtime").path("state").asText());
        assertEquals("stopped", stoppedJson.path("proxy").path("state").asText());
        assertEquals("stopped", stoppedJson.path("idp").path("state").asText());
        assertEquals("stopped", stoppedJson.path("mcp").path("state").asText());
        assertEquals("dev server stopped", stoppedJson.path("runtime").path("detail").asText());
        assertEquals(session.runtime().port(), stoppedJson.path("runtime").path("port").asInt());
        assertFalse(Files.exists(mcpTokenFile));
    }

    @Test
    void requestsEnvironmentShutdownWhenVersionAlignedRuntimeExits(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "runtime-exit-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), List.of(), false, "local", List.of(), 0, IdpMode.EXTERNAL);

        try (DevServer devServer = new DevServer(config).start()) {
            long runtimePid = devServer.session().runtime().pid();
            assertNotEquals(ProcessHandle.current().pid(), runtimePid);
            assertTrue(ProcessHandle.of(runtimePid).orElseThrow().destroyForcibly());

            String reason = devServer.shutdownRequested().get(5, TimeUnit.SECONDS);
            assertTrue(reason.contains("Test Server stopped unexpectedly"), reason);
            assertTrue(await(() -> "failed".equals(devServer.session().runtime().state())));
            assertEquals("failed", devServer.session().proxy().state());
        }
    }

    @Test
    void usesConfiguredPublicPortForBackendOnlyEnvironment(@TempDir Path projectDirectory) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        DevServerConfig config = backendOnlyConfig(projectDirectory, port);

        try (DevServer devServer = new DevServer(config).start()) {
            DevSession session = devServer.session();
            assertEquals(port, session.gateway().port());
            assertEquals("http://localhost:" + port, session.gateway().url());
            assertEquals("running", session.gateway().state());
            assertEquals(200, healthStatus(session.proxy().url()));
        }
    }

    @Test
    void usesDynamicProxyPortAfterBackendOnlyPortConflict(@TempDir Path projectDirectory) throws Exception {
        try (ServerSocket occupiedPort = new ServerSocket()) {
            occupiedPort.setReuseAddress(false);
            occupiedPort.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int configuredPort = occupiedPort.getLocalPort();

            try (DevServer devServer = new DevServer(backendOnlyConfig(projectDirectory, configuredPort), ignored -> true)
                    .start()) {
                DevSession session = devServer.session();
                assertNotEquals(configuredPort, session.proxy().port());
                assertTrue(session.proxy().port() > 0);
                assertEquals("http://localhost:" + session.proxy().port(), session.proxy().url());
                assertEquals("running", session.gateway().state());
                assertEquals(200, healthStatus(session.proxy().url()));
            }
        }
    }

    @Test
    void externalIdpModeSkipsManagedIdp(@TempDir Path projectDirectory) {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "external-idp-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), List.of(), false, "local", List.of(), 0, IdpMode.EXTERNAL);

        try (DevServer devServer = new DevServer(config).start()) {
            assertEquals("running", devServer.session().runtime().state());
            assertEquals("running", devServer.session().proxy().state());
            assertEquals("external", devServer.session().idp().state());
            assertTrue(devServer.session().idp().detail().contains("application configuration applies"));
        }
    }

    private static DevServerConfig backendOnlyConfig(Path projectDirectory, int port) {
        return new DevServerConfig(
                projectDirectory, null, "fixed-backend-port", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), List.of(), false, "local", List.of(), port, IdpMode.EXTERNAL);
    }

    @Test
    void rejectsSecondDevServerForSameProjectWithoutReplacingDiagnostics(@TempDir Path projectDirectory)
            throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        try (DevServer first = new DevServer(config).start()) {
            Path diagnostics = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                    .resolve(DevLogStore.DIAGNOSTICS_FILE);
            String owningSession = objectMapper.readTree(diagnostics.toFile()).path("sessionId").asText();

            assertThrows(IllegalStateException.class, () -> new DevServer(config).start());
            assertEquals(first.session().sessionId(), owningSession);
            assertEquals(owningSession,
                         objectMapper.readTree(diagnostics.toFile()).path("sessionId").asText());
        }
    }

    @Test
    void rejectsActiveSessionWhenPreviousDevServerPidStillLives(@TempDir Path projectDirectory) {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        store.writeSession(DevSession.empty(config).withStatus("running"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                       () -> new DevServer(config).start());

        assertTrue(exception.getMessage().contains("already active"));
    }

    @Test
    void cleansUpOwnedOrphanAppFromStaleSession(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        DevSession stale = DevSession.empty(config).withStatus("running");
        String ownershipMarker = stale.sessionId();
        Process orphan = ProcessUtils.start(List.of(
                javaExecutable(),
                "-Dfluxzero.dev.session=" + ownershipMarker,
                "-cp", testClassesDirectory().toString(),
                FixtureAppMain.class.getName()),
                                            projectDirectory, Map.of(), ignored -> {
                });
        try {
            assertTrue(orphan.isAlive());
            long processStartedAt = ProcessUtils.startedAt(orphan).orElseThrow();
            stale = withPid(stale.withApp(DevSession.ServiceStatus.running(
                    "app", null, null, orphan.pid(), "running build 7").withMetadata(
                    Map.of(ProcessUtils.PROCESS_STARTED_AT, Long.toString(processStartedAt)))), unusedPid());
            new DevSessionStore(projectDirectory).writeSession(stale);

            try (DevServer devServer = new DevServer(config).start()) {
                assertTrue(awaitStopped(orphan), () -> {
                    ProcessHandle.Info info = orphan.info();
                    return "Failed to stop owned orphan with marker " + ownershipMarker
                           + "; commandLine=" + info.commandLine().orElse("<unavailable>")
                           + "; arguments=" + info.arguments().map(java.util.Arrays::toString).orElse("<unavailable>")
                           + "; expectedStartedAt=" + processStartedAt
                           + "; actualStartedAt=" + info.startInstant().orElse(null);
                });
                assertEquals("running", devServer.session().status());
            }
        } finally {
            if (orphan.isAlive()) {
                orphan.destroyForcibly();
            }
        }
    }

    @Test
    void managedIdpCompletesAuthorizationCodeFlowThroughProxy(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        try (DevServer devServer = new DevServer(config).start()) {
            String proxyUrl = devServer.session().gateway().url() + DevGateway.BACKEND_PREFIX;
            JsonNode discovery = awaitJson(proxyUrl + "/.well-known/openid-configuration");
            assertEquals(proxyUrl, discovery.path("issuer").asText());
            assertEquals(proxyUrl + "/oauth2/token", discovery.path("token_endpoint").asText());
            assertTrue(awaitJson(proxyUrl + "/.well-known/jwks.json").path("keys").isArray());

            String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
            String state = "dev-state";
            String redirectUri = devServer.session().gateway().url() + "/app/callback";
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
            String loginRequestCookie = cookie(authorize, "fz_local_stub_login_request");

            HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/login"))
                    .header("Cookie", loginRequestCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", "rene@example.com"))))
                    .build());
            assertEquals(302, login.statusCode(), login.body());
            URI callback = URI.create(location(login));
            assertEquals("/app/callback", callback.getPath());
            assertEquals(URI.create(redirectUri).getAuthority(), callback.getAuthority());
            assertEquals(state, queryParam(callback, "state"));
            String code = queryParam(callback, "code");

            HttpResponse<String> token = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                            "grant_type", "authorization_code",
                            "code", code,
                            "redirect_uri", redirectUri,
                            "client_id", ManagedIdpService.CLIENT_ID,
                            "code_verifier", verifier))))
                    .build());
            assertEquals(200, token.statusCode(), token.body());
            String accessToken = objectMapper.readTree(token.body()).path("access_token").asText();

            HttpResponse<String> userInfo = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build());
            assertEquals(200, userInfo.statusCode(), userInfo.body());
            JsonNode identity = objectMapper.readTree(userInfo.body());
            assertEquals("rene@example.com", identity.path("sub").asText());
            assertEquals("rene@example.com", identity.path("email").asText());
            assertEquals("local-auth", identity.path("tenant_id").asText());
        }
    }

    private static int healthStatus(String proxyUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxyUrl + "/proxy/health")).GET().build();
        return send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static DevSession withPid(DevSession session, long pid) {
        return new DevSession(session.sessionId(), pid, session.devServerVersion(), session.projectDirectory(),
                              session.observability(),
                              session.status(),
                              session.runtime(), session.proxy(), session.gateway(), session.idp(), session.app(),
                              session.reload(), session.compile(),
                              session.tests(), session.commands(), session.frontend(), session.mcp(),
                              session.startedAt(), session.heartbeatAt(), session.updatedAt());
    }

    private static long unusedPid() {
        long pid = ProcessHandle.current().pid() + 10_000;
        while (ProcessUtils.isAlive(pid)) {
            pid++;
        }
        return pid;
    }

    private static boolean awaitStopped(Process process) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return !process.isAlive();
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                       System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private static Path testClassesDirectory() throws Exception {
        return Path.of(DevServerLifecycleTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String shellQuote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    private JsonNode awaitConsoleStatus(HttpClient http, String base, Predicate<JsonNode> condition) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "status.json"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonNode lastStatus;
        do {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            lastStatus = objectMapper.readTree(response.body());
            if (condition.test(lastStatus)) return lastStatus;
            // The console serves the shared asynchronous projection used by its push channel.
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Console status did not reach the expected state: " + lastStatus.path("testResults"));
    }

    private JsonNode awaitJson(String url) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url)).GET().build());
                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                }
            } catch (Exception e) {
                lastFailure = e;
            }
            Thread.sleep(50);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("No successful JSON response from " + url);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(request, bodyHandler);
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryParam(URI uri, String name) {
        for (String parameter : uri.getRawQuery().split("&")) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                String value = separator < 0 ? "" : parameter.substring(separator + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter " + name + " in " + uri);
    }
}
