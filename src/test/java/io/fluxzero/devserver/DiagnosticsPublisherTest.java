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

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsPublisherTest {
    @Test
    void coalescesAnArbitraryBurstDuringBlockedIoAndFlushesLatestState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong sequence = new AtomicLong();
        AtomicLong persisted = new AtomicLong();
        AtomicInteger writes = new AtomicInteger();
        DiagnosticsPublisher publisher = new DiagnosticsPublisher(() -> snapshot(sequence.get()), snapshot -> {
            if (writes.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
            persisted.set(snapshot.lastEventSequence());
        }, () -> {}, Duration.ofMillis(10), Duration.ofSeconds(2));
        try {
            publisher.changed();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 100_000; i++) {
                sequence.incrementAndGet();
                publisher.changed();
            }
            assertEquals(1, writes.get());
        } finally {
            release.countDown();
            publisher.close();
        }
        assertEquals(100_000, persisted.get());
        assertTrue(writes.get() <= 3, "burst must not enqueue one snapshot per mutation");
    }

    @Test
    void retriesFailedIoWithoutAnotherEventAndSurvivesBrokenObservers() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        try (DiagnosticsPublisher publisher = new DiagnosticsPublisher(() -> snapshot(7), snapshot -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("simulated disk failure");
            recovered.countDown();
        }, () -> { throw new IllegalStateException("disconnected"); }, Duration.ofMillis(10), Duration.ofSeconds(2))) {
            publisher.changed();
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
        }
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void interruptedShutdownStillFlushesAndRestoresInterrupt() {
        AtomicLong persisted = new AtomicLong();
        DiagnosticsPublisher publisher = new DiagnosticsPublisher(() -> snapshot(42),
                snapshot -> persisted.set(snapshot.lastEventSequence()), () -> {},
                Duration.ofDays(1), Duration.ofSeconds(2));
        Thread.currentThread().interrupt();
        try {
            publisher.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(42, persisted.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reportsFailureOfFinalFlush() {
        DiagnosticsPublisher publisher = new DiagnosticsPublisher(() -> snapshot(1), snapshot -> {
            throw new IOException("simulated disk failure");
        }, () -> {}, Duration.ofDays(1), Duration.ofSeconds(2));
        assertThrows(IllegalStateException.class, publisher::close);
    }

    @Test
    void boundsShutdownWhenWriterIsStuck() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        DiagnosticsPublisher publisher = new DiagnosticsPublisher(() -> snapshot(1), snapshot -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }, () -> {}, Duration.ofMillis(10), Duration.ofMillis(100));
        publisher.changed();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                                  () -> assertThrows(IllegalStateException.class, publisher::close));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    private static DevDiagnostics snapshot(long sequence) {
        return new DevDiagnostics("session", 0, 0, 0, List.of(), sequence, 0);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
