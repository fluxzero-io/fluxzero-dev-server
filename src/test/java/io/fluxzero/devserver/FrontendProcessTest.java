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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class FrontendProcessTest {

    @Test
    void frontendOutputDoesNotControlLifecycle(@TempDir Path projectDirectory)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = "printf 'Failed to compile\\nCompiled successfully\\n'; exec "
                         + quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();

        try (FrontendProcess frontend = FrontendProcess.start(
                config(projectDirectory, command, Duration.ofMillis(250)), statuses::add, ignored -> {
                })) {
            assertTrue(await(frontend::ready));
            assertFalse(statuses.stream().anyMatch(status -> "failed".equals(status.state())), statuses.toString());
        }
    }

    @Test
    void runsSetupInConfiguredDirectoryBeforeFrontend(@TempDir Path projectDirectory) throws Exception {
        Path frontendDirectory = Files.createDirectories(projectDirectory.resolve("ui"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = "test -f setup-complete; exec " + quote(java) + " -cp "
                         + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        FrontendConfig frontendConfig = FrontendConfig.command(command)
                .withLaunchSetup("ui", "printf ready > setup-complete");
        DevServerConfig config = config(projectDirectory, frontendConfig, Duration.ofMillis(150));

        try (FrontendProcess frontend = FrontendProcess.start(
                config, ignored -> {
                }, ignored -> {
                })) {
            assertTrue(await(frontend::ready));
            assertEquals("ready", Files.readString(frontendDirectory.resolve("setup-complete")));
        }
    }

    @Test
    void restartsUnexpectedProcessExitOnce(@TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String server = "exec " + quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                        + FrontendFixtureServer.class.getName() + " {port}";
        String command = "attempt=$(cat attempts 2>/dev/null || printf 0); attempt=$((attempt + 1)); "
                         + "printf $attempt > attempts; if [ $attempt -eq 1 ]; then exit 7; fi; " + server;
        List<String> output = new CopyOnWriteArrayList<>();

        FrontendConfig frontendConfig = FrontendConfig.command(command)
                .withLaunchSetup(null, "printf x >> setups");
        try (FrontendProcess frontend = FrontendProcess.start(
                config(projectDirectory, frontendConfig, Duration.ofMillis(150)), ignored -> {
                }, output::add)) {
            assertTrue(await(frontend::ready));
            assertEquals("2", Files.readString(projectDirectory.resolve("attempts")));
            assertEquals("x", Files.readString(projectDirectory.resolve("setups")));
            assertTrue(output.stream().anyMatch(line -> line.contains("restarting once")), output.toString());
        }
    }

    @Test
    void stopsRetryingAfterSecondProcessExit(@TempDir Path projectDirectory) throws Exception {
        String command = "printf x >> attempts; exit 7";

        try (FrontendProcess frontend = FrontendProcess.start(
                config(projectDirectory, command, Duration.ofMillis(150)), ignored -> {
                }, ignored -> {
                })) {
            assertTrue(await(() -> "failed".equals(frontend.status().state())));
            assertEquals("xx", Files.readString(projectDirectory.resolve("attempts")));
            Thread.sleep(400);
            assertEquals("xx", Files.readString(projectDirectory.resolve("attempts")));
        }
    }

    @Test
    void restartsOnceWhenReadyFrontendRemainsUnavailable(@TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String server = quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                        + FrontendFixtureServer.class.getName() + " {port}";
        String command = "attempt=$(cat attempts 2>/dev/null || printf 0); attempt=$((attempt + 1)); "
                         + "printf $attempt > attempts; if [ $attempt -eq 1 ]; then " + server
                         + " & child=$!; sleep 4; kill -9 $child; wait $child 2>/dev/null; sleep 30; "
                         + "else exec " + server + "; fi";
        List<String> output = new CopyOnWriteArrayList<>();

        try (FrontendProcess frontend = FrontendProcess.start(
                config(projectDirectory, command, Duration.ofMillis(150)), ignored -> {
                }, output::add)) {
            assertTrue(await(() -> "2".equals(readIfExists(projectDirectory.resolve("attempts")))
                                   && frontend.ready()));
            assertTrue(output.stream().anyMatch(line -> line.contains("remained unavailable")
                                                        && line.contains("restarting once")), output.toString());
        }
    }

    @Test
    void substitutesDynamicPortAndExposesOnlyRelativeBackendPath(@TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command(command), List.of());

        try (FrontendProcess frontend = FrontendProcess.prepare(config, ignored -> {
        }, ignored -> {
        })) {
            assertEquals("starting", frontend.status().state());
            assertEquals(null, frontend.status().pid());
            frontend.launch(ignored -> {
            });
            assertTrue(await(frontend::ready));
            String body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(frontend.internalUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertEquals("port=" + frontend.status().port() + ";backend=" + DevGateway.BACKEND_PREFIX, body);
        }
    }

    @Test
    void staleCleanupRecognizesOwnershipAndStopsEntireFrontendTree(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command("sleep 30 & echo $! > child.pid; wait"), List.of());

        String sessionId = "frontend-session-1";
        try (FrontendProcess frontend = FrontendProcess.prepare(config, sessionId, ignored -> {
        }, ignored -> {
        })) {
            frontend.launch(ignored -> {
            });
            long parentPid = frontend.status().pid();
            Path childPidFile = projectDirectory.resolve("child.pid");
            assertTrue(await(() -> Files.isRegularFile(childPidFile)));
            long childPid = Long.parseLong(Files.readString(childPidFile).strip());
            assertTrue(ProcessUtils.isAlive(parentPid));
            assertTrue(ProcessUtils.isAlive(childPid));

            assertTrue(ProcessUtils.stopIfCommandLineContains(
                    parentPid, sessionId, Duration.ofSeconds(2)));

            assertTrue(await(() -> !ProcessUtils.isAlive(parentPid)));
            assertTrue(await(() -> !ProcessUtils.isAlive(childPid)));
        }
    }

    private static boolean await(CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static String readIfExists(Path path) throws Exception {
        return Files.isRegularFile(path) ? Files.readString(path) : null;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static DevServerConfig config(Path projectDirectory, String command, Duration startupTimeout) {
        return config(projectDirectory, FrontendConfig.command(command), startupTimeout);
    }

    private static DevServerConfig config(Path projectDirectory, FrontendConfig frontend, Duration startupTimeout) {
        return new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                startupTimeout,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                frontend, List.of());
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
