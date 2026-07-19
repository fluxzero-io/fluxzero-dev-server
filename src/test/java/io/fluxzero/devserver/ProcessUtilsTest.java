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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessUtilsTest {

    @Test
    void processTreeStopNeverStopsTheCurrentProcess() {
        ProcessUtils.stopTree(ProcessHandle.current(), Duration.ZERO);

        assertTrue(ProcessHandle.current().isAlive());
    }

    @Test
    void forceStopTreeWaitsUntilParentAndDescendantHaveExited() throws Exception {
        Process process = ProcessUtils.start(
                javaCommand(ProcessTreeFixture.class), Path.of("."), Map.of(), ignored -> {
                });
        ProcessHandle child = awaitDescendant(process, Duration.ofSeconds(2));

        ProcessUtils.forceStopTree(process);

        assertFalse(process.isAlive());
        assertFalse(child.isAlive());
    }

    @Test
    void gracefulStopWaitsForForcedProcessTermination() throws Exception {
        Process process = ProcessUtils.start(
                javaCommand(SleepingFixture.class), Path.of("."), Map.of(), ignored -> {
                });

        ProcessUtils.stopTree(process, Duration.ZERO);

        assertFalse(process.isAlive());
    }

    @Test
    void interruptedRunHardStopsItsProcessBeforeReturning() throws Exception {
        AtomicReference<Process> started = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                ProcessUtils.run(javaCommand(SleepingFixture.class), Path.of("."), Map.of(), ignored -> {
                }, started::set);
                failure.set(new AssertionError("Fixture process exited without interruption"));
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Process process = awaitProcess(started, Duration.ofSeconds(2));

        runner.interrupt();
        runner.join(Duration.ofSeconds(2));

        assertFalse(runner.isAlive());
        assertFalse(process.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("Process runner failed", failure.get());
        }
    }

    @Test
    void completedRunIncludesAllProcessOutput() throws Exception {
        ProcessUtils.ProcessResult result = ProcessUtils.run(
                javaCommand(BurstOutputFixture.class), Path.of("."), Map.of(), ignored -> {
                });

        assertTrue(result.success());
        assertEquals(1_000, result.output().size());
        assertTrue(result.output().contains("stdout-0"));
        assertTrue(result.output().contains("stdout-499"));
        assertTrue(result.output().contains("[stderr] stderr-0"));
        assertTrue(result.output().contains("[stderr] stderr-499"));
    }

    @Test
    void exactProcessStartTimeCanProveOwnershipWithoutACommandMarker() throws Exception {
        Process process = ProcessUtils.start(
                javaCommand(SleepingFixture.class), Path.of("."), Map.of(), ignored -> {
                });
        long startedAt = ProcessUtils.startedAt(process).orElseThrow();

        assertTrue(ProcessUtils.stopIfOwned(
                process.pid(), "marker-that-is-not-present", startedAt, Duration.ZERO));
        assertFalse(process.isAlive());
    }

    @Test
    void mismatchedProcessStartTimeDoesNotProveOwnership() throws Exception {
        Process process = ProcessUtils.start(
                javaCommand(SleepingFixture.class), Path.of("."), Map.of(), ignored -> {
                });
        try {
            long startedAt = ProcessUtils.startedAt(process).orElseThrow();

            assertFalse(ProcessUtils.stopIfOwned(
                    process.pid(), "marker-that-is-not-present", startedAt - 1, Duration.ZERO));
            assertTrue(process.isAlive());
        } finally {
            ProcessUtils.forceStopTree(process);
        }
    }

    private static Process awaitProcess(AtomicReference<Process> started, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Process process = started.get();
            if (process != null) {
                return process;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Fixture process did not start");
    }

    private static ProcessHandle awaitDescendant(Process process, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            ProcessHandle child = process.descendants().findFirst().orElse(null);
            if (child != null) {
                return child;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        ProcessUtils.forceStopTree(process);
        throw new AssertionError("Fixture process did not start its child process");
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                                    System.getProperty("os.name").toLowerCase().contains("win")
                                            ? "java.exe" : "java").toString();
        return List.of(executable, "-cp", System.getProperty("java.class.path"), mainClass.getName());
    }

    public static final class ProcessTreeFixture {
        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(javaCommand(SleepingFixture.class)).start();
            if (!child.isAlive()) {
                throw new IllegalStateException("Child fixture did not start");
            }
            Thread.sleep(Duration.ofMinutes(1));
        }
    }

    public static final class SleepingFixture {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Duration.ofMinutes(1));
        }
    }

    public static final class BurstOutputFixture {
        public static void main(String[] args) {
            for (int i = 0; i < 500; i++) {
                System.out.println("stdout-" + i);
                System.err.println("stderr-" + i);
            }
        }
    }
}
