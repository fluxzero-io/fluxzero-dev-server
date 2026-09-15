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

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JvmHeapMemoryTest {
    @Test void readsItsOwnHeapWithoutAttaching() {
        long pid = ProcessHandle.current().pid();
        try (var memory = new JvmHeapMemory()) {
            var usage = memory.sample(Set.of(pid), Set.of(pid)).get(pid);
            assertNotNull(usage);
            assertTrue(usage.used() > 0);
            assertEquals(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax(), usage.max());
        }
    }

    @Test void readsEffectiveLimitsFromManagedJvmsAndForgetsStoppedProcesses(@TempDir Path directory) throws Exception {
        for (var flags : java.util.List.of(java.util.List.of("-Xms16m", "-Xmx64m"),
                java.util.List.of("-XX:MaxRAM=256m", "-XX:MaxRAMPercentage=50", "-XX:MinRAMPercentage=50"))) {
            Path output = directory.resolve("heap-" + flags.size() + "-" + System.nanoTime() + ".log");
            var command = new java.util.ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", ProcessUtils.isWindows() ? "java.exe" : "java").toString());
            command.addAll(flags);
            command.addAll(java.util.List.of("-cp", Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), HeapFixture.class.getName()));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try (var memory = new JvmHeapMemory()) {
                var usage = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                    while (true) {
                        var reading = memory.sample(Set.of(child.pid()), Set.of()).get(child.pid());
                        if (reading != null && Files.readString(output).contains("MAX=")) return reading;
                        assertTrue(child.isAlive(), () -> "Heap fixture exited: " + output);
                        Thread.sleep(25);
                    }
                });
                long effectiveMax = Files.readAllLines(output).stream().filter(line -> line.startsWith("MAX="))
                        .mapToLong(line -> Long.parseLong(line.substring(4))).findFirst().orElseThrow();
                assertEquals(effectiveMax, usage.max());
                assertTrue(usage.used() >= 8 * 1024 * 1024);
                assertTrue(usage.used() <= usage.max());
                child.destroy();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                assertTrue(memory.sample(Set.of(child.pid()), Set.of()).isEmpty());
            } finally {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void absentProcessesAndClosedSamplerHaveNoInventedLimit() {
        try (var memory = new JvmHeapMemory()) {
            assertTrue(memory.sample(Set.of(Long.MAX_VALUE), Set.of()).isEmpty());
            memory.close();
            assertTrue(memory.sample(Set.of(ProcessHandle.current().pid()), Set.of()).isEmpty());
        }
    }

    public static class HeapFixture {
        static final byte[] RETAINED = new byte[8 * 1024 * 1024];
        public static void main(String[] args) throws Exception {
            System.out.println("MAX=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
            Thread.sleep(60_000);
            System.out.println(RETAINED.length);
        }
    }
}
