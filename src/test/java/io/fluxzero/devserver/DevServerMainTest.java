/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerMainTest {

    @Test
    void reportsDevelopmentVersionFromClasses() {
        assertEquals("Fluxzero Dev Server development", DevServerMain.versionLine());
    }

    @Test
    void explainsIncompatibleResolvedArtifacts() {
        assertEquals(
                "required class io.fluxzero.testserver.metrics.TestServerMetricsMonitor is missing from the resolved "
                + "dependencies. Reinstall matching Fluxzero dev-server artifacts.",
                DevServerMain.startupFailureMessage(new NoClassDefFoundError(
                        "io/fluxzero/testserver/metrics/TestServerMetricsMonitor")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void reportsStoppedAfterSignalCleanup(@TempDir Path projectDirectory) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path outputFile = projectDirectory.resolve("dev-server.out");
        Process process = new ProcessBuilder(
                java.toString(), runtimeCacheArgument(),
                "-Dfluxzero.dev.project=" + projectDirectory.toAbsolutePath().normalize(),
                "-D" + DevEnvironmentRegistry.DIRECTORY_PROPERTY + "=" + projectDirectory.resolve("registry"),
                "-cp", testClassPath(),
                DevServerMain.class.getName(),
                "--project-dir", projectDirectory.toString(),
                "--no-watch", "--no-compile-on-start", "--no-tests", "--idp", "external")
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            Path sessionFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                    .resolve(DevSessionStore.SESSION_FILE);
            assertTrue(awaitRunningSession(sessionFile), "dev server did not become ready");

            Process signal = new ProcessBuilder("kill", "-TERM", Long.toString(process.pid())).start();
            assertTrue(signal.waitFor(2, TimeUnit.SECONDS) && signal.exitValue() == 0,
                       "failed to signal dev server");

            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "dev server did not stop after one signal");
            String output = Files.readString(outputFile);
            int stopping = output.indexOf(DevServerMain.STOPPING_MESSAGE);
            int stopped = output.indexOf(DevServerMain.STOPPED_MESSAGE);
            assertTrue(stopping >= 0 && stopped > stopping, output);
            assertTrue(output.stripTrailing().endsWith(DevServerMain.STOPPED_MESSAGE), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void stopsWorkspaceAndStartsAgainThroughItsRetainedDashboard(@TempDir Path projectDirectory) throws Exception {
        Process process = startServer(projectDirectory);
        ObjectMapper mapper = new ObjectMapper();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            assertTrue(awaitRunningSession(sessionFile(projectDirectory)));
            var before = mapper.readTree(sessionFile(projectDirectory).toFile());
            String base = before.path("gateway").path("url").asText();
            long runtimePid = before.path("runtime").path("pid").asLong();
            Path history = seedMonitoringHistory(projectDirectory);
            assertEquals(409, dashboardAction(http, base, "start-workspace"));
            assertEquals(202, dashboardAction(http, base, "stop-workspace"));
            var stopped = awaitDashboardState(http, base, "idle");
            assertTrue(stopped.path("maintenance").path("workspaceStopped").asBoolean());
            assertFalse(stopped.path("maintenance").path("busy").asBoolean());
            assertFalse(ProcessHandle.of(runtimePid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(process.isAlive(), "dashboard controller should remain alive");
            assertEquals(200, http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + DevConsole.ROOT)).build(),
                                       java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("idle", mapper.readTree(sessionFile(projectDirectory).toFile()).path("status").asText());
            assertEquals(409, dashboardAction(http, base, "stop-workspace"));
            assertEquals(409, dashboardAction(http, base, "restart-application"));
            assertEquals(202, dashboardAction(http, base, "start-workspace"));
            awaitDashboardState(http, base, "running");
            var after = mapper.readTree(sessionFile(projectDirectory).toFile());
            assertFalse(before.path("sessionId").equals(after.path("sessionId")));
            assertEquals(base, after.path("gateway").path("url").asText());
            assertEquals(process.pid(), after.path("pid").asLong());
            assertEquals(202, dashboardAction(http, base, "stop-workspace"));
            awaitDashboardState(http, base, "idle");
            try (var bootstrap = new DevServerBootstrap()) {
                assertEquals(0, bootstrap.run(projectDirectory, List.of(), true, true));
            }
            awaitDashboardState(http, base, "running");
            var resumed = mapper.readTree(sessionFile(projectDirectory).toFile());
            assertEquals(process.pid(), resumed.path("pid").asLong());
            assertEquals("saved monitoring history", Files.readString(history), "Stop/Start must preserve history");
            assertEquals(202, dashboardAction(http, base, "stop-devserver"));
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(resumed.path("runtime").path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if (process.isAlive()) { runControl(projectDirectory, "stop"); if (!process.waitFor(5, TimeUnit.SECONDS)) ProcessUtils.forceStopTree(process); }
        }
    }

    @Test
    void cliStopClosesTheRetainedDashboard(@TempDir Path projectDirectory) throws Exception {
        Process process = startServer(projectDirectory);
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            assertTrue(awaitRunningSession(sessionFile(projectDirectory)));
            String base = new ObjectMapper().readTree(sessionFile(projectDirectory).toFile()).path("gateway").path("url").asText();
            assertEquals(202, dashboardAction(http, base, "stop-workspace"));
            awaitDashboardState(http, base, "idle");
            assertEquals(0, runControl(projectDirectory, "stop").exitCode());
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        } finally { if (process.isAlive()) ProcessUtils.forceStopTree(process); }
    }

    private static int dashboardAction(java.net.http.HttpClient http, String base, String action) throws Exception {
        return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + DevConsole.ROOT + "actions/" + action))
                .header("Origin", base).header("X-Fluxzero-Console", "1")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static com.fasterxml.jackson.databind.JsonNode awaitDashboardState(java.net.http.HttpClient http, String base, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + DevConsole.ROOT + "status.json"))
                        .timeout(Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                var status = new ObjectMapper().readTree(response.body());
                if (state.equals(status.path("state").asText()) && !status.path("maintenance").path("busy").asBoolean()) return status;
            } catch (IOException ignored) { }
            Thread.sleep(50);
        }
        throw new AssertionError("Dashboard did not reach " + state);
    }

    @Test
    void restartsManagedEnvironmentOnTheSamePublicPortAndClearsMonitoringHistory(@TempDir Path projectDirectory) throws Exception {
        Process process = startServer(projectDirectory);
        ObjectMapper mapper = new ObjectMapper();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            assertTrue(awaitRunningSession(sessionFile(projectDirectory)));
            var before = mapper.readTree(sessionFile(projectDirectory).toFile());
            String base = before.path("gateway").path("url").asText();
            long runtimePid = before.path("runtime").path("pid").asLong();
            Path history = seedMonitoringHistory(projectDirectory);
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + DevConsole.ROOT + "actions/restart-devserver"))
                    .header("Origin", base).header("X-Fluxzero-Console", "1").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(202, http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            com.fasterxml.jackson.databind.JsonNode after = before;
            while (System.nanoTime() < deadline) {
                try { after = mapper.readTree(sessionFile(projectDirectory).toFile()); }
                catch (IOException ignored) { }
                if (!before.path("sessionId").equals(after.path("sessionId")) && "running".equals(after.path("status").asText())) break;
                Thread.sleep(25);
            }
            assertFalse(before.path("sessionId").equals(after.path("sessionId")), "new session expected");
            assertEquals("running", after.path("status").asText());
            assertEquals(base, after.path("gateway").path("url").asText());
            assertEquals(process.pid(), after.path("pid").asLong());
            assertFalse(ProcessHandle.of(runtimePid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(after.path("runtime").path("pid").asLong() != runtimePid);
            assertFalse(Files.exists(history), "Restart All must delete persisted VictoriaLogs history");
            assertEquals(200, http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + DevConsole.ROOT + "status.json")).build(),
                                        java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            runControl(projectDirectory, "stop");
            if (!process.waitFor(5, TimeUnit.SECONDS)) ProcessUtils.forceStopTree(process);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failedMonitoringResetKeepsEnvironmentRunningAndReportsFailure(@TempDir Path projectDirectory) throws Exception {
        Files.createDirectories(projectDirectory.resolve(".fluxzero"));
        Files.writeString(projectDirectory.resolve(DevProjectConfig.FILE), "version: 1\nmonitoring: {enabled: false}\n");
        Process process = startServer(projectDirectory);
        ObjectMapper mapper = new ObjectMapper();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            assertTrue(awaitRunningSession(sessionFile(projectDirectory)));
            var before = mapper.readTree(sessionFile(projectDirectory).toFile());
            String base = before.path("gateway").path("url").asText();
            Path external = Files.createDirectory(projectDirectory.resolve("external-history"));
            Path history = Files.writeString(external.resolve("records"), "keep");
            Path monitoringDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/monitoring"));
            Files.createSymbolicLink(monitoringDirectory.resolve("victorialogs"), external);
            assertEquals(202, dashboardAction(http, base, "restart-devserver"));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String error = "";
            while (System.nanoTime() < deadline) {
                var status = awaitDashboardState(http, base, "running");
                error = status.path("maintenance").path("error").asText();
                if (!error.isBlank()) break;
                Thread.sleep(25);
            }
            assertTrue(error.contains("Refusing to clear linked monitoring storage"), error);
            assertEquals("keep", Files.readString(history));
            assertEquals(before.path("sessionId"), mapper.readTree(sessionFile(projectDirectory).toFile()).path("sessionId"));
            assertTrue(ProcessHandle.of(before.path("runtime").path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            runControl(projectDirectory, "stop");
            if (!process.waitFor(5, TimeUnit.SECONDS)) ProcessUtils.forceStopTree(process);
        }
    }

    @Test
    void switchesProfilesThroughTheLocalConsoleAndRetainsSelectionOnRestart(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve(".fluxzero"));
        Files.writeString(directory.resolve(DevProjectConfig.FILE), """
                version: 1
                defaultProfile: local
                profiles:
                  local:
                    environment: local
                    monitoring: {enabled: false}
                  alternate:
                    environment: local
                    monitoring: {enabled: false}
                """);
        Process process = startServer(directory);
        ObjectMapper mapper = new ObjectMapper();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            assertTrue(awaitRunningSession(sessionFile(directory)));
            var before = mapper.readTree(sessionFile(directory).toFile());
            String base = before.path("gateway").path("url").asText();
            var statusUrl = java.net.URI.create(base + DevConsole.ROOT + "status.json");
            var status = mapper.readTree(http.send(java.net.http.HttpRequest.newBuilder(statusUrl).build(),
                                                   java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("local", status.path("profiles").path("active").asText());
            assertEquals(2, status.path("profiles").path("available").size());
            assertTrue(status.path("profiles").path("switchSupported").asBoolean());
            var switchUrl = java.net.URI.create(base + DevConsole.ROOT + "actions/switch-profile");
            for (String body : List.of("{}", "{", "{\"profile\":null}")) {
                var request = java.net.http.HttpRequest.newBuilder(switchUrl).header("Origin", base)
                        .header("X-Fluxzero-Console", "1").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
                assertEquals(400, http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var foreign = java.net.http.HttpRequest.newBuilder(switchUrl).header("Origin", "https://example.com")
                    .header("X-Fluxzero-Console", "1").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"profile\":\"alternate\"}")).build();
            assertEquals(403, http.send(foreign, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            var unknown = java.net.http.HttpRequest.newBuilder(switchUrl).header("Origin", base)
                    .header("X-Fluxzero-Console", "1").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"profile\":\"missing\"}")).build();
            assertEquals(409, http.send(unknown, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(before.path("sessionId"), mapper.readTree(sessionFile(directory).toFile()).path("sessionId"));
            Path history = seedMonitoringHistory(directory);
            for (String action : List.of("switch-profile", "restart-devserver")) {
                long previousRuntime = before.path("runtime").path("pid").asLong();
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + DevConsole.ROOT + "actions/" + action))
                        .header("Origin", base).header("X-Fluxzero-Console", "1")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"profile\":\"alternate\"}")).build();
                assertEquals(202, http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                var after = before;
                while (System.nanoTime() < deadline) {
                    try { after = mapper.readTree(sessionFile(directory).toFile()); } catch (IOException ignored) { }
                    if (!before.path("sessionId").equals(after.path("sessionId")) && "running".equals(after.path("status").asText())) break;
                    Thread.sleep(25);
                }
                assertFalse(before.path("sessionId").equals(after.path("sessionId")));
                assertEquals("running", after.path("status").asText());
                assertEquals("switch-profile".equals(action), Files.exists(history),
                             "Only Restart All should clear monitoring history");
                assertEquals(base, after.path("gateway").path("url").asText());
                assertEquals(process.pid(), after.path("pid").asLong());
                assertFalse(ProcessHandle.of(previousRuntime).map(ProcessHandle::isAlive).orElse(false));
                status = mapper.readTree(http.send(java.net.http.HttpRequest.newBuilder(statusUrl).build(),
                                                  java.net.http.HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("alternate", status.path("profiles").path("active").asText());
                before = after;
            }
            assertTrue(Files.readString(directory.resolve(DevProjectConfig.FILE)).contains("defaultProfile: local"));
        } finally {
            runControl(directory, "stop");
            if (!process.waitFor(5, TimeUnit.SECONDS)) ProcessUtils.forceStopTree(process);
        }
    }

    @Test
    void controlMainReportsAndStopsDetachedServer(@TempDir Path projectDirectory) throws Exception {
        Process process = startServer(projectDirectory);
        Process logs = null;
        try {
            assertTrue(awaitRunningSession(sessionFile(projectDirectory)), "dev server did not become ready");

            ProcessResult status = runControl(projectDirectory, "status");
            assertEquals(0, status.exitCode());
            assertTrue(status.output().contains("Fluxzero dev is running."), status.output());

            logs = startControl(projectDirectory, "logs", "--follow")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();

            ProcessResult stop = runControl(projectDirectory, "stop");
            assertEquals(0, stop.exitCode(), stop.output());
            assertStopOrder(stop.output());
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "controlled dev server did not exit");
            assertTrue(logs.waitFor(3, TimeUnit.SECONDS), "log follower did not exit after dev server stop");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (logs != null && logs.isAlive()) {
                logs.destroyForcibly();
            }
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void controlMainStopsServerStartedThroughProjectPathAlias(@TempDir Path directory) throws Exception {
        Path projectDirectory = Files.createDirectory(directory.resolve("project"));
        Path projectAlias = directory.resolve("project-alias");
        Files.createSymbolicLink(projectAlias, projectDirectory);
        Process process = startServer(projectAlias);
        try {
            assertTrue(awaitRunningSession(sessionFile(projectDirectory)), "dev server did not become ready");

            ProcessResult stop = runControl(projectDirectory, "stop");

            assertEquals(0, stop.exitCode(), stop.output());
            assertStopOrder(stop.output());
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "controlled dev server did not exit");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void statusReconcilesAbruptStopAndInvalidatesCommands(@TempDir Path projectDirectory) throws Exception {
        Process process = startServer(projectDirectory);
        assertTrue(awaitRunningSession(sessionFile(projectDirectory)), "dev server did not become ready");
        DevSessionStore store = new DevSessionStore(projectDirectory);
        DevSession running = store.readSession().orElseThrow();
        long runtimePid = running.runtime().pid();
        store.writeCommandStatus(new DevCommandStatus(
                "succeeded", running.sessionId(), 1, 1, 0, 0, 0,
                List.of(new DevCommandStatus.Entry("command.json", "hash", "Command", "succeeded", null, 1)), 1));

        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertTrue(awaitStopped(runtimePid),
                   "version-aligned runtime survived its supervisor");
        ProcessResult status = runControl(projectDirectory, "status");

        assertEquals(1, status.exitCode());
        assertTrue(status.output().contains("stopped unexpectedly"), status.output());
        assertEquals("stopped-unexpectedly", store.readSession().orElseThrow().status());
        assertEquals("stale", store.readCommandStatus().orElseThrow().state());
    }

    @Test
    void globallyListsRunningAndUnexpectedlyStoppedEnvironments(@TempDir Path directory) throws Exception {
        Path registryDirectory = directory.resolve("registry");
        Path orders = Files.createDirectory(directory.resolve("orders"));
        Path reporting = Files.createDirectory(directory.resolve("reporting"));
        Process ordersProcess = startServer(orders, registryDirectory);
        Process reportingProcess = startServer(reporting, registryDirectory);
        try {
            assertTrue(awaitRunningSession(sessionFile(orders)), "orders dev server did not become ready");
            assertTrue(awaitRunningSession(sessionFile(reporting)), "reporting dev server did not become ready");
            assertTrue(awaitRegistrySize(registryDirectory, 2), "dev environments were not globally registered");

            ProcessResult list = runControl(orders, registryDirectory, "list");
            assertEquals(0, list.exitCode(), list.output());
            assertTrue(list.output().contains(orders.getFileName().toString()), list.output());
            assertTrue(list.output().contains(reporting.getFileName().toString()), list.output());
            assertTrue(list.output().contains("2 active, 0 stale."), list.output());

            ProcessResult json = runControl(orders, registryDirectory, "list", "--json");
            assertEquals(0, json.exitCode(), json.output());
            var jsonOutput = new ObjectMapper().readTree(json.output());
            assertEquals(2, jsonOutput.path("active").asInt());
            assertEquals(2, jsonOutput.path("environments").size());
            var listedPaths = new java.util.HashSet<String>();
            for (var environment : jsonOutput.path("environments")) {
                listedPaths.add(Path.of(environment.path("projectDirectory").asText()).toRealPath().toString());
            }
            assertEquals(java.util.Set.of(orders.toRealPath().toString(), reporting.toRealPath().toString()), listedPaths);
            assertFalse(json.output().contains("token"), json.output());

            ProcessResult stop = runControl(orders, registryDirectory, "stop");
            assertEquals(0, stop.exitCode(), stop.output());
            assertTrue(ordersProcess.waitFor(5, TimeUnit.SECONDS), "orders dev server did not stop");
            assertTrue(awaitRegistrySize(registryDirectory, 1), "controlled stop did not unregister environment");
            assertEquals("stopped", new DevSessionStore(orders).readSession().orElseThrow().status());

            reportingProcess.destroyForcibly();
            assertTrue(reportingProcess.waitFor(5, TimeUnit.SECONDS), "reporting dev server did not terminate");
            ProcessResult stale = runControl(reporting, registryDirectory, "list");
            assertEquals(0, stale.exitCode(), stale.output());
            assertFalse(stale.output().contains(orders.getFileName().toString()), stale.output());
            assertTrue(stale.output().contains(reporting.getFileName().toString()), stale.output());
            assertTrue(stale.output().contains("0 active, 1 stale."), stale.output());
        } finally {
            if (ordersProcess.isAlive()) {
                ordersProcess.destroyForcibly();
            }
            if (reportingProcess.isAlive()) {
                reportingProcess.destroyForcibly();
            }
            assertTrue(ordersProcess.waitFor(5, TimeUnit.SECONDS), "orders dev server did not terminate");
            assertTrue(reportingProcess.waitFor(5, TimeUnit.SECONDS), "reporting dev server did not terminate");
        }
    }

    @Test
    void globallyStopsAllRegisteredEnvironments(@TempDir Path directory) throws Exception {
        Path registryDirectory = directory.resolve("registry");
        Path orders = Files.createDirectory(directory.resolve("orders"));
        Path reporting = Files.createDirectory(directory.resolve("reporting"));
        Process ordersProcess = startServer(orders, registryDirectory);
        Process reportingProcess = startServer(reporting, registryDirectory);
        try {
            assertTrue(awaitRunningSession(sessionFile(orders)), "orders dev server did not become ready");
            assertTrue(awaitRunningSession(sessionFile(reporting)), "reporting dev server did not become ready");
            assertTrue(awaitRegistrySize(registryDirectory, 2), "dev environments were not globally registered");

            ProcessResult stop = runControl(orders, registryDirectory, "stop", "--all", "--force");

            assertEquals(0, stop.exitCode(), stop.output());
            assertTrue(stop.output().contains("Stopped 2 environments"), stop.output());
            assertTrue(ordersProcess.waitFor(5, TimeUnit.SECONDS), "orders dev server did not stop");
            assertTrue(reportingProcess.waitFor(5, TimeUnit.SECONDS), "reporting dev server did not stop");
            assertTrue(awaitRegistrySize(registryDirectory, 0), "global stop did not clear registrations");
        } finally {
            if (ordersProcess.isAlive()) {
                ordersProcess.destroyForcibly();
            }
            if (reportingProcess.isAlive()) {
                reportingProcess.destroyForcibly();
            }
        }
    }

    private static Process startServer(Path projectDirectory) throws IOException {
        return startServer(projectDirectory, projectDirectory.resolve("registry"));
    }

    private static Process startServer(Path projectDirectory, Path registryDirectory) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(
                java.toString(), runtimeCacheArgument(),
                "-Dfluxzero.dev.project=" + projectDirectory.toAbsolutePath().normalize(),
                "-D" + DevEnvironmentRegistry.DIRECTORY_PROPERTY + "=" + registryDirectory,
                "-cp", testClassPath(), DevServerMain.class.getName(),
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start", "--no-tests",
                "--idp", "external")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void assertStopOrder(String output) {
        int stopping = output.indexOf(DevServerMain.STOPPING_MESSAGE);
        int stopped = output.indexOf(DevServerMain.STOPPED_MESSAGE);
        assertTrue(stopping >= 0 && stopped > stopping, output);
    }

    private static ProcessResult runControl(Path projectDirectory, String action) throws Exception {
        return runControl(projectDirectory, projectDirectory.resolve("registry"), action);
    }

    private static ProcessResult runControl(Path projectDirectory, Path registryDirectory, String action,
                                            String... options) throws Exception {
        Process process = startControl(projectDirectory, registryDirectory, action, options)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(8, TimeUnit.SECONDS), "control process did not exit");
        return new ProcessResult(process.exitValue(), output);
    }

    private static ProcessBuilder startControl(Path projectDirectory, String action, String... options) {
        return startControl(projectDirectory, projectDirectory.resolve("registry"), action, options);
    }

    private static ProcessBuilder startControl(Path projectDirectory, Path registryDirectory, String action,
                                               String... options) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>(List.of(
                java.toString(), "-D" + DevEnvironmentRegistry.DIRECTORY_PROPERTY + "=" + registryDirectory,
                "-cp", testClassPath(), DevServerControlMain.class.getName(),
                action, "--project-dir", projectDirectory.toString()));
        command.addAll(List.of(options));
        return new ProcessBuilder(command);
    }

    private static Path seedMonitoringHistory(Path project) throws IOException {
        Path directory = Files.createDirectories(project.resolve(".fluxzero/dev/monitoring/victorialogs"));
        return Files.writeString(directory.resolve("test-history-marker"), "saved monitoring history");
    }

    private static Path sessionFile(Path projectDirectory) {
        return projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevSessionStore.SESSION_FILE);
    }

    private static String testClassPath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private static String runtimeCacheArgument() {
        return "-Dfluxzero.dev.runtime.cache=" + System.getProperty(
                "fluxzero.dev.runtime.cache", "target/dev-runtime-test-cache");
    }

    private static boolean awaitRunningSession(Path sessionFile) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Files.isRegularFile(sessionFile)
                    && "running".equals(objectMapper.readTree(sessionFile.toFile()).path("status").asText())) {
                    return true;
                }
            } catch (Exception ignored) {
                // Atomic session replacement may briefly race with the read on some file systems.
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean awaitStopped(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessUtils.isAlive(pid)) {
                return true;
            }
            Thread.sleep(50);
        }
        return !ProcessUtils.isAlive(pid);
    }

    private static boolean awaitRegistrySize(Path registryDirectory, int expected) throws Exception {
        DevEnvironmentRegistry registry = new DevEnvironmentRegistry(registryDirectory);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (registry.list().size() == expected) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
