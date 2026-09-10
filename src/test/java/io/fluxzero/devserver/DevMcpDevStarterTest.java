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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DevMcpDevStarterTest {
    @TempDir Path directory;

    @Test
    void coalescesExplicitStartsAndCancelsPendingWorkOnClose() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var starter = new DevMcpDevStarter(directory, () -> {
            calls.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); return 0; }
            catch (InterruptedException e) { interrupted.countDown(); throw e; }
        }, Duration.ofSeconds(30))) {
            assertTrue(starter.status().isEmpty());
            starter.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            starter.start();
            assertEquals(1, calls.get());
            assertEquals("dev-server-starting", starter.status().get("status"));
        }
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
    }

    @Test
    void timesOutWithoutStartingAgainFromStatus() throws Exception {
        var interrupted = new CountDownLatch(1);
        try (var starter = new DevMcpDevStarter(directory, () -> {
            try { new CountDownLatch(1).await(); return 0; }
            catch (InterruptedException e) { interrupted.countDown(); throw e; }
        }, Duration.ofMillis(100))) {
            starter.start();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals("dev-server-start-failed", starter.status().get("status"));
        }
    }

    @Test
    void preservesDocumentationAndAllowsExplicitRetryAfterFailure() throws Exception {
        var calls = new AtomicInteger();
        try (var starter = new DevMcpDevStarter(directory, () -> {
            calls.incrementAndGet(); throw new IllegalStateException("secret-fixture");
        }, Duration.ofSeconds(30))) {
            starter.start();
            awaitFailure(starter);
            assertEquals(true, starter.status().get("documentationAvailable"));
            assertFalse(starter.status().toString().contains("secret-fixture"));
            starter.start();
            awaitFailure(starter);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void observedReadyDoesNotTurnExplicitStopIntoFailedStartup() throws Exception {
        try (var starter = new DevMcpDevStarter(directory, () -> 0, Duration.ofSeconds(30))) {
            starter.start();
            starter.observedReady();
            assertTrue(starter.status().isEmpty());
        }
    }

    @Test
    void closedBridgeCannotStartDevelopment() {
        var starter = new DevMcpDevStarter(directory);
        starter.close();
        assertThrows(IllegalStateException.class, starter::start);
    }

    private void awaitFailure(DevMcpDevStarter starter) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ("dev-server-starting".equals(starter.status().get("status")) && System.nanoTime() < deadline)
            TimeUnit.MILLISECONDS.sleep(10);
        assertEquals("dev-server-start-failed", starter.status().get("status"));
    }
}
