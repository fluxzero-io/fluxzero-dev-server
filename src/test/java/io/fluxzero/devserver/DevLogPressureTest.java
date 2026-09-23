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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevLogPressureTest {
    @Test
    void drainsRealChildWhileItServesRequestsAndPreservesHistory(@TempDir Path directory) throws Exception {
        qualify(directory, 40, 50, true);
    }

    // Also usable as an isolated before/after qualification with the same child and workload.
    public static void main(String[] args) throws Exception {
        qualify(Path.of(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                Boolean.parseBoolean(args[3]));
    }

    private static void qualify(Path directory, int requests, int linesPerRequest, boolean verifyGrouping)
            throws Exception {
        Files.createDirectories(directory);
        CompletableFuture<Integer> port = new CompletableFuture<>();
        CompletableFuture<Void> drained = new CompletableFuture<>();
        AtomicLong firstReceived = new AtomicLong();
        AtomicLong lastReceived = new AtomicLong();
        AtomicInteger publications = new AtomicInteger();
        List<Long> latencies = new ArrayList<>();
        List<Long> childWrites = new ArrayList<>();
        long heapBefore = usedHeap();
        long peakHeap = heapBefore;
        long heapAfter;
        long childPeakHeap;
        long childRetainedHeap;
        int total = requests * linesPerRequest;
        Path diagnostics;
        int active;
        try (DevLogStore store = new DevLogStore(directory, "pressure", "orders", 128L * 1024 * 1024, 2);
             var ignored = store.onDiagnosticsChanged(publications::incrementAndGet)) {
            diagnostics = store.diagnosticsFile();
            Process child = ProcessUtils.startWithStreams(javaCommand(), directory, Map.of(), output -> {
                try {
                    if (output.line().startsWith("PORT ")) {
                        port.complete(Integer.parseInt(output.line().substring(5)));
                        return;
                    }
                    firstReceived.compareAndSet(0, System.nanoTime());
                    store.process("app", "application", "orders", "orders-1", output.stream(), output.line());
                    lastReceived.set(System.nanoTime());
                    if (output.line().equals("INFO finished")) drained.complete(null);
                } catch (Throwable failure) {
                    port.completeExceptionally(failure);
                    drained.completeExceptionally(failure);
                }
            });
            try {
                try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port.get(10, TimeUnit.SECONDS));
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    socket.setSoTimeout(30_000);
                    for (int i = 0; i < requests; i++) {
                        long start = System.nanoTime();
                        output.writeInt(linesPerRequest);
                        output.flush();
                        childWrites.add(input.readLong());
                        latencies.add(System.nanoTime() - start);
                        peakHeap = Math.max(peakHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    }
                    output.writeInt(0);
                    output.flush();
                    childPeakHeap = input.readLong();
                    childRetainedHeap = input.readLong();
                }
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
                drained.get(10, TimeUnit.SECONDS);
            } finally {
                ProcessUtils.forceStopTree(child);
            }
            active = store.diagnostics().activeCount();
            assertEquals(total + 1, store.lastSequence());
            if (verifyGrouping) {
                assertEquals(1, active);
                assertEquals(total, store.diagnostics().problems().getFirst().occurrences());
                assertTrue(publications.get() < total / 10, "snapshot notifications must be coalesced");
            }
            // Check every event and occurrence transition after measuring throughput.
            long cursor = 0;
            int observations = 0;
            while (true) {
                var page = store.readAgentChanges(cursor, 500, event -> true, problem -> true);
                for (DevLogEvent event : page.events()) assertEquals(++cursor, event.sequence());
                observations += page.problemChanges().size();
                if (!page.hasMore()) break;
            }
            assertEquals(total + 1, cursor);
            assertEquals(total, observations);
            assertEquals(total + 1, Files.readAllLines(store.combinedLog()).size());
            assertEquals(total, Files.readAllLines(store.problemsFile()).size());
            heapAfter = usedHeap();
            assertEquals(active, store.diagnostics().activeCount());
        }
        var persisted = new ObjectMapper().readTree(diagnostics.toFile());
        assertEquals(verifyGrouping ? total + 1 : total, persisted.path("lastEventSequence").asLong());
        assertEquals(active, persisted.path("activeCount").asInt());
        latencies.sort(Long::compareTo);
        childWrites.sort(Long::compareTo);
        double elapsedSeconds = (lastReceived.get() - firstReceived.get()) / 1_000_000_000.0;
        System.out.printf(java.util.Locale.ROOT,
                          "PRESSURE logs=%d seconds=%.3f logsPerSecond=%.0f requestP95Ms=%.3f requestMaxMs=%.3f "
                          + "childWriteP95Ms=%.3f childWriteMaxMs=%.3f heapBeforeMiB=%.2f heapAfterMiB=%.2f "
                          + "sampledPeakHeapMiB=%.2f childPeakHeapMiB=%.2f childRetainedHeapMiB=%.2f "
                          + "activeProblems=%d publications=%d%n",
                          total, elapsedSeconds, total / elapsedSeconds, millis(percentile(latencies)),
                          millis(latencies.getLast()), millis(percentile(childWrites)), millis(childWrites.getLast()),
                          mib(heapBefore), mib(heapAfter), mib(peakHeap), mib(childPeakHeap), mib(childRetainedHeap),
                          active, publications.get());
    }

    private static long percentile(List<Long> values) { return values.get((int) Math.ceil(values.size() * .95) - 1); }
    private static double millis(long value) { return value / 1_000_000.0; }
    private static double mib(long value) { return value / (1024.0 * 1024); }
    private static long usedHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static List<String> javaCommand() {
        return List.of(Path.of(System.getProperty("java.home"), "bin",
                               ProcessUtils.isWindows() ? "java.exe" : "java").toString(),
                       "-Xmx128m", "-cp", System.getProperty("java.class.path"), Child.class.getName());
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                System.out.println("PORT " + server.getLocalPort());
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    int count;
                    while ((count = input.readInt()) > 0) {
                        long start = System.nanoTime();
                        for (int i = 0; i < count; i++) {
                            System.out.println(Instant.now() + " [worker-" + i
                                               + "] WARN example.Handler - request " + UUID.randomUUID()
                                               + " rejected: inventory unavailable");
                        }
                        output.writeLong(System.nanoTime() - start);
                        output.flush();
                    }
                    System.out.println("INFO finished");
                    output.writeLong(ManagementFactory.getMemoryPoolMXBeans().stream()
                                             .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                                             .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum());
                    output.writeLong(usedHeap());
                    output.flush();
                }
            }
        }
    }
}
