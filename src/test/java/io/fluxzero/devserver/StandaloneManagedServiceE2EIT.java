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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Qualifies Unix managed-service shutdown through the packaged standalone launcher. */
@EnabledIfSystemProperty(named = "fluxzero.devserver.e2e", matches = "true")
@EnabledOnOs({OS.LINUX, OS.MAC})
class StandaloneManagedServiceE2EIT {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void reusesGatewayAndServicePortsAfterGracefulCleanup(@TempDir Path project) throws Exception {
        Path resource = project.resolve("external-resource.lock");
        Path cleaned = project.resolve("service-cleaned.txt");
        int servicePort = availablePort();
        writeConfig(project, servicePort, resource, cleaned);

        Process first = startStandalone(project, 0, false);
        Process second = null;
        try {
            JsonNode firstSession = awaitSession(project, "running");
            int gatewayPort = firstSession.path("gateway").path("port").asInt();
            long firstServicePid = firstSession.path("services").path("qualification").path("pid").asLong();
            assertTrue(Files.isRegularFile(resource));
            assertTrue(ProcessUtils.isAlive(firstServicePid));

            stop(first);
            assertTrue(Files.isRegularFile(cleaned), Files.readString(project.resolve("dev-server-1.out")));
            assertFalse(Files.exists(resource));
            assertFalse(ProcessUtils.isAlive(firstServicePid));

            Files.delete(cleaned);
            second = startStandalone(project, gatewayPort, true);
            JsonNode secondSession = awaitSession(project, "running");
            long secondServicePid = secondSession.path("services").path("qualification").path("pid").asLong();
            assertEquals(gatewayPort, secondSession.path("gateway").path("port").asInt());
            assertNotEquals(firstServicePid, secondServicePid);
            assertTrue(Files.isRegularFile(resource));
            JsonNode updateStatus = awaitStatus(gatewayPort);
            assertEquals("updated", updateStatus.path("update").path("phase").asText());
            assertEquals("mac-qualification", updateStatus.path("update").path("attemptId").asText());

            stop(second);
            assertTrue(Files.isRegularFile(cleaned), Files.readString(project.resolve("dev-server-2.out")));
            assertFalse(Files.exists(resource));
            assertFalse(ProcessUtils.isAlive(secondServicePid));
        } finally {
            forceStop(first);
            forceStop(second);
        }
    }

    private static void writeConfig(Path project, int servicePort, Path resource, Path cleaned) throws Exception {
        Path config = project.resolve(DevProjectConfig.FILE);
        Files.createDirectories(config.getParent());
        String java = quote(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Path testClasses = Path.of(URI.create(DevServiceFixtureServer.class.getProtectionDomain()
                                                       .getCodeSource().getLocation().toExternalForm()));
        String fixture = java + " -cp " + quote(testClasses.toString()) + " "
                         + DevServiceFixtureServer.class.getName();
        Files.writeString(config, """
                version: 1
                monitoring: {enabled: false}
                services:
                  qualification:
                    command: >-
                      %s graceful-port %d %s %s
                    readiness:
                      log: READY
                      timeout: 5s
                """.formatted(fixture, servicePort, quote(resource.toString()), quote(cleaned.toString())));
    }

    private static Process startStandalone(Path project, int port, boolean update) throws Exception {
        Path standalone;
        try (var files = Files.list(Path.of("target"))) {
            standalone = files.filter(path -> path.getFileName().toString().endsWith("-standalone.jar"))
                    .findFirst().orElseThrow();
        }
        Path testClasses = Path.of(URI.create(DevServiceFixtureServer.class.getProtectionDomain()
                                                       .getCodeSource().getLocation().toExternalForm()));
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "--sun-misc-unsafe-memory-access=allow",
                "-D" + DevEnvironmentRegistry.DIRECTORY_PROPERTY + "=" + project.resolve("registry"),
                "-Dfluxzero.dev.runtime.cache=" + System.getProperty(
                        "fluxzero.dev.runtime.cache", "target/dev-runtime-test-cache")));
        if (update) {
            command.add("-D" + DevServerUpdates.ATTEMPT_PROPERTY + "=mac-qualification");
            command.add("-D" + DevServerUpdates.PHASE_PROPERTY + "=starting-new");
        }
        command.addAll(List.of(
                "-cp", standalone.toAbsolutePath() + File.pathSeparator + testClasses,
                DevServerMain.class.getName(),
                "--project-dir", project.toString(), "--port", Integer.toString(port),
                "--no-watch", "--no-compile-on-start", "--no-tests", "--no-frontend", "--idp", "external"));
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(project.resolve(update ? "dev-server-2.out" : "dev-server-1.out").toFile()).start();
    }

    private static JsonNode awaitSession(Path project, String state) throws Exception {
        Path session = project.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevSessionStore.SESSION_FILE);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                JsonNode result = OBJECT_MAPPER.readTree(session.toFile());
                if (state.equals(result.path("status").asText())
                    && "running".equals(result.path("services").path("qualification").path("state").asText())) {
                    return result;
                }
            } catch (Exception ignored) {
                // Session publication is atomic, but the file may not exist yet.
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Standalone dev server did not reach " + state);
    }

    private static JsonNode awaitStatus(int port) throws Exception {
        URI target = URI.create("http://127.0.0.1:" + port + DevConsole.ROOT + "status.json");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try (HttpClient client = HttpClient.newHttpClient()) {
            while (System.nanoTime() < deadline) {
                try {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    JsonNode status = OBJECT_MAPPER.readTree(response.body());
                    if ("updated".equals(status.path("update").path("phase").asText())) {
                        return status;
                    }
                } catch (Exception ignored) {
                    // The gateway may still be publishing its first snapshot.
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("Updated status was not published");
    }

    private static void stop(Process process) throws Exception {
        process.destroy();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "standalone dev server did not stop gracefully");
        assertEquals(143, process.exitValue());
    }

    private static void forceStop(Process process) {
        if (process != null && process.isAlive()) {
            ProcessUtils.forceStopTree(process);
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return server.getLocalPort();
        }
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }
}
