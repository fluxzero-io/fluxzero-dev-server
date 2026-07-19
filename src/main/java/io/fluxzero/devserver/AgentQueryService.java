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

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Transport-independent, read-only query API for coding agents.
 */
public final class AgentQueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private final Supplier<DevSession> sessionSupplier;
    private final DevLogStore logStore;

    AgentQueryService(Supplier<DevSession> sessionSupplier, DevLogStore logStore) {
        this.sessionSupplier = sessionSupplier;
        this.logStore = logStore;
    }

    public AgentStatus getStatus() {
        DevDiagnostics diagnostics = logStore.diagnostics();
        return new AgentStatus(currentCursor(), sessionSupplier.get(), diagnostics.activeCount(),
                               diagnostics.errors(), diagnostics.warnings());
    }

    public AgentProblemPage getActiveProblems(AgentSelector selector, int requestedLimit) {
        AgentSelector effectiveSelector = selector == null ? AgentSelector.all() : selector;
        int limit = limit(requestedLimit);
        List<DevProblem> matching = logStore.diagnostics().problems().stream()
                .filter(effectiveSelector::matches)
                .limit(limit + 1L)
                .toList();
        boolean truncated = matching.size() > limit;
        return new AgentProblemPage(currentCursor(), truncated ? matching.subList(0, limit) : matching, truncated);
    }

    public AgentLogPage getLogs(AgentCursor cursor, AgentSelector selector, int requestedLimit) {
        AgentCursor current = currentCursor();
        if (cursor != null && !current.sessionId().equals(cursor.sessionId())) {
            return new AgentLogPage(current, List.of(), true, false);
        }
        long afterSequence = cursor == null ? 0 : cursor.sequence();
        return logPage(afterSequence, selector, requestedLimit, false);
    }

    public AgentTestStatus getTestStatus() {
        DevSession session = sessionSupplier.get();
        return new AgentTestStatus(currentCursor(), session.tests(), session.commands());
    }

    public AgentChange waitForChange(AgentCursor cursor, AgentSelector selector, Duration requestedTimeout,
                                     int requestedLimit) {
        AgentCursor current = currentCursor();
        if (cursor != null && !current.sessionId().equals(cursor.sessionId())) {
            return new AgentChange(current, true, false, List.of(), activeProblems(selector, requestedLimit), false);
        }
        Duration timeout = boundedTimeout(requestedTimeout);
        long deadline = System.nanoTime() + timeout.toNanos();
        long scanSequence = cursor == null ? current.sequence() : cursor.sequence();
        while (true) {
            AgentLogPage page = logPage(scanSequence, selector, requestedLimit, false);
            if (!page.events().isEmpty()) {
                return new AgentChange(page.cursor(), false, false, page.events(),
                                       activeProblems(selector, requestedLimit), page.hasMore());
            }
            scanSequence = Math.max(scanSequence, logStore.lastSequence());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !logStore.awaitEventAfter(scanSequence, Duration.ofNanos(remaining))) {
                return new AgentChange(new AgentCursor(current.sessionId(), logStore.lastSequence()), false, true,
                                       List.of(), activeProblems(selector, requestedLimit), false);
            }
        }
    }

    private AgentLogPage logPage(long afterSequence, AgentSelector selector, int requestedLimit,
                                 boolean sessionChanged) {
        AgentSelector effectiveSelector = selector == null ? AgentSelector.all() : selector;
        int limit = limit(requestedLimit);
        List<DevLogEvent> matching = logStore.readEvents(afterSequence, limit + 1, effectiveSelector::matches);
        boolean hasMore = matching.size() > limit;
        List<DevLogEvent> events = hasMore ? matching.subList(0, limit) : matching;
        long nextSequence = hasMore || !events.isEmpty() ? events.getLast().sequence() : logStore.lastSequence();
        return new AgentLogPage(new AgentCursor(logStore.sessionId(), nextSequence), events, sessionChanged, hasMore);
    }

    private List<DevProblem> activeProblems(AgentSelector selector, int requestedLimit) {
        return getActiveProblems(selector, requestedLimit).problems();
    }

    private AgentCursor currentCursor() {
        return new AgentCursor(logStore.sessionId(), logStore.lastSequence());
    }

    private static int limit(int requestedLimit) {
        int candidate = requestedLimit <= 0 ? DEFAULT_LIMIT : requestedLimit;
        return Math.min(candidate, MAX_LIMIT);
    }

    private static Duration boundedTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            return Duration.ZERO;
        }
        return timeout.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : timeout;
    }
}
