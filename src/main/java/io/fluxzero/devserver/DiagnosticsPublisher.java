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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** A single writer with one dirty bit, regardless of producer rate or snapshot duration. */
final class DiagnosticsPublisher implements AutoCloseable {
    private final Supplier<DevDiagnostics> snapshot;
    private final Writer writer;
    private final Runnable published;
    private final Duration closeTimeout;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("fluxzero-dev-diagnostics").factory());
    private final ScheduledFuture<?> periodic;
    private volatile RuntimeException failure;
    private boolean closing;

    DiagnosticsPublisher(Supplier<DevDiagnostics> snapshot, Writer writer, Runnable published) {
        this(snapshot, writer, published, Duration.ofMillis(250), Duration.ofSeconds(5));
    }

    DiagnosticsPublisher(Supplier<DevDiagnostics> snapshot, Writer writer, Runnable published,
                         Duration interval, Duration closeTimeout) {
        this.snapshot = snapshot;
        this.writer = writer;
        this.published = published;
        this.closeTimeout = closeTimeout;
        periodic = executor.scheduleWithFixedDelay(this::publish, interval.toNanos(), interval.toNanos(),
                                                  TimeUnit.NANOSECONDS);
    }

    void changed() {
        dirty.set(true);
    }

    private void publish() {
        if (!dirty.getAndSet(false)) {
            return;
        }
        try {
            writer.write(snapshot.get());
            failure = null;
        } catch (IOException | RuntimeException e) {
            if (failure == null) {
                // Do not feed a persistence error back through the embedded log capture.
                System.err.println("Failed to write Fluxzero dev diagnostics; retrying: " + e);
            }
            failure = new IllegalStateException("Failed to write Fluxzero dev diagnostics", e);
            dirty.set(true);
            return;
        }
        try {
            published.run();
        } catch (RuntimeException ignored) {
            // Optional observers must not terminate periodic persistence.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closing) {
                closing = true;
                periodic.cancel(false);
                // Accepted mutations are complete before close; always publish the final cursor, even with no problems.
                dirty.set(true);
                executor.execute(this::publish);
                executor.shutdown();
            }
        }
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    executor.shutdownNow();
                    throw flushFailure("Timed out flushing Fluxzero dev diagnostics");
                }
                try {
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    // Shutdown often follows interruption. Still give the final snapshot its bounded flush window.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IllegalStateException flushFailure(String message) {
        System.err.println(message);
        return new IllegalStateException(message);
    }

    @FunctionalInterface
    interface Writer {
        void write(DevDiagnostics diagnostics) throws IOException;
    }
}
