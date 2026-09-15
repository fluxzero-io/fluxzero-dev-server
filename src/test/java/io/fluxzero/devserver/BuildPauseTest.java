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
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildPauseTest {
    @Test void waitsForExistingBuildAndBlocksNewBuildUntilResume() throws Exception {
        try (var coordinator = new MavenBuildCoordinator(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = new CountDownLatch(1); var finish = new CountDownLatch(1);
            var paused = new CountDownLatch(1); var resume = new CountDownLatch(1);
            var first = executor.submit(() -> coordinator.withCompileLock(() -> { active.countDown(); finish.await(); return 1; }));
            assertTrue(active.await(2, TimeUnit.SECONDS));
            var pause = executor.submit(() -> coordinator.withMaintenanceLock(Duration.ofSeconds(3), () -> {
                paused.countDown(); resume.await(); return 2;
            }));
            try {
                assertFalse(paused.await(50, TimeUnit.MILLISECONDS));
                finish.countDown();
                assertEquals(1, first.get(2, TimeUnit.SECONDS));
                assertTrue(paused.await(2, TimeUnit.SECONDS));
                var next = executor.submit(() -> coordinator.withCompileLock(() -> 3));
                assertThrows(TimeoutException.class, () -> next.get(50, TimeUnit.MILLISECONDS));
                resume.countDown();
                assertEquals(2, pause.get(2, TimeUnit.SECONDS));
                assertEquals(3, next.get(2, TimeUnit.SECONDS));
            } finally { finish.countDown(); resume.countDown(); }
        }
    }
}
