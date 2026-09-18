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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DevConsoleProjectStarterTest {
    @Test
    void coalescesConcurrentStartsAndReturnsOnlyAReadyEnvironment(@TempDir Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var project = directory.resolve("project with spaces");
        var session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("stopped");
        var store = new DevSessionStore(project);
        store.writeSession(session);
        registry.register(session);
        var known = registry.listKnown().getFirst();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var starter = new DevConsoleProjectStarter(registry, root -> {
            assertEquals(project.toRealPath(), root);
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            store.writeSession(DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                    .withGateway(DevSession.ServiceStatus.running("gateway", "http://localhost:4200", 4200, null, "public").withMetadata(java.util.Map.of(DevConsole.CAPABILITY, "1"))));
            return 0;
        })) {
            var first = starter.start(known.id());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertSame(first, starter.start(known.id()));
            assertFalse(first.isDone());
            release.countDown();
            assertEquals("http://localhost:4200/_fluxzero/dev/", first.get(5, TimeUnit.SECONDS).consoleUrl());
            assertEquals("running", starter.start(known.id()).get(5, TimeUnit.SECONDS).status());
            assertEquals(1, calls.get());
            assertThrows(IllegalArgumentException.class, () -> starter.start("0".repeat(64)));
        } finally { release.countDown(); }
    }

    @Test
    void boundsStartupAndInterruptsThePendingBootstrap(@TempDir Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var project = directory.resolve("orders");
        java.nio.file.Files.createDirectories(project);
        registry.register(DevSession.empty(DevServerConfig.defaults(project)).withStatus("stopped"));
        var interrupted = new CountDownLatch(1);
        try (var starter = new DevConsoleProjectStarter(registry, root -> {
            try { new CountDownLatch(1).await(); return 0; }
            catch (InterruptedException e) { interrupted.countDown(); throw e; }
        }, java.time.Duration.ofMillis(500))) {
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> starter.start(registry.listKnown().getFirst().id()).get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsUnavailableFoldersAndReportsFailedLaunches(@TempDir Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var project = directory.resolve("orders");
        registry.register(DevSession.empty(DevServerConfig.defaults(project)).withStatus("stopped"));
        var id = registry.listKnown().getFirst().id();
        try (var starter = new DevConsoleProjectStarter(registry, root -> 2)) {
            assertThrows(IllegalArgumentException.class, () -> starter.start(id));
            java.nio.file.Files.createDirectories(project);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> starter.start(id).get(5, TimeUnit.SECONDS));
        }
        try (var starter = new DevConsoleProjectStarter(registry, root -> 0)) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> starter.start(id).get(5, TimeUnit.SECONDS), "A successful exit without a ready URL is not readiness");
        }
    }
}
