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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
            assertEquals(1, service.getActiveProblems(orders, 10).problems().size());
        }
    }

    @Test
    void capsPageSizeAndReportsMoreResults(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            for (int index = 0; index < AgentQueryService.MAX_LIMIT + 1; index++) {
                store.accept("event " + index);
            }

            AgentLogPage page = service.getLogs(null, AgentSelector.all(), Integer.MAX_VALUE);

            assertEquals(AgentQueryService.MAX_LIMIT, page.events().size());
            assertTrue(page.hasMore());
            assertEquals(AgentQueryService.MAX_LIMIT, page.cursor().sequence());
        }
    }

    @Test
    void rejectsCursorFromReplacedSessionWithoutReadingOldEvents(@TempDir Path projectDirectory) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService service = new AgentQueryService(() -> session, store);
            store.accept("current session event");

            AgentLogPage page = service.getLogs(new AgentCursor("old-session", 99), AgentSelector.all(), 10);
            AgentChange change = service.waitForChange(new AgentCursor("old-session", 99), AgentSelector.all(),
                                                       Duration.ofSeconds(1), 10);

            assertTrue(page.sessionChanged());
            assertTrue(page.events().isEmpty());
            assertEquals(session.sessionId(), page.cursor().sessionId());
            assertTrue(change.sessionChanged());
            assertFalse(change.timedOut());
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
}
