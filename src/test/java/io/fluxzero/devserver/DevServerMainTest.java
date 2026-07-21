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
                java.toString(), "-Dfluxzero.dev.project=" + projectDirectory.toAbsolutePath().normalize(),
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
        store.writeCommandStatus(new DevCommandStatus(
                "succeeded", running.sessionId(), 1, 1, 0, 0, 0,
                List.of(new DevCommandStatus.Entry("command.json", "hash", "Command", "succeeded", null, 1)), 1));

        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
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
            assertTrue(list.output().contains(orders.toString()), list.output());
            assertTrue(list.output().contains(reporting.toString()), list.output());
            assertTrue(list.output().contains("2 active, 0 stale."), list.output());

            ProcessResult json = runControl(orders, registryDirectory, "list", "--json");
            assertEquals(0, json.exitCode(), json.output());
            var jsonOutput = new ObjectMapper().readTree(json.output());
            assertEquals(2, jsonOutput.path("active").asInt());
            assertEquals(2, jsonOutput.path("environments").size());
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
            assertFalse(stale.output().contains(orders.toString()), stale.output());
            assertTrue(stale.output().contains(reporting.toString()), stale.output());
            assertTrue(stale.output().contains("0 active, 1 stale."), stale.output());
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
                java.toString(), "-Dfluxzero.dev.project=" + projectDirectory.toAbsolutePath().normalize(),
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

    private static Path sessionFile(Path projectDirectory) {
        return projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevSessionStore.SESSION_FILE);
    }

    private static String testClassPath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
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
