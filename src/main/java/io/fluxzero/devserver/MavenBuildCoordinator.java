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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Coordinates Maven processes that share a project's mutable {@code target} directory.
 */
final class MavenBuildCoordinator implements AutoCloseable {
    private static final Duration TEST_CANCELLATION_TIMEOUT = Duration.ofSeconds(2);

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition compileFinished = lock.newCondition();
    private final AtomicBoolean compilePending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Process> activeTest = new AtomicReference<>();
    private final AtomicReference<Process> cancelledTest = new AtomicReference<>();

    <T> T withCompileLock(InterruptibleSupplier<T> action) throws Exception {
        compilePending.set(true);
        Process test = activeTest.get();
        if (test != null && test.isAlive() && cancelledTest.compareAndSet(null, test)) {
            ProcessUtils.stopTree(test, TEST_CANCELLATION_TIMEOUT);
        }
        boolean locked = false;
        try {
            lock.lockInterruptibly();
            locked = true;
            if (closed.get()) {
                throw new InterruptedException("Maven build coordinator is closed");
            }
            return action.get();
        } finally {
            if (locked) {
                compilePending.set(false);
                compileFinished.signalAll();
                lock.unlock();
            } else {
                lock.lock();
                try {
                    compilePending.set(false);
                    compileFinished.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    TestRun runTest(List<String> command, Path directory, Map<String, String> environment,
                    Consumer<String> output) throws Exception {
        lock.lockInterruptibly();
        AtomicReference<Process> started = new AtomicReference<>();
        try {
            while (compilePending.get()) {
                compileFinished.await();
            }
            if (closed.get()) {
                throw new InterruptedException("Maven build coordinator is closed");
            }
            ProcessUtils.ProcessResult result = ProcessUtils.run(command, directory, environment, output, value -> {
                started.set(value);
                activeTest.set(value);
                if (compilePending.get() && cancelledTest.compareAndSet(null, value)) {
                    ProcessUtils.stopTree(value, TEST_CANCELLATION_TIMEOUT);
                }
            });
            Process process = started.get();
            boolean cancelled = process != null && cancelledTest.compareAndSet(process, null);
            return new TestRun(result, cancelled);
        } finally {
            Process process = started.get();
            if (process != null) {
                activeTest.compareAndSet(process, null);
                cancelledTest.compareAndSet(process, null);
            }
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Process process = activeTest.get();
        if (process != null) {
            ProcessUtils.forceStopTree(process);
        }
    }

    record TestRun(ProcessUtils.ProcessResult result, boolean cancelledByCompile) {
    }

    @FunctionalInterface
    interface InterruptibleSupplier<T> {
        T get() throws Exception;
    }
}
