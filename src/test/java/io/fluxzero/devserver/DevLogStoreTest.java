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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.fluxzero.devserver.DevLogEvent.Level.ERROR;
import static io.fluxzero.devserver.DevLogEvent.Level.WARN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("mutates the JVM-global Logback root logger")
class DevLogStoreTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesCombinedStructuredAndProblemLogs(@TempDir Path projectDirectory) throws Exception {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.accept("[compile] compiling changed handlers");
            store.accept("[compile] [stderr] [WARNING] deprecated processor option");
            store.process("app", "application", "orders", "orders-1", "stdout",
                          "12:00:00 ERROR com.example.OrderHandler - database unavailable");

            assertTrue(Files.readString(store.combinedLog()).contains("[compile orders stderr]"));
            List<DevLogEvent> events = store.readEvents(0, 10, null, null);
            assertEquals(3, events.size());
            assertEquals(List.of("internal", "stderr", "stdout"),
                         events.stream().map(DevLogEvent::stream).toList());
            assertEquals(WARN, events.get(1).level());
            assertEquals(ERROR, events.get(2).level());
            assertEquals("orders", events.get(2).serviceId());
            assertEquals("orders-1", events.get(2).instanceId());

            DevDiagnostics diagnostics = store.diagnostics();
            assertEquals(2, diagnostics.activeCount());
            assertEquals(1, diagnostics.errors());
            assertEquals(1, diagnostics.warnings());
            assertTrue(Files.readString(store.problemsFile()).contains("observed"));
            assertEquals(2, objectMapper.readTree(store.diagnosticsFile().toFile()).path("activeCount").asInt());
        }
    }

    @Test
    void returnsExactCombinedLogLineForEachProcessEvent(@TempDir Path projectDirectory) throws Exception {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            DevLogStore.LogPosition first = store.process(
                    "app", "application", "orders", "orders-1", "stdout", "INFO first");
            DevLogStore.LogPosition second = store.process(
                    "app", "application", "orders", "orders-1", "stderr", "ERROR second");

            assertEquals(store.combinedLog(), first.file());
            assertEquals(1, first.line());
            assertEquals(2, second.line());
            assertTrue(Files.readAllLines(store.combinedLog()).get(1).contains("ERROR second"));
        }
    }

    @Test
    void recognizesSlf4jBootstrapWarningsAndErrors(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.process("app", "application", "orders", "orders-1", "stderr",
                          "SLF4J(W): No SLF4J providers were found.");
            store.process("app", "application", "billing", "billing-1", "stderr",
                          "SLF4J(E): Failed to load a provider.");

            assertEquals(List.of(WARN, ERROR),
                         store.readEvents(0, 10, null, WARN).stream().map(DevLogEvent::level).toList());
            assertEquals(2, store.diagnostics().activeCount());
        }
    }

    @Test
    void resolvesLifecycleProblemsOnlyAfterSuccess(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.observeStatus("compile", "build", "orders", null, "failed", "cannot compile OrderHandler");
            assertEquals(1, store.diagnostics().activeCount());

            store.observeStatus("compile", "build", "orders", null, "running", "retrying");
            assertEquals(1, store.diagnostics().activeCount());

            store.observeStatus("compile", "build", "orders", null, "succeeded", "build 2 ready");
            assertEquals(0, store.diagnostics().activeCount());
            assertTrue(read(store.problemsFile()).contains("resolved"));
        }
    }

    @Test
    void resolvesInfrastructureLogProblemsWhenServiceIsRunningAgain(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.process("frontend", "infrastructure", "frontend", null, "stderr",
                          "ERROR frontend compilation failed");
            assertEquals(1, store.diagnostics().activeCount());

            store.observeStatus("frontend", "infrastructure", "frontend", null,
                                "running", "frontend ready");

            assertEquals(0, store.diagnostics().activeCount());
        }
    }

    @Test
    void resolvesTestStatusAndOutputProblemsWhenRetryPasses(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.process("test", "test", "orders", null, "stdout",
                          "[ERROR] MollieTest expected success");
            store.observeStatus("test", "test", "orders", null, "failed", "exit code 1");
            assertEquals(2, store.diagnostics().activeCount());

            store.observeStatus("test", "test", "orders", null, "running", "retrying MollieTest");
            assertEquals(2, store.diagnostics().activeCount());

            store.observeStatus("test", "test", "orders", null, "passed", "38 tests passed");
            assertEquals(0, store.diagnostics().activeCount());
            assertTrue(read(store.problemsFile()).contains("\"reason\":\"passed\""));
        }
    }

    @Test
    void keepsApplicationsAndInstancesSeparateAndResolvesOnlyReplacedInstance(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.process("app", "application", "orders", "orders-1", "stdout",
                          "ERROR failed to reserve inventory");
            store.process("app", "application", "orders", "orders-1", "stdout",
                          "ERROR failed to reserve inventory");
            store.process("app", "application", "billing", "billing-4", "stderr",
                          "ERROR payment provider unavailable");

            assertEquals(2, store.diagnostics().activeCount());
            DevProblem orders = store.diagnostics().problems().stream()
                    .filter(problem -> "orders".equals(problem.serviceId())).findFirst().orElseThrow();
            assertEquals(2, orders.occurrences());

            store.observeStatus("app", "application", "orders", "orders-1",
                                "running", "application ready");
            assertEquals(2, store.diagnostics().activeCount());

            store.resolveInstance("orders", "orders-1", "orders instance replaced");

            assertEquals(1, store.diagnostics().activeCount());
            assertEquals("billing", store.diagnostics().problems().getFirst().serviceId());
            assertEquals("billing-4", store.diagnostics().problems().getFirst().instanceId());
        }
    }

    @Test
    void filtersAndBoundsCursorReads(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders")) {
            store.process("app", "application", "orders", "orders-1", "stdout", "INFO first");
            store.process("app", "application", "billing", "billing-1", "stdout", "WARN second");
            store.process("app", "application", "orders", "orders-1", "stderr", "ERROR third");

            List<DevLogEvent> events = store.readEvents(1, 1, "orders", WARN);

            assertEquals(1, events.size());
            assertEquals("third", events.getFirst().message().substring("ERROR ".length()));
            assertEquals(3, events.getFirst().sequence());
        }
    }

    @Test
    void preservesFullEventsAndReadsAcrossRotatedFilesInCursorOrder(@TempDir Path projectDirectory) {
        String longMessage = "INFO " + "x".repeat(600);
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders", 500, 2)) {
            store.process("app", "application", "orders", "orders-1", "stdout", longMessage);
            store.process("app", "application", "orders", "orders-1", "stdout", "INFO second");
            store.process("app", "application", "orders", "orders-1", "stdout", "INFO third");

            List<DevLogEvent> firstPage = store.readEvents(0, 2, null, null);
            List<DevLogEvent> secondPage = store.readEvents(firstPage.getLast().sequence(), 2, null, null);

            assertEquals(List.of(1L, 2L), firstPage.stream().map(DevLogEvent::sequence).toList());
            assertEquals(longMessage, firstPage.getFirst().message());
            assertEquals(List.of(3L), secondPage.stream().map(DevLogEvent::sequence).toList());
            assertTrue(Files.isRegularFile(store.eventsFile().resolveSibling(DevLogStore.EVENTS_FILE + ".1")));
        }
    }

    @Test
    void capturesEmbeddedServiceLogging(@TempDir Path projectDirectory) {
        try (DevLogStore store = new DevLogStore(projectDirectory, "session-1", "orders");
             EmbeddedLogCapture ignored = EmbeddedLogCapture.start(store)) {
            org.slf4j.LoggerFactory.getLogger("io.fluxzero.testserver.Probe").warn("runtime pressure");

            List<DevLogEvent> events = awaitEvents(store, "runtime", WARN);
            assertFalse(events.isEmpty(), "embedded warning was not captured");
            assertEquals("runtime", events.getLast().source());
            assertEquals("infrastructure", events.getLast().serviceType());
        }
    }

    @Test
    void retainsOnlyRecentSessionDirectories(@TempDir Path projectDirectory) throws Exception {
        Path logs = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevLogStore.LOGS_DIRECTORY);
        Files.createDirectories(logs);
        for (int index = 0; index < 7; index++) {
            Path oldSession = Files.createDirectories(logs.resolve("old-" + index));
            Files.setLastModifiedTime(oldSession, java.nio.file.attribute.FileTime.fromMillis(index + 1L));
        }

        try (DevLogStore ignored = new DevLogStore(projectDirectory, "current", "orders")) {
            try (var sessions = Files.list(logs)) {
                List<String> names = sessions.map(path -> path.getFileName().toString()).toList();
                assertEquals(5, names.size());
                assertTrue(names.contains("current"));
            }
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<DevLogEvent> awaitEvents(DevLogStore store, String serviceId,
                                                 DevLogEvent.Level minimumLevel) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        List<DevLogEvent> events;
        do {
            events = store.readEvents(0, 10, serviceId, minimumLevel);
            if (!events.isEmpty()) {
                return events;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        } while (System.nanoTime() < deadline);
        return events;
    }
}
