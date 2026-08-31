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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.devserver.DevLogEvent.Level.ERROR;
import static io.fluxzero.devserver.DevLogEvent.Level.WARN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentQueryServiceTest {

    @Test
    void filtersMultipleApplicationsAndBoundsPages(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            store.process("app", "application", "orders", "orders-1", "stdout", "INFO order one");
            store.process("app", "application", "billing", "billing-1", "stderr", "WARN billing one");
            store.process("app", "application", "orders", "orders-2", "stderr", "ERROR order two");

            AgentSelector orders = new AgentSelector(Set.of("orders"), Set.of(), Set.of("app"), WARN);
            AgentLogPage firstPage = service.getLogs(new AgentCursor(session.sessionId(), 0), orders, 1);

            assertEquals(1, firstPage.events().size());
            assertEquals("orders-2", firstPage.events().getFirst().instanceId());
            assertFalse(firstPage.hasMore());
            assertEquals(3, firstPage.cursor().sequence());
            assertEquals(2, service.getStatus().activeProblems());
            AgentProblemPage problems = service.getActiveProblems(orders, 10);
            assertEquals(1, problems.problems().size());
            assertEquals(1, problems.activeProblemCount());
        }
    }

    @Test
    void capsPageSizeAndReportsMoreResults(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            for (int index = 0; index < AgentQueryService.MAX_LIMIT + 1; index++) {
                store.accept("WARN event " + index);
            }

            AgentLogPage page = service.getLogs(null, AgentSelector.all(), Integer.MAX_VALUE);
            AgentProblemPage problems = service.getActiveProblems(AgentSelector.all(), Integer.MAX_VALUE);

            assertEquals(AgentQueryService.MAX_LIMIT, page.events().size());
            assertTrue(page.hasMore());
            assertEquals(AgentQueryService.MAX_LIMIT, page.cursor().sequence());
            assertEquals(AgentQueryService.MAX_LIMIT, problems.problems().size());
            assertEquals(AgentQueryService.MAX_LIMIT + 1, problems.activeProblemCount());
            assertTrue(problems.truncated());
        }
    }

    @Test
    void rejectsCursorFromReplacedSessionWithoutReadingOldEvents(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            store.accept("WARN current session problem");

            AgentLogPage page = service.getLogs(new AgentCursor("old-session", 99), AgentSelector.all(), 10);
            AgentChange change = service.waitForChange(new AgentCursor("old-session", 99), AgentSelector.all(),
                                                       Duration.ofSeconds(1), 10);

            assertTrue(page.sessionChanged());
            assertTrue(page.events().isEmpty());
            assertEquals(session.sessionId(), page.cursor().sessionId());
            assertTrue(change.sessionChanged());
            assertFalse(change.timedOut());
            assertTrue(change.problemChanges().isEmpty());
            assertEquals(1, change.activeProblemCount());
        }
    }

    @Test
    void waitSkipsUnrelatedApplicationAndReturnsMatchingChange(@TempDir Path projectDirectory) throws Exception {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentSelector orders = new AgentSelector(Set.of("orders"), Set.of(), Set.of("app"), null);
            AgentCursor cursor = service.getStatus().cursor();

            CompletableFuture<AgentChange> waiting = CompletableFuture.supplyAsync(
                    () -> service.waitForChange(cursor, orders, Duration.ofSeconds(2), 10));
            store.process("app", "application", "billing", "billing-1", "stdout", "INFO irrelevant");
            Thread.sleep(50);
            assertFalse(waiting.isDone());

            store.process("app", "application", "orders", "orders-1", "stdout", "INFO relevant");
            AgentChange change = waiting.get(1, TimeUnit.SECONDS);

            assertFalse(change.timedOut());
            assertEquals(1, change.events().size());
            assertEquals("orders", change.events().getFirst().serviceId());
            assertEquals(2, change.cursor().sequence());
        }
    }

    @Test
    void waitReturnsCompactTimeoutCursor(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);

            AgentChange change = service.waitForChange(service.getStatus().cursor(), AgentSelector.all(),
                                                       Duration.ofMillis(20), 10);

            assertTrue(change.timedOut());
            assertTrue(change.events().isEmpty());
            assertEquals(session.sessionId(), change.cursor().sessionId());
        }
    }

    @Test
    void reportsProblemTransitionsWithoutRepeatingActiveSnapshots(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentSelector warnings = new AgentSelector(Set.of("orders"), Set.of(), Set.of("compile"), WARN);
            AgentCursor cursor = service.getStatus().cursor();

            store.observeStatus("compile", "build", "orders", null, "failed", "cannot compile OrderHandler");
            AgentChange added = service.waitForChange(cursor, warnings, Duration.ZERO, 10);

            assertEquals(List.of(AgentProblemChange.Type.ADDED), types(added));
            assertEquals(1, added.problemChanges().getFirst().problem().occurrences());
            assertEquals(1, added.activeProblemCount());
            AgentChange retry = service.waitForChange(cursor, warnings, Duration.ZERO, 10);
            assertEquals(added.cursor(), retry.cursor());
            assertEquals(added.problemChanges(), retry.problemChanges());

            AgentChange unchanged = service.waitForChange(added.cursor(), warnings, Duration.ofMillis(10), 10);
            assertTrue(unchanged.timedOut());
            assertTrue(unchanged.events().isEmpty());
            assertTrue(unchanged.problemChanges().isEmpty());
            assertEquals(1, unchanged.activeProblemCount());

            store.observeStatus("compile", "build", "orders", null, "failed", "cannot compile OrderHandler");
            AgentChange changed = service.waitForChange(unchanged.cursor(), warnings, Duration.ZERO, 10);
            assertEquals(List.of(AgentProblemChange.Type.CHANGED), types(changed));
            assertEquals(2, changed.problemChanges().getFirst().problem().occurrences());
            assertEquals(1, changed.activeProblemCount());

            store.observeStatus("compile", "build", "orders", null, "succeeded", "build ready");
            AgentChange resolved = service.waitForChange(changed.cursor(), warnings, Duration.ZERO, 10);
            assertTrue(resolved.events().isEmpty(), "the INFO lifecycle event does not match a WARN selector");
            assertEquals(List.of(AgentProblemChange.Type.RESOLVED), types(resolved));
            assertEquals("succeeded", resolved.problemChanges().getFirst().reason());
            assertEquals(0, resolved.activeProblemCount());
        }
    }

    @Test
    void returnsOnlyProblemsThatChangedAfterTheCursor(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            store.process("app", "application", "orders", "orders-1", "stderr", "ERROR orders unavailable");
            AgentCursor afterOrders = service.getStatus().cursor();

            store.process("app", "application", "billing", "billing-1", "stderr", "ERROR billing unavailable");
            AgentChange change = service.waitForChange(afterOrders, AgentSelector.all(), Duration.ZERO, 10);

            assertEquals(1, change.problemChanges().size());
            assertEquals("billing", change.problemChanges().getFirst().problem().serviceId());
            assertEquals(2, change.activeProblemCount());
        }
    }

    @Test
    void keepsOneCausalSequenceTogetherWhenPaging(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            store.process("app", "application", "orders", "orders-1", "stderr", "ERROR first failure");
            store.process("app", "application", "orders", "orders-1", "stderr", "ERROR second failure");
            AgentCursor beforeResolution = service.getStatus().cursor();

            store.resolveInstance("orders", "orders-1", "instance replaced");
            AgentChange resolved = service.waitForChange(beforeResolution, AgentSelector.all(), Duration.ZERO, 1);

            assertEquals(1, resolved.events().size());
            assertEquals(2, resolved.problemChanges().size());
            assertTrue(resolved.problemChanges().stream()
                               .allMatch(change -> change.sequence() == resolved.cursor().sequence()));
            assertEquals(List.of(AgentProblemChange.Type.RESOLVED, AgentProblemChange.Type.RESOLVED),
                         types(resolved));
            assertFalse(resolved.hasMore());
            assertEquals(0, resolved.activeProblemCount());
        }
    }

    @Test
    void drainsMixedEventAndProblemPagesWithoutDuplicates(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentCursor cursor = service.getStatus().cursor();
            for (int index = 0; index < 4; index++) {
                store.observeStatus("compile", "build", "project-" + index, null, "failed", "failure " + index);
            }

            List<Long> eventSequences = new ArrayList<>();
            List<Long> problemSequences = new ArrayList<>();
            AgentChange page;
            do {
                page = service.waitForChange(cursor, AgentSelector.all(), Duration.ZERO, 2);
                eventSequences.addAll(page.events().stream().map(DevLogEvent::sequence).toList());
                problemSequences.addAll(page.problemChanges().stream().map(AgentProblemChange::sequence).toList());
                cursor = page.cursor();
            } while (page.hasMore());

            assertEquals(List.of(1L, 2L, 3L, 4L), eventSequences);
            assertEquals(List.of(1L, 2L, 3L, 4L), problemSequences);
            assertEquals(4, page.activeProblemCount());
        }
    }

    @Test
    void snapshotAndCursorRemainConsistentDuringConcurrentProblemWrites(@TempDir Path projectDirectory)
            throws Exception {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int index = 0; index < 50; index++) {
                    store.observeStatus("compile", "build", "project-" + index, null, "failed",
                                        "failure " + index);
                }
            });

            start.countDown();
            AgentProblemPage baseline = service.getActiveProblems(AgentSelector.all(), 100);
            Map<String, AgentProblemSummary> localProblems = new HashMap<>();
            baseline.problems().forEach(
                    problem -> localProblems.put(problem.id(), AgentProblemSummary.from(problem)));
            AgentCursor cursor = baseline.cursor();
            while (!writer.isDone() || cursor.sequence() < store.lastSequence()) {
                AgentChange changes = service.waitForChange(cursor, AgentSelector.all(), Duration.ZERO, 7);
                apply(localProblems, changes.problemChanges());
                cursor = changes.cursor();
            }
            writer.get(1, TimeUnit.SECONDS);

            AgentProblemPage current = service.getActiveProblems(AgentSelector.all(), 100);
            assertFalse(current.truncated());
            assertEquals(current.activeProblemCount(), current.problems().size());
            assertEquals(current.problems().stream().map(DevProblem::id).collect(java.util.stream.Collectors.toSet()),
                         localProblems.keySet());
            assertTrue(current.problems().stream()
                               .allMatch(problem -> problem.lastEventSequence() <= current.cursor().sequence()));
        }
    }

    private static List<AgentProblemChange.Type> types(AgentChange change) {
        return change.problemChanges().stream().map(AgentProblemChange::type).toList();
    }

    private static void apply(Map<String, AgentProblemSummary> problems, List<AgentProblemChange> changes) {
        changes.forEach(change -> {
            if (change.type() == AgentProblemChange.Type.RESOLVED) {
                problems.remove(change.problem().id());
            } else {
                problems.put(change.problem().id(), change.problem());
            }
        });
    }
}
