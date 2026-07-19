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
import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.testserver.TestServer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevCommandPipelineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesDiscoveredCommandsAndWritesStatus(@TempDir Path projectDirectory) throws Exception {
        Path commandDirectory = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY);
        Files.createDirectories(commandDirectory);
        writeCreateUserCommand(commandDirectory.resolve("create-user.json"), "Ada");
        Server runtime = TestServer.startServer(0);
        AtomicReference<String> processedName = new AtomicReference<>();
        try {
            DevServerConfig config = new DevServerConfig(
                    projectDirectory, null, "dev-test-app", null,
                    false, false, false,
                    DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                    DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                    DevServerConfig.DEFAULT_DEBOUNCE,
                    FrontendConfig.none(), null);
            DevSessionStore store = new DevSessionStore(projectDirectory);
            AtomicReference<DevCommandStatus> status = new AtomicReference<>();
            List<String> output = new CopyOnWriteArrayList<>();
            WebSocketClient appClient = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl("ws://localhost:" + localPort(runtime))
                    .name("dev-test-app")
                    .id("dev-test-app")
                    .build());
            Fluxzero fluxzero = DefaultFluxzero.builder()
                    .disableShutdownHook()
                    .disableKeepalive()
                    .disableTrackingMetrics()
                    .disableCacheEvictionMetrics()
                    .build(appClient);

            Registration registration = fluxzero.registerHandlers(new Handler(processedName));
            try (DevCommandPipeline pipeline = new DevCommandPipeline(
                    config, store, "ws://localhost:" + localPort(runtime), status::set, output::add)) {
                pipeline.requestRun();

                assertTrue(awaitStatus(status, "succeeded"));
                assertEquals("Ada", processedName.get());
            } finally {
                registration.cancel();
                fluxzero.close();
            }

            Path statusFile = store.directory().resolve(DevSessionStore.COMMAND_STATUS_FILE);
            JsonNode json = objectMapper.readTree(statusFile.toFile());
            assertEquals("succeeded", json.path("state").asText());
            assertEquals(CreateUser.class.getName(), json.path("commands").get(0).path("type").asText());
            assertTrue(json.path("commands").get(0).path("detail").asText().contains("processed by app"));
            assertTrue(output.stream().anyMatch(line -> line.contains("executing")));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void changedCommandContentIsRetriedForSamePath(@TempDir Path projectDirectory) throws Exception {
        Path command = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY).resolve("create-user.json");
        Files.createDirectories(command.getParent());
        writeCreateUserCommand(command, "Ada");
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        try {
            DevServerConfig config = new DevServerConfig(
                    projectDirectory, null, "dev-test-app", null,
                    false, false, false,
                    DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                    DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                    DevServerConfig.DEFAULT_DEBOUNCE,
                    FrontendConfig.none(), null);
            DevSessionStore store = new DevSessionStore(projectDirectory);
            AtomicReference<DevCommandStatus> status = new AtomicReference<>();
            WebSocketClient appClient = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl("ws://localhost:" + localPort(runtime))
                    .name("dev-test-app")
                    .id("dev-test-app")
                    .build());
            Fluxzero fluxzero = DefaultFluxzero.builder()
                    .disableShutdownHook()
                    .disableKeepalive()
                    .disableTrackingMetrics()
                    .disableCacheEvictionMetrics()
                    .build(appClient);

            Registration registration = fluxzero.registerHandlers(new ListHandler(processedNames));
            try (DevCommandPipeline pipeline = new DevCommandPipeline(
                    config, store, "ws://localhost:" + localPort(runtime), status::set, ignored -> {
                    })) {
                pipeline.requestRun();
                assertTrue(awaitProcessed(processedNames, "Ada", 1));
                String firstHash = store.readCommandStatus().orElseThrow().commands().getFirst().hash();

                Files.writeString(command, """
                        {"payload":{"name":"Ada"},"metadata":{"source":"dev"},
                         "type":"io.fluxzero.devserver.DevCommandPipelineTest$CreateUser"}
                        """);
                status.set(null);
                pipeline.requestRun();

                assertTrue(awaitStatus(status, "succeeded"));
                assertEquals(List.of("Ada"), processedNames);
                assertEquals(firstHash, store.readCommandStatus().orElseThrow().commands().getFirst().hash());

                writeCreateUserCommand(command, "Grace");
                status.set(null);
                pipeline.requestRun();

                assertTrue(awaitProcessed(processedNames, "Grace", 2));
                assertTrue(awaitStatus(status, "succeeded"));
                DevCommandStatus.Entry entry = store.readCommandStatus().orElseThrow().commands().getFirst();
                assertEquals("succeeded", entry.state());
                assertTrue(entry.detail().contains("processed by app"));
                assertTrue(!firstHash.equals(entry.hash()));
            } finally {
                registration.cancel();
                fluxzero.close();
            }
        } finally {
            runtime.stop();
        }
    }

    @Test
    void successfulCommandsRunAgainForANewDevSession(@TempDir Path projectDirectory) throws Exception {
        Path command = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY).resolve("create-user.json");
        Files.createDirectories(command.getParent());
        writeCreateUserCommand(command, "Ada");
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        WebSocketClient appClient = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + localPort(runtime))
                .name("dev-test-app")
                .id("dev-test-app")
                .build());
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableShutdownHook()
                .disableKeepalive()
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .build(appClient);
        Registration registration = fluxzero.registerHandlers(new ListHandler(processedNames));
        try {
            try (DevCommandPipeline first = new DevCommandPipeline(
                    config, store, "ws://localhost:" + localPort(runtime), ignored -> {
            }, ignored -> {
            }, "session-1")) {
                first.requestRun();
                assertTrue(awaitProcessed(processedNames, "Ada", 1));
            }

            try (DevCommandPipeline second = new DevCommandPipeline(
                    config, store, "ws://localhost:" + localPort(runtime), ignored -> {
            }, ignored -> {
            }, "session-2")) {
                second.requestRun();
                assertTrue(awaitProcessed(processedNames, "Ada", 2));
            }

            assertEquals("session-2", store.readCommandStatus().orElseThrow().sessionId());
        } finally {
            registration.cancel();
            fluxzero.close();
            runtime.stop();
        }
    }

    @Test
    void executesInPathOrderAndResumesBlockedCommandsAfterFailure(@TempDir Path projectDirectory) throws Exception {
        Path commandDirectory = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY);
        Files.createDirectories(commandDirectory);
        writeCreateUserCommand(commandDirectory.resolve("030-third.json"), "third");
        writeCreateUserCommand(commandDirectory.resolve("010-first.json"), "first");
        writeCreateUserCommand(commandDirectory.resolve("020-middle.json"), "middle");
        Server runtime = TestServer.startServer(0);
        List<String> attempts = new CopyOnWriteArrayList<>();
        AtomicBoolean allowMiddle = new AtomicBoolean();
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        AtomicReference<DevCommandStatus> status = new AtomicReference<>();
        List<String> output = new CopyOnWriteArrayList<>();
        WebSocketClient appClient = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + localPort(runtime))
                .name("dev-test-app")
                .id("dev-test-app")
                .build());
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableShutdownHook()
                .disableKeepalive()
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .build(appClient);
        Registration registration = fluxzero.registerHandlers(new OrderedHandler(attempts, allowMiddle));
        try (DevCommandPipeline pipeline = new DevCommandPipeline(
                config, store, "ws://localhost:" + localPort(runtime), status::set, output::add)) {
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "failed"));
            DevCommandStatus failed = store.readCommandStatus().orElseThrow();
            assertEquals(List.of("first", "middle"), attempts);
            assertEquals(List.of("succeeded", "failed", "blocked"),
                         failed.commands().stream().map(DevCommandStatus.Entry::state).toList());
            assertEquals(List.of(
                                 "src/test/resources/fluxzero/dev/commands/010-first.json",
                                 "src/test/resources/fluxzero/dev/commands/020-middle.json",
                                 "src/test/resources/fluxzero/dev/commands/030-third.json"),
                         failed.commands().stream().map(DevCommandStatus.Entry::path).toList());
            assertEquals(1, failed.succeeded());
            assertEquals(1, failed.failed());
            assertEquals(1, failed.blocked());
            assertEquals(0, failed.pending());
            assertTrue(failed.commands().getLast().detail().contains("020-middle.json"));

            allowMiddle.set(true);
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "succeeded"));
            assertEquals(List.of("first", "middle", "middle", "third"), attempts);
            DevCommandStatus succeeded = store.readCommandStatus().orElseThrow();
            assertEquals(3, succeeded.succeeded());
            assertEquals(0, succeeded.failed());
            assertEquals(0, succeeded.blocked());
            assertTrue(output.stream().anyMatch(line -> line.contains("blocked 1 command")));
        } finally {
            registration.cancel();
            fluxzero.close();
            runtime.stop();
        }
    }

    @Test
    void executesYamlCommandsInDeclarationOrderBeforeFilesAndRetriesOnlyChangedDefinition(
            @TempDir Path projectDirectory) throws Exception {
        writeYamlCommands(projectDirectory, "first", "second");
        Path fileCommand = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY).resolve("010-file.json");
        Files.createDirectories(fileCommand.getParent());
        writeCreateUserCommand(fileCommand, "file");
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        AtomicReference<DevCommandStatus> status = new AtomicReference<>();
        WebSocketClient appClient = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + localPort(runtime))
                .name("dev-test-app")
                .id("dev-test-app")
                .build());
        Fluxzero fluxzero = DefaultFluxzero.builder()
                .disableShutdownHook()
                .disableKeepalive()
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .build(appClient);
        Registration registration = fluxzero.registerHandlers(new ListHandler(processedNames));
        try (DevCommandPipeline pipeline = new DevCommandPipeline(
                config, store, "ws://localhost:" + localPort(runtime), status::set, ignored -> {
        })) {
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "succeeded"));
            assertEquals(List.of("first", "second", "file"), processedNames);
            assertEquals(List.of("commands.create-first", "commands.create-second",
                                 "src/test/resources/fluxzero/dev/commands/010-file.json"),
                         store.readCommandStatus().orElseThrow().commands().stream()
                                 .map(DevCommandStatus.Entry::path).toList());

            writeYamlCommands(projectDirectory, "first", "changed");
            pipeline.requestRun();

            assertTrue(awaitProcessed(processedNames, "changed", 4));
            assertEquals(List.of("first", "second", "file", "changed"), processedNames);
        } finally {
            registration.cancel();
            fluxzero.close();
            runtime.stop();
        }
    }

    private static boolean awaitStatus(AtomicReference<DevCommandStatus> status, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            DevCommandStatus value = status.get();
            if (value != null && expected.equals(value.state())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean awaitProcessed(List<String> processedNames, String expectedName, int expectedSize)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (processedNames.size() >= expectedSize && expectedName.equals(processedNames.getLast())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void writeCreateUserCommand(Path command, String name) throws Exception {
        Files.writeString(command, """
                {
                  "type": "io.fluxzero.devserver.DevCommandPipelineTest$CreateUser",
                  "metadata": {
                    "source": "dev"
                  },
                  "payload": {
                    "name": "%s"
                  }
                }
                """.formatted(name));
    }

    private static void writeYamlCommands(Path projectDirectory, String first, String second) throws Exception {
        Path config = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                version: 1
                commands:
                  create-first:
                    type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                    metadata:
                      source: yaml
                    payload:
                      name: %s
                  create-second:
                    type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                    payload:
                      name: %s
                """.formatted(first, second));
    }

    private static int localPort(Server server) {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    private record CreateUser(String name) {
    }

    private record CreatedUser(String name) {
    }

    private record Handler(AtomicReference<String> processedName) {
        @HandleCommand
        CreatedUser handle(CreateUser command) {
            processedName.set(command.name());
            return new CreatedUser(command.name());
        }
    }

    private record ListHandler(List<String> processedNames) {
        @HandleCommand
        CreatedUser handle(CreateUser command) {
            processedNames.add(command.name());
            return new CreatedUser(command.name());
        }
    }

    private record OrderedHandler(List<String> attempts, AtomicBoolean allowMiddle) {
        @HandleCommand
        CreatedUser handle(CreateUser command) {
            attempts.add(command.name());
            if ("middle".equals(command.name()) && !allowMiddle.get()) {
                throw new SeedFailure("middle is not ready");
            }
            return new CreatedUser(command.name());
        }
    }

    private static final class SeedFailure extends FunctionalException {
        private SeedFailure(String message) {
            super(message);
        }
    }
}
