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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendUpdateCoordinatorTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void ignoresOrdinaryFrontendChanges() throws Exception {
        List<Set<String>> pending = new CopyOnWriteArrayList<>();
        List<Set<String>> settled = new CopyOnWriteArrayList<>();

        try (FrontendUpdateCoordinator coordinator = coordinator(pending, settled)) {
            coordinator.frontendFilesChanged(Set.of("ui"));
            Thread.sleep(100);
        }

        assertTrue(pending.isEmpty());
        assertTrue(settled.isEmpty());
    }

    @Test
    void coalescesFrontendChangesPublishedBySuccessfulBuild() throws Exception {
        List<Set<String>> pending = new CopyOnWriteArrayList<>();
        List<Set<String>> settled = new CopyOnWriteArrayList<>();

        try (FrontendUpdateCoordinator coordinator = coordinator(pending, settled)) {
            coordinator.buildStarted("backend");
            coordinator.frontendFilesChanged(Set.of("ui"));
            coordinator.frontendFilesChanged(Set.of("ui", "admin"));
            coordinator.buildCompleted("backend", true);
            coordinator.frontendFilesChanged(Set.of("ui"));

            assertTrue(await(() -> settled.size() == 1));
        }

        assertEquals(List.of(Set.of("ui"), Set.of("admin")), pending);
        assertEquals(List.of(Set.of("ui", "admin")), settled);
    }

    @Test
    void waitsForNextSuccessfulBuildAfterFailure() throws Exception {
        List<Set<String>> pending = new CopyOnWriteArrayList<>();
        List<Set<String>> settled = new CopyOnWriteArrayList<>();

        try (FrontendUpdateCoordinator coordinator = coordinator(pending, settled)) {
            coordinator.buildStarted("backend");
            coordinator.frontendFilesChanged(Set.of("ui"));
            coordinator.buildCompleted("backend", false);
            Thread.sleep(100);
            assertTrue(settled.isEmpty());

            coordinator.buildStarted("backend");
            coordinator.buildCompleted("backend", true);
            assertTrue(await(() -> settled.size() == 1));
        }

        assertEquals(List.of(Set.of("ui")), pending);
        assertEquals(List.of(Set.of("ui")), settled);
    }

    private FrontendUpdateCoordinator coordinator(List<Set<String>> pending, List<Set<String>> settled) {
        return new FrontendUpdateCoordinator(
                Duration.ofMillis(40), scheduler, pending::add, settled::add);
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
