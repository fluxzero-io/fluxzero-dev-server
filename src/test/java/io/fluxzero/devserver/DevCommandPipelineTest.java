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
import io.fluxzero.common.api.Metadata;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.MessageType.COMMAND;
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
        AtomicReference<Metadata> processedMetadata = new AtomicReference<>();
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

            Registration registration = fluxzero.registerHandlers(new Handler(processedName, processedMetadata));
            try (DevCommandPipeline pipeline = new DevCommandPipeline(
                    config, store, "ws://localhost:" + localPort(runtime), status::set, output::add)) {
                pipeline.requestRun();

                assertTrue(awaitStatus(status, "succeeded"));
                assertEquals("Ada", processedName.get());
                assertEquals("$system", processedMetadata.get().get("$user"));
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
    void lateConsumerProcessesCommandFromConfiguredReplayWindow(@TempDir Path projectDirectory) throws Exception {
        Path commandDirectory = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY);
        Files.createDirectories(commandDirectory);
        writeCreateUserCommand(commandDirectory.resolve("create-user.json"), "Ada");
        Server runtime = TestServer.startServer(0, Duration.ofSeconds(10));
        AtomicReference<DevCommandStatus> status = new AtomicReference<>();
        AtomicBoolean handled = new AtomicBoolean();
        WebSocketClient inspector = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + localPort(runtime))
                .name("command-inspector")
                .id("command-inspector")
                .build());
        try (DevCommandPipeline pipeline = new DevCommandPipeline(
                new DevServerConfig(
                        projectDirectory, null, "dev-test-app", null,
                        false, false, false,
                        DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                        DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                        DevServerConfig.DEFAULT_DEBOUNCE,
                        FrontendConfig.none(), null),
                new DevSessionStore(projectDirectory),
                "ws://localhost:" + localPort(runtime), status::set, ignored -> {
                })) {
            pipeline.requestRun();
            assertTrue(awaitCommand(inspector));

            Thread.sleep(1_500L);
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
            Registration registration = fluxzero.registerHandlers(new Object() {
                @HandleCommand
                void handle(CreateUser ignored) {
                    handled.set(true);
                }
            });
            try {
                assertTrue(awaitStatus(status, "succeeded"), () -> String.valueOf(status.get()));
                assertTrue(handled.get());
            } finally {
                registration.cancel();
                fluxzero.close();
            }
        } finally {
            inspector.shutDown();
            runtime.stop();
        }
    }

    @Test
    void appliesConfiguredUsersToInlineAndReferencedCommands(@TempDir Path projectDirectory) throws Exception {
        Path users = projectDirectory.resolve("src/test/resources/users");
        Files.createDirectories(users);
        writeFixtureCommand(users.resolve("create-system.json"), "system");
        writeFixtureCommand(users.resolve("create-admin.json"), "admin");
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                defaultProfile: seeded
                profiles:
                  seeded:
                    commandDefaults:
                      userMetadataKey: $actor
                      systemUser:
                        name: Local System
                        roles: [admin]
                    commands:
                      - src/test/resources/users/create-system.json
                      - src/test/resources/users/create-admin.json:
                          user: admin
                          metadata:
                            source: referenced
                      - create-legacy-user:
                          user:
                            name: Legacy Admin
                            roles: [admin]
                          type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                          payload:
                            name: legacy
                      - create-custom-metadata-user:
                          type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                          metadata:
                            $sender: delegated-admin
                          payload:
                            name: custom
                """);
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        List<Metadata> processedMetadata = new CopyOnWriteArrayList<>();
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start", "--no-tests"
        });
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
        Registration registration = fluxzero.registerHandlers(new MetadataHandler(processedNames, processedMetadata));
        try (DevCommandPipeline pipeline = new DevCommandPipeline(
                config, store, "ws://localhost:" + localPort(runtime), status::set, ignored -> {
        })) {
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "succeeded"));
            assertEquals(List.of("system", "admin", "legacy", "custom"), processedNames);
            assertEquals("Local System", processedMetadata.get(0).get("$actor", Map.class).get("name"));
            assertEquals("admin", processedMetadata.get(1).get("$actor"));
            assertEquals("referenced", processedMetadata.get(1).get("source"));
            assertEquals("Legacy Admin", processedMetadata.get(2).get("$actor", Map.class).get("name"));
            assertEquals("delegated-admin", processedMetadata.get(3).get("$sender"));

            String originalHash = store.readCommandStatus().orElseThrow().commands().get(1).hash();
            Files.writeString(configFile, Files.readString(configFile).replace("user: admin", "user: operator"));
            status.set(null);
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "succeeded"));
            assertEquals(List.of("system", "admin", "legacy", "custom", "admin"), processedNames);
            assertEquals("operator", processedMetadata.getLast().get("$actor"));
            String changedHash = store.readCommandStatus().orElseThrow().commands().get(1).hash();
            assertTrue(!originalHash.equals(changedHash));
        } finally {
            registration.cancel();
            fluxzero.close();
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
    void executesSelectedProfileCommandsInDeclarationOrderBeforeFilesAndRetriesOnlyChangedDefinition(
            @TempDir Path projectDirectory) throws Exception {
        writeProfileCommands(projectDirectory, "first", "second");
        Path fileCommand = projectDirectory.resolve(DevCommandPipeline.COMMAND_DIRECTORY).resolve("010-file.json");
        Files.createDirectories(fileCommand.getParent());
        writeCreateUserCommand(fileCommand, "file");
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--profile", "seeded",
                "--no-watch", "--no-compile-on-start", "--no-tests"
        });
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

            writeProfileCommands(projectDirectory, "first", "changed");
            pipeline.requestRun();

            assertTrue(awaitProcessed(processedNames, "changed", 4));
            assertEquals(List.of("first", "second", "file", "changed"), processedNames);
        } finally {
            registration.cancel();
            fluxzero.close();
            runtime.stop();
        }
    }

    @Test
    void executesReferencedTestFixtureFilesAndTracksInheritedChanges(@TempDir Path projectDirectory) throws Exception {
        Path resourceDirectory = projectDirectory.resolve("src/test/resources/user");
        Files.createDirectories(resourceDirectory);
        Path base = resourceDirectory.resolve("base-user.json");
        Path inherited = resourceDirectory.resolve("create-inherited-user.json");
        Path direct = resourceDirectory.resolve("create-direct-user.json");
        writeFixtureCommand(base, "file");
        writeFixtureCommand(direct, "second-file");
        Files.writeString(inherited, """
                {
                  "@extends": "/user/base-user.json",
                  "@revision": 0
                }
                """);
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                profiles:
                  seeded:
                    commands:
                      - src/test/resources/user/create-inherited-user.json
                      - create-inline:
                          type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                          payload:
                            name: inline
                      - src/test/resources/user/create-direct-user.json
                """);
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--profile", "seeded",
                "--no-watch", "--no-compile-on-start", "--no-tests"
        });
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
            assertEquals(List.of("file", "inline", "second-file"), processedNames);
            assertEquals(List.of("src/test/resources/user/create-inherited-user.json",
                                 "commands.create-inline",
                                 "src/test/resources/user/create-direct-user.json"),
                         store.readCommandStatus().orElseThrow().commands().stream()
                                 .map(DevCommandStatus.Entry::path).toList());
            assertTrue(pipeline.references(base));
            assertTrue(pipeline.references(inherited));
            assertTrue(pipeline.references(direct));

            writeFixtureCommand(base, "changed");
            status.set(null);
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "succeeded"));
            assertEquals(List.of("file", "inline", "second-file", "changed"), processedNames);
        } finally {
            registration.cancel();
            fluxzero.close();
            runtime.stop();
        }
    }

    @Test
    void expandsFixtureGlobsAlphabeticallyAtTheirDeclaredPosition(@TempDir Path projectDirectory) throws Exception {
        Path users = projectDirectory.resolve("src/test/resources/users");
        Files.createDirectories(users.resolve("nested"));
        writeFixtureCommand(users.resolve("020-second.json"), "second");
        writeFixtureCommand(users.resolve("010-first.json"), "first");
        writeFixtureCommand(users.resolve("nested/015-nested.json"), "nested");
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                commands:
                  - before:
                      type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                      payload:
                        name: before
                  - src/test/resources/users/*.json
                  - after:
                      type: io.fluxzero.devserver.DevCommandPipelineTest$CreateUser
                      payload:
                        name: after
                """);
        Server runtime = TestServer.startServer(0);
        List<String> processedNames = new CopyOnWriteArrayList<>();
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start", "--no-tests"
        });
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
            assertEquals(List.of("before", "first", "second", "after"), processedNames);
            assertEquals(List.of("commands.before", "src/test/resources/users/010-first.json",
                                 "src/test/resources/users/020-second.json", "commands.after"),
                         store.readCommandStatus().orElseThrow().commands().stream()
                                 .map(DevCommandStatus.Entry::path).toList());

            Path added = users.resolve("015-added.json");
            assertTrue(pipeline.references(added));
            writeFixtureCommand(added, "added");
            status.set(null);
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "succeeded"));
            assertEquals(List.of("before", "first", "second", "after", "added"), processedNames);
        } finally {
            registration.cancel();
            fluxzero.close();
            runtime.stop();
        }
    }

    @Test
    void reportsEmptyFixtureGlobAndKeepsItWatched(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                commands:
                  - src/test/resources/users/**/*.json
                """);
        Server runtime = TestServer.startServer(0);
        AtomicReference<DevCommandStatus> status = new AtomicReference<>();
        List<String> output = new CopyOnWriteArrayList<>();
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start", "--no-tests"
        });
        try (DevCommandPipeline pipeline = new DevCommandPipeline(
                config, new DevSessionStore(projectDirectory), "ws://localhost:" + localPort(runtime),
                status::set, output::add)) {
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "failed"));
            assertTrue(output.stream().anyMatch(line -> line.contains("glob matched no files")));
            assertTrue(pipeline.references(projectDirectory.resolve("src/test/resources/users/new/create.json")));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void keepsMissingConfiguredFileWatchedForRecovery(@TempDir Path projectDirectory) throws Exception {
        Path missing = projectDirectory.resolve("src/test/resources/user/create-admin.json");
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                commands:
                  - src/test/resources/user/create-admin.json
                """);
        Server runtime = TestServer.startServer(0);
        AtomicReference<DevCommandStatus> status = new AtomicReference<>();
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start", "--no-tests"
        });
        try (DevCommandPipeline pipeline = new DevCommandPipeline(
                config, new DevSessionStore(projectDirectory), "ws://localhost:" + localPort(runtime),
                status::set, ignored -> {
                })) {
            pipeline.requestRun();

            assertTrue(awaitStatus(status, "failed"));
            assertTrue(pipeline.references(missing));
        } finally {
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

    private static boolean awaitCommand(WebSocketClient client) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!client.getTrackingClient(COMMAND).readFromIndex(0, 10).isEmpty()) {
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

    private static void writeFixtureCommand(Path command, String name) throws Exception {
        Files.writeString(command, """
                {
                  "@class": "io.fluxzero.devserver.DevCommandPipelineTest$CreateUser",
                  "name": "%s"
                }
                """.formatted(name));
    }

    private static void writeProfileCommands(Path projectDirectory, String first, String second) throws Exception {
        Path config = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                version: 1
                profiles:
                  seeded:
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

    private record Handler(AtomicReference<String> processedName, AtomicReference<Metadata> processedMetadata) {
        @HandleCommand
        CreatedUser handle(CreateUser command, Metadata metadata) {
            processedName.set(command.name());
            processedMetadata.set(metadata);
            return new CreatedUser(command.name());
        }
    }

    private record MetadataHandler(List<String> processedNames, List<Metadata> processedMetadata) {
        @HandleCommand
        CreatedUser handle(CreateUser command, Metadata metadata) {
            processedNames.add(command.name());
            processedMetadata.add(metadata);
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
