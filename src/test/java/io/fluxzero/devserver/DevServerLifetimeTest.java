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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerLifetimeTest {

    @Test
    void usesShorterTimeoutUntilEnvironmentBecomesReady(@TempDir Path project) {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> shutdown = new AtomicReference<>();
        DevServerLifetime lifetime = new DevServerLifetime(
                Duration.ofHours(8), Duration.ofMinutes(10), ready::get, shutdown::set,
                now::get, new DevActivityStore(project));

        now.addAndGet(Duration.ofMinutes(9).toMillis());
        lifetime.check();
        assertNull(shutdown.get());

        now.addAndGet(Duration.ofMinutes(1).toMillis());
        lifetime.check();
        assertTrue(shutdown.get().contains("not ready for 10m"), shutdown.get());
    }

    @Test
    void activityResetsReadyEnvironmentIdleTimeout(@TempDir Path project) {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        AtomicReference<String> shutdown = new AtomicReference<>();
        DevServerLifetime lifetime = new DevServerLifetime(
                Duration.ofHours(8), Duration.ofMinutes(10), () -> true, shutdown::set,
                now::get, new DevActivityStore(project));

        now.addAndGet(Duration.ofHours(7).toMillis());
        lifetime.activity();
        now.addAndGet(Duration.ofHours(7).toMillis());
        lifetime.check();
        assertNull(shutdown.get());

        now.addAndGet(Duration.ofHours(1).toMillis());
        lifetime.check();
        assertTrue(shutdown.get().contains("idle for 8h"), shutdown.get());
    }

    @Test
    void disabledTimeoutNeverRequestsShutdown(@TempDir Path project) {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        AtomicReference<String> shutdown = new AtomicReference<>();
        DevServerLifetime lifetime = new DevServerLifetime(
                Duration.ZERO, Duration.ZERO, () -> true, shutdown::set,
                now::get, new DevActivityStore(project));

        now.addAndGet(Duration.ofDays(100).toMillis());
        lifetime.check();

        assertNull(shutdown.get());
    }
}
