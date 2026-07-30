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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class DevServerLifetime implements AutoCloseable {
    private final Duration idleTimeout;
    private final Consumer<String> shutdownRequest;
    private final LongSupplier clock;
    private final DevActivityStore activityStore;
    private final AtomicLong lastActivityAt;
    private final AtomicBoolean expired = new AtomicBoolean();
    private volatile ScheduledFuture<?> task;

    DevServerLifetime(DevServerConfig config, Consumer<String> shutdownRequest) {
        this(config.idleTimeout(), shutdownRequest,
             System::currentTimeMillis, new DevActivityStore(config.projectDirectory()));
    }

    DevServerLifetime(Duration idleTimeout, Consumer<String> shutdownRequest, LongSupplier clock,
                      DevActivityStore activityStore) {
        this.idleTimeout = idleTimeout;
        this.shutdownRequest = shutdownRequest;
        this.clock = clock;
        this.activityStore = activityStore;
        this.lastActivityAt = new AtomicLong(clock.getAsLong());
    }

    void start(ScheduledExecutorService scheduler) {
        activityStore.touch();
        if (idleTimeout.isZero()) {
            return;
        }
        long interval = checkIntervalMillis();
        task = scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }

    void activity() {
        lastActivityAt.set(clock.getAsLong());
    }

    void check() {
        if (expired.get()) {
            return;
        }
        if (idleTimeout.isZero()) {
            return;
        }
        long lastActivity = Math.max(lastActivityAt.get(), activityStore.lastActivityAt(0));
        long idleMillis = Math.max(0, clock.getAsLong() - lastActivity);
        if (idleMillis >= idleTimeout.toMillis() && expired.compareAndSet(false, true)) {
            shutdownRequest.accept("dev environment was idle for " + display(idleTimeout));
        }
    }

    private long checkIntervalMillis() {
        return Math.max(250, Math.min(30_000, idleTimeout.toMillis() / 4));
    }

    private static String display(Duration duration) {
        if (duration.toDays() > 0 && duration.minusDays(duration.toDays()).isZero()) {
            return duration.toDays() + "d";
        }
        if (duration.toHours() > 0 && duration.minusHours(duration.toHours()).isZero()) {
            return duration.toHours() + "h";
        }
        if (duration.toMinutes() > 0 && duration.minusMinutes(duration.toMinutes()).isZero()) {
            return duration.toMinutes() + "m";
        }
        if (duration.toSeconds() > 0 && duration.minusSeconds(duration.toSeconds()).isZero()) {
            return duration.toSeconds() + "s";
        }
        return duration.toMillis() + "ms";
    }

    @Override
    public void close() {
        ScheduledFuture<?> current = task;
        if (current != null) {
            current.cancel(true);
        }
    }
}
