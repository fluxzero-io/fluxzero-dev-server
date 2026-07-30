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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class DevServerLifetime implements AutoCloseable {
    private final Duration idleTimeout;
    private final Duration failedStartupTimeout;
    private final BooleanSupplier ready;
    private final Consumer<String> shutdownRequest;
    private final LongSupplier clock;
    private final DevActivityStore activityStore;
    private final AtomicLong lastActivityAt;
    private final AtomicBoolean expired = new AtomicBoolean();
    private volatile ScheduledFuture<?> task;

    DevServerLifetime(DevServerConfig config, BooleanSupplier ready, Consumer<String> shutdownRequest) {
        this(config.idleTimeout(), config.failedStartupTimeout(), ready, shutdownRequest,
             System::currentTimeMillis, new DevActivityStore(config.projectDirectory()));
    }

    DevServerLifetime(Duration idleTimeout, Duration failedStartupTimeout, BooleanSupplier ready,
                      Consumer<String> shutdownRequest, LongSupplier clock, DevActivityStore activityStore) {
        this.idleTimeout = idleTimeout;
        this.failedStartupTimeout = failedStartupTimeout;
        this.ready = ready;
        this.shutdownRequest = shutdownRequest;
        this.clock = clock;
        this.activityStore = activityStore;
        this.lastActivityAt = new AtomicLong(clock.getAsLong());
    }

    void start(ScheduledExecutorService scheduler) {
        activityStore.touch();
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
        Duration timeout = ready.getAsBoolean() ? idleTimeout : failedStartupTimeout;
        if (timeout.isZero()) {
            return;
        }
        long lastActivity = Math.max(lastActivityAt.get(), activityStore.lastActivityAt(0));
        long idleMillis = Math.max(0, clock.getAsLong() - lastActivity);
        if (idleMillis >= timeout.toMillis() && expired.compareAndSet(false, true)) {
            String state = ready.getAsBoolean() ? "idle" : "not ready";
            shutdownRequest.accept("dev environment was " + state + " for " + display(timeout));
        }
    }

    private long checkIntervalMillis() {
        long shortest = java.util.stream.Stream.of(idleTimeout, failedStartupTimeout)
                .filter(timeout -> !timeout.isZero()).mapToLong(Duration::toMillis).min().orElse(1_000);
        return Math.max(250, Math.min(30_000, shortest / 4));
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
