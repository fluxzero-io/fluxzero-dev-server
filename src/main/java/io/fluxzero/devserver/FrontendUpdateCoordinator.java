/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class FrontendUpdateCoordinator implements AutoCloseable {
    private final long quietPeriodMillis;
    private final ScheduledExecutorService scheduler;
    private final Consumer<Set<String>> pendingConsumer;
    private final Consumer<Set<String>> settledConsumer;
    private final Set<String> activeBuilds = new LinkedHashSet<>();
    private final Set<String> pendingFrontends = new LinkedHashSet<>();

    private ScheduledFuture<?> settlement;
    private boolean closed;

    FrontendUpdateCoordinator(Duration quietPeriod, ScheduledExecutorService scheduler,
                              Consumer<Set<String>> pendingConsumer, Consumer<Set<String>> settledConsumer) {
        this.quietPeriodMillis = Math.max(1, quietPeriod.toMillis());
        this.scheduler = scheduler;
        this.pendingConsumer = pendingConsumer;
        this.settledConsumer = settledConsumer;
    }

    synchronized void buildStarted(String projectId) {
        if (closed) {
            return;
        }
        cancelSettlement();
        activeBuilds.add(projectId);
    }

    void frontendFilesChanged(Set<String> frontendIds) {
        Set<String> newlyPending;
        synchronized (this) {
            if (closed || frontendIds.isEmpty() || activeBuilds.isEmpty() && settlement == null) {
                return;
            }
            newlyPending = new LinkedHashSet<>(frontendIds);
            newlyPending.removeAll(pendingFrontends);
            pendingFrontends.addAll(frontendIds);
            if (activeBuilds.isEmpty()) {
                scheduleSettlement();
            }
        }
        if (!newlyPending.isEmpty()) {
            pendingConsumer.accept(Set.copyOf(newlyPending));
        }
    }

    synchronized void buildCompleted(String projectId, boolean successful) {
        if (closed) {
            return;
        }
        activeBuilds.remove(projectId);
        if (!activeBuilds.isEmpty()) {
            return;
        }
        if (successful) {
            scheduleSettlement();
        } else {
            cancelSettlement();
        }
    }

    private synchronized void scheduleSettlement() {
        cancelSettlement();
        settlement = scheduler.schedule(this::settle, quietPeriodMillis, TimeUnit.MILLISECONDS);
    }

    private void settle() {
        Set<String> frontends;
        synchronized (this) {
            if (closed || !activeBuilds.isEmpty()) {
                return;
            }
            settlement = null;
            frontends = Set.copyOf(pendingFrontends);
            pendingFrontends.clear();
        }
        if (!frontends.isEmpty()) {
            settledConsumer.accept(frontends);
        }
    }

    private synchronized void cancelSettlement() {
        if (settlement != null) {
            settlement.cancel(false);
            settlement = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelSettlement();
        activeBuilds.clear();
        pendingFrontends.clear();
    }
}
