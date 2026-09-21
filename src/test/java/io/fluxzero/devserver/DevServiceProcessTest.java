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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServiceProcessTest {

    @Test
    @EnabledOnOs({OS.WINDOWS, OS.LINUX, OS.MAC})
    void gracefullyStopsManagedServiceWithoutExplicitCleanup(@TempDir Path projectDirectory) throws Exception {
        Path started = projectDirectory.resolve("started.txt");
        Path cleaned = projectDirectory.resolve("cleaned.txt");
        String command = javaCommand() + " " + DevServiceFixtureServer.class.getName() + " graceful "
                         + quote(started.toString()) + " " + quote(cleaned.toString());
        DevServiceConfig config = new DevServiceConfig(
                command, null, null, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness(null, null, Pattern.compile("^READY$"), Duration.ofSeconds(5)));

        try (DevServiceProcess service = DevServiceProcess.prepare(
                "graceful", config, projectDirectory, "session", Duration.ofSeconds(2), ignored -> {}, ignored -> {})) {
            service.start();
            assertTrue(Files.isRegularFile(started));
        }

        assertTrue(Files.isRegularFile(cleaned));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void reportsNonzeroCleanupCommandAndStillStopsTheService(@TempDir Path projectDirectory) throws Exception {
        Path started = projectDirectory.resolve("started.txt");
        Path cleaned = projectDirectory.resolve("cleaned.txt");
        String fixture = javaCommand() + " " + DevServiceFixtureServer.class.getName();
        DevServiceConfig config = new DevServiceConfig(
                fixture + " graceful " + quote(started.toString()) + " " + quote(cleaned.toString()),
                fixture + " stop-fail", null, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness(null, null, Pattern.compile("^READY$"), Duration.ofSeconds(5)));
        List<ProcessUtils.ProcessOutput> output = new CopyOnWriteArrayList<>();
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();

        try (DevServiceProcess service = DevServiceProcess.prepare(
                "cleanup-failure", config, projectDirectory, "session", Duration.ofSeconds(2),
                statuses::add, output::add)) {
            service.start();
        }

        assertTrue(Files.isRegularFile(cleaned));
        assertTrue(output.stream().anyMatch(line -> line.line().contains("cleanup command exited with code 9")));
        assertTrue(statuses.getLast().detail().contains("cleanup command exited with code 9"));
    }

    @Test
    void startsManagedServiceOnDynamicPortAndRunsExplicitCleanup(@TempDir Path projectDirectory) throws Exception {
        Path stopped = projectDirectory.resolve("stopped.txt");
        String java = javaCommand();
        DevServiceConfig config = new DevServiceConfig(
                java + " " + DevServiceFixtureServer.class.getName() + " {servicePort.http}",
                java + " " + DevServiceFixtureServer.class.getName() + " stop " + quote(stopped.toString()),
                "http://127.0.0.1:{servicePort.http}", null,
                new LinkedHashMap<>(Map.of("http", 0)),
                Map.of("FIXTURE_VALUE", "{url}/configured"),
                new DevServiceConfig.Readiness("{url}", null, Duration.ofSeconds(5)));
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();
        DevServiceProcess service = DevServiceProcess.prepare(
                "victoriaLogs", config, projectDirectory, "session-1", statuses::add, ignored -> {
                });

        service.start();
        long pid = service.status().pid();
        String body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(service.url())).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertTrue(service.ready());
        assertEquals("running", service.status().state());
        assertEquals(service.url(), service.placeholders().get("services.victoriaLogs.url"));
        assertEquals(Integer.toString(service.ports().get("http")),
                     service.placeholders().get("services.victoriaLogs.ports.http"));
        assertEquals("port=" + service.ports().get("http")
                     + ";service=victoriaLogs;servicePort=" + service.ports().get("http")
                     + ";session=session-1;configured=" + service.url() + "/configured", body);
        assertTrue(ProcessUtils.isAlive(pid));

        service.close();

        assertTrue(Files.isRegularFile(stopped));
        assertFalse(ProcessUtils.isAlive(pid));
        assertEquals("stopped", statuses.getLast().state());
    }

    @Test
    void waitsForExternalServiceWithoutOwningItsLifecycle(@TempDir Path projectDirectory) throws Exception {
        int port = ProcessUtils.availablePort();
        Process external = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable("java")).toString(),
                "-cp", System.getProperty("java.class.path"), DevServiceFixtureServer.class.getName(),
                Integer.toString(port)).start();
        try {
            DevServiceConfig config = new DevServiceConfig(
                    null, null, "http://127.0.0.1:" + port, null, Map.of(), Map.of(),
                    new DevServiceConfig.Readiness("http://127.0.0.1:" + port, null, Duration.ofSeconds(5)));
            DevServiceProcess service = DevServiceProcess.prepare(
                    "external", config, projectDirectory, "session-1", ignored -> {
                    }, ignored -> {
                    });

            service.start();
            service.close();

            assertTrue(external.isAlive());
        } finally {
            ProcessUtils.forceStopTree(external);
        }
    }

    @Test
    void reportsBoundedReadinessFailure(@TempDir Path projectDirectory) throws Exception {
        int port = ProcessUtils.availablePort();
        DevServiceConfig config = new DevServiceConfig(
                null, null, "http://127.0.0.1:" + port, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness("http://127.0.0.1:" + port, null, Duration.ofMillis(250)));
        DevServiceProcess service = DevServiceProcess.prepare(
                "missing", config, projectDirectory, "session-1", ignored -> {
                }, ignored -> {
                });

        DevServerStartupException exception = assertThrows(DevServerStartupException.class, service::start);

        assertTrue(exception.getMessage().contains("did not become ready"));
        assertEquals("failed", service.status().state());
        service.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdout", "stderr"})
    void matchesImmediateAnsiOutputBeforeRedactionAndTracksExit(String stream, @TempDir Path directory)
            throws Exception {
        List<ProcessUtils.ProcessOutput> output = new CopyOnWriteArrayList<>();
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();
        try (DevServiceProcess service = DevServiceProcess.prepare(
                "listener", logConfig(directory, stream, "immediate", Duration.ofSeconds(5)), directory,
                "session", statuses::add, output::add)) {
            service.start();
            assertTrue(service.ready());
            await(() -> output.stream().anyMatch(o -> o.line().contains("Ready!")));
            assertTrue(output.stream().anyMatch(o -> o.stream().equals(stream)
                                                    && o.line().equals("Ready! [REDACTED] [REDACTED]")));
            // Starting the same adapter again must never create a second process.
            long pid = service.status().pid();
            service.start();
            assertEquals(pid, service.status().pid());
            Files.writeString(directory.resolve("exit"), "exit now");
            await(() -> "failed".equals(service.status().state()));
            assertFalse(service.ready());
            assertTrue(statuses.stream().anyMatch(status -> "running".equals(status.state())));
            assertNoSecrets(output.toString() + statuses);
        }
        await(() -> output.stream().filter(o -> o.line().startsWith("Ready!")).count() == 3);
        assertNoSecrets(output.toString() + statuses);
    }

    @Test
    void timeoutCannotRecoverFromLateReadyOrCleanupOutput(@TempDir Path directory) throws Exception {
        List<ProcessUtils.ProcessOutput> output = new CopyOnWriteArrayList<>();
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();
        try (DevServiceProcess service = DevServiceProcess.prepare(
                "listener", logConfig(directory, "stdout", "delayed", Duration.ofMillis(400)), directory,
                "session", statuses::add, output::add)) {
            assertTrue(assertThrows(DevServerStartupException.class, service::start).getMessage()
                               .contains("did not become ready"));
            Files.writeString(directory.resolve("ready"), "late readiness");
            await(() -> output.stream().anyMatch(o -> o.line().startsWith("Ready!")));
            assertEquals("failed", service.status().state());
            assertFalse(service.ready());
        }
        await(() -> output.stream().filter(o -> o.line().startsWith("Ready!")).count() == 3);
        assertTrue(statuses.stream().noneMatch(status -> "running".equals(status.state())));
        assertEquals("stopped", statuses.getLast().state());
        assertNoSecrets(output.toString() + statuses);
    }

    @Test
    void exitBeforeReadyFailsStartup(@TempDir Path directory) {
        DevServiceConfig config = new DevServiceConfig(
                javaCommand() + " " + DevServiceFixtureServer.class.getName() + " exit", null, null, null,
                Map.of(), Map.of(), new DevServiceConfig.Readiness(null, null, Pattern.compile("Ready!"),
                                                                  Duration.ofSeconds(5)));
        try (DevServiceProcess service = DevServiceProcess.prepare(
                "listener", config, directory, "session", ignored -> {}, ignored -> {})) {
            assertTrue(assertThrows(DevServerStartupException.class, service::start).getMessage()
                               .contains("exited before readiness"));
            assertFalse(service.ready());
        }
    }

    @Test
    void shutdownWhileStartingCannotBeUndoneByLateOutputOrAnotherInstance(@TempDir Path directory) throws Exception {
        List<ProcessUtils.ProcessOutput> output = new CopyOnWriteArrayList<>();
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();
        CountDownLatch outputBlocked = new CountDownLatch(1);
        CountDownLatch releaseOutput = new CountDownLatch(1);
        DevServiceProcess old = DevServiceProcess.prepare(
                "listener", logConfig(directory, "stdout", "delayed", Duration.ofSeconds(5)), directory,
                "old-session", statuses::add, line -> {
                    output.add(line);
                    if (line.line().equals("fixture started")) {
                        outputBlocked.countDown();
                        try {
                            releaseOutput.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        CompletableFuture<Void> startup = CompletableFuture.runAsync(old::start);
        try {
            assertTrue(outputBlocked.await(5, TimeUnit.SECONDS));
            // Queue a Ready line behind a blocked reader, then close before it can be consumed.
            Files.writeString(directory.resolve("ready"), "release fixture");
            await(() -> Files.exists(directory.resolve("ready-emitted")));
            old.close();
            startup.get(5, TimeUnit.SECONDS);
            Files.delete(directory.resolve("ready"));
            try (DevServiceProcess replacement = DevServiceProcess.prepare(
                    "listener", logConfig(directory, "stderr", "delayed", Duration.ofMillis(400)), directory,
                    "new-session", ignored -> {}, output::add)) {
                releaseOutput.countDown();
                assertThrows(DevServerStartupException.class, replacement::start);
                assertFalse(replacement.ready());
                assertFalse(old.ready());
                assertEquals("stopped", old.status().state());
                assertTrue(statuses.stream().noneMatch(status -> "running".equals(status.state())));
            }
        } finally {
            releaseOutput.countDown();
            old.close();
        }
        assertNoSecrets(output.toString() + statuses);
    }

    @Test
    void supportsTcpReadiness(@TempDir Path directory) {
        DevServiceConfig config = new DevServiceConfig(
                javaCommand() + " " + DevServiceFixtureServer.class.getName() + " {servicePort.http}",
                null, null, null, Map.of("http", 0), Map.of(),
                new DevServiceConfig.Readiness(null, "127.0.0.1:{servicePort.http}", Duration.ofSeconds(5)));
        try (DevServiceProcess service = DevServiceProcess.prepare(
                "tcp", config, directory, "session", ignored -> {}, ignored -> {})) {
            service.start();
            assertTrue(service.ready());
        }
    }

    @Test
    void redactsBeforeTerminalLogDiagnosticsAndMcpPublication(@TempDir Path directory) throws Exception {
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();
        DevSession session = DevSession.empty(DevServerConfig.defaults(directory));
        CountDownLatch stopOutput = new CountDownLatch(2);
        try (DevLogStore store = new DevLogStore(directory, session.sessionId(), "app");
             TerminalProgress terminal = new TerminalProgress(false, new PrintStream(terminalBytes))) {
            try (DevServiceProcess service = DevServiceProcess.prepare(
                    "listener", logConfig(directory, "stderr", "immediate", Duration.ofSeconds(5)), directory,
                    session.sessionId(), status -> store.observeStatus(
                            "service", "support", "listener", null, status.state(), status.detail()), line -> {
                        store.process("service", "support", "listener", null, line.stream(), line.line());
                        terminal.println(line.line());
                        if (line.line().equals("Ready! [REDACTED]")) {
                            stopOutput.countDown();
                        }
                    })) {
                service.start();
                await(() -> store.diagnostics().activeCount() > 0);
                assertTrue(service.ready());
                assertMcpOutputRedacted(new AgentQueryService(() -> session, store), true);
            }
            assertTrue(stopOutput.await(5, TimeUnit.SECONDS));
            String terminalOutput = terminalBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(terminalOutput.contains("Ready! [REDACTED] [REDACTED]"));
            assertNoSecrets(terminalOutput);
            for (Path file : List.of(store.combinedLog(), store.eventsFile(), store.problemsFile(),
                                     store.diagnosticsFile())) {
                assertNoSecrets(Files.readString(file));
            }
            assertMcpOutputRedacted(new AgentQueryService(() -> session, store), false);
        }
    }

    private static void assertMcpOutputRedacted(AgentQueryService queries, boolean activeProblems) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        for (var tool : DevMcpTools.tools(queries, mapper)) {
            if (!List.of("get_logs", "get_active_problems").contains(tool.tool().name())) {
                continue;
            }
            var result = tool.callHandler().apply(null,
                    McpSchema.CallToolRequest.builder(tool.tool().name()).arguments(Map.of()).build());
            assertFalse(Boolean.TRUE.equals(result.isError()));
            String json = mapper.writeValueAsString(result);
            if (activeProblems || tool.tool().name().equals("get_logs")) {
                assertTrue(json.contains("[REDACTED]"));
            }
            assertNoSecrets(json);
        }
    }

    private static DevServiceConfig logConfig(Path directory, String stream, String mode, Duration timeout) {
        String fixture = javaCommand() + " " + DevServiceFixtureServer.class.getName();
        return new DevServiceConfig(
                fixture + " log " + stream + " " + quote(directory.toString()) + " " + mode,
                fixture + " stop-log " + quote(directory.toString()), null, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness(null, null, Pattern.compile("^Ready! whsec_Fake[0-9]{3}"), timeout),
                List.of(Pattern.compile("whsec_[A-Za-z0-9]+"), Pattern.compile("token_[0-9]+")));
    }

    private static void assertNoSecrets(String published) {
        assertFalse(published.contains("whsec_"), published);
        assertFalse(published.contains("token_456"), published);
        assertFalse(published.contains("Fake123"), published);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true within five seconds");
    }

    private static String javaCommand() {
        Path testClasses = Path.of(URI.create(DevServiceFixtureServer.class.getProtectionDomain()
                                                       .getCodeSource().getLocation().toExternalForm()));
        return quote(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString())
               + " -cp " + quote(testClasses.toString());
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }
}
