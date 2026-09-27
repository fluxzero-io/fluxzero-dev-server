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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.fluxzero.devserver.AgentChangeType.LOGS;
import static io.fluxzero.devserver.AgentChangeType.PROBLEMS;
import static io.fluxzero.devserver.AgentChangeType.STATUS;
import static io.fluxzero.devserver.AgentChangeType.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentChangeTypesTest {
    @Test
    void defaultWaitSkipsRawLogsButKeepsCompactStatusAndProblems(@TempDir Path directory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(directory));
        try (DevLogStore store = new DevLogStore(directory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentCursor baseline = service.getStatus().cursor();
            String detail = "large detail ".repeat(500) + "\nstacktrace";
            store.process("app", "application", "orders", "orders-1", "stdout", detail);
            store.observeStatus("compile", "build", "orders", null, "failed", detail);
            AgentChange change = service.waitForChange(baseline, AgentSelector.all(), Duration.ZERO, 50);
            assertEquals(List.of("failed"), change.events().stream().map(DevLogEvent::message).toList());
            assertEquals(1, change.problemChanges().size());
            assertEquals(1, change.activeProblemCount());
            assertEquals(2, change.cursor().sequence());
            assertFalse(change.timedOut());
            assertFalse(change.hasMore());
            assertEquals(change, service.waitForChange(baseline, AgentSelector.all(), Duration.ZERO, 50,
                                                      Set.of(STATUS, PROBLEMS)));
            assertEquals(detail, service.getActiveProblems(AgentSelector.all(), 50).problems().getFirst().detail());
            // A status read does not consume full messages, even after its cursor advances.
            assertEquals(detail, service.getLogs(baseline, AgentSelector.all(), 50).events().getFirst().message());
            assertEquals("failed: " + detail, service.getLogs(baseline, AgentSelector.all(), 50)
                    .events().getLast().message());
        }
    }

    @Test
    void allTypeCombinationsPageWithoutDuplicatesAndKeepProblemCount(@TempDir Path directory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(directory));
        try (DevLogStore store = new DevLogStore(directory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentCursor baseline = service.getStatus().cursor();
            store.process("app", "application", "orders", "orders-1", "stdout", "INFO raw output");
            store.observeStatus("compile", "build", "orders", null, "failed", "compiler detail");
            store.observeStatus("test", "test", "orders", null, "passed", "test detail");
            store.process("app", "application", "orders", "orders-1", "stderr", "WARN log problem");
            for (int mask = 1; mask < 8; mask++) {
                Set<AgentChangeType> types = EnumSet.noneOf(AgentChangeType.class);
                for (AgentChangeType type : values()) {
                    if ((mask & (1 << type.ordinal())) != 0) {
                        types.add(type);
                    }
                }
                List<DevLogEvent> events = new ArrayList<>();
                List<AgentProblemChange> problems = new ArrayList<>();
                AgentCursor cursor = baseline;
                AgentChange page;
                int pages = 0;
                do {
                    page = service.waitForChange(cursor, AgentSelector.all(), Duration.ZERO, 1, types);
                    assertEquals(page, service.waitForChange(cursor, AgentSelector.all(), Duration.ZERO, 1, types));
                    events.addAll(page.events());
                    problems.addAll(page.problemChanges());
                    assertEquals(2, page.activeProblemCount(), types.toString());
                    assertTrue(page.cursor().sequence() > cursor.sequence());
                    cursor = page.cursor();
                    assertTrue(++pages <= 4, "pagination must advance");
                } while (page.hasMore());
                assertEquals(types.contains(LOGS) ? List.of(1L, 2L, 3L, 4L)
                                     : types.contains(STATUS) ? List.of(2L, 3L) : List.of(),
                             events.stream().map(DevLogEvent::sequence).toList(), types.toString());
                assertEquals(types.contains(PROBLEMS) ? List.of(2L, 4L) : List.of(),
                             problems.stream().map(AgentProblemChange::sequence).toList(), types.toString());
                if (types.contains(LOGS)) {
                    assertEquals("failed: compiler detail", events.get(1).message());
                } else if (types.contains(STATUS)) {
                    assertEquals(List.of("failed", "passed"), events.stream().map(DevLogEvent::message).toList());
                }
            }
        }
    }

    @Test
    void defaultTimeoutAdvancesPastNoiseAndCanReplayItAsLogs(@TempDir Path directory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(directory));
        try (DevLogStore store = new DevLogStore(directory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentCursor baseline = service.getStatus().cursor();
            for (int i = 0; i < 60; i++) {
                store.process("app", "application", "orders", "orders-1", "stdout", "INFO noise " + i);
            }
            AgentChange quiet = service.waitForChange(baseline, AgentSelector.all(), Duration.ofMillis(10), 1);
            assertTrue(quiet.timedOut());
            assertTrue(quiet.events().isEmpty());
            assertTrue(quiet.problemChanges().isEmpty());
            assertFalse(quiet.hasMore());
            assertEquals(60, quiet.cursor().sequence());
            AgentChange logs = service.waitForChange(baseline, AgentSelector.all(), Duration.ZERO, 1, Set.of(LOGS));
            assertEquals("INFO noise 0", logs.events().getFirst().message());
            assertTrue(logs.hasMore());
            assertTrue(service.waitForChange(quiet.cursor(), AgentSelector.all(), Duration.ZERO, 1, Set.of(LOGS))
                               .timedOut());
            store.observeStatus("reload", "deployment", "orders", null, "running", "replacement ready");
            assertEquals("running", service.waitForChange(quiet.cursor(), AgentSelector.all(), Duration.ZERO, 1)
                    .events().getFirst().message());
        }
    }

    @Test
    void selectorsAndSessionReplacementStillApplyWithoutProblemTransitions(@TempDir Path directory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(directory));
        try (DevLogStore store = new DevLogStore(directory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            AgentCursor baseline = service.getStatus().cursor();
            store.observeStatus("app", "application", "billing", "billing-1", "failed", "billing detail");
            store.observeStatus("app", "application", "orders", "orders-1", "failed", "orders detail");
            AgentSelector selector = new AgentSelector(Set.of("orders"), Set.of("orders-1"), Set.of("app"),
                                                       DevLogEvent.Level.ERROR);
            for (Set<AgentChangeType> types : List.of(Set.of(STATUS), Set.of(LOGS))) {
                AgentChange change = service.waitForChange(baseline, selector, Duration.ZERO, 1, types);
                assertEquals(List.of("orders"), change.events().stream().map(DevLogEvent::serviceId).toList());
                assertTrue(change.problemChanges().isEmpty());
                assertEquals(1, change.activeProblemCount());
                assertFalse(change.hasMore());
                AgentChange replaced = service.waitForChange(new AgentCursor("previous", 99), selector,
                                                            Duration.ZERO, 1, types);
                assertTrue(replaced.sessionChanged());
                assertEquals(session.sessionId(), replaced.cursor().sessionId());
                assertTrue(replaced.events().isEmpty());
                assertTrue(replaced.problemChanges().isEmpty());
                assertEquals(1, replaced.activeProblemCount());
            }
        }
    }
}
