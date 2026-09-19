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

import com.sun.tools.attach.VirtualMachine;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reads actual heap usage through local JMX, without blocking console updates on attach or sampling. */
final class JvmHeapMemory implements AutoCloseable {
    // RMI otherwise embeds the current LAN address in the local JMX stub. That address
    // becomes unreachable after a network change, even though the JVM is still running.
    static final String LOCAL_JMX_OPTION = "-Djava.rmi.server.hostname=127.0.0.1";

    record Usage(long used, Long max) {
        static Usage from(MemoryUsage usage) {
            return new Usage(usage.getUsed(), usage.getMax() > 0 ? usage.getMax() : null);
        }
    }
    private record Reading(Usage usage, long at) {}
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("dev-heap-sampler-", 0).factory());
    private final Map<Long, Monitor> monitors = new HashMap<>();
    private boolean closed;

    synchronized Map<Long, Usage> sample(Set<Long> roots, Set<Long> processOnly) {
        if (closed) return Map.of();
        long now = System.nanoTime();
        Map<Long, Set<ProcessHandle>> trees = new HashMap<>();
        Set<Long> active = new HashSet<>();
        for (long root : roots) {
            ProcessHandle.of(root).filter(ProcessHandle::isAlive).ifPresent(process -> {
                Set<ProcessHandle> tree = new HashSet<>();
                if (isJava(process)) tree.add(process);
                if (!processOnly.contains(root)) process.descendants().filter(ProcessHandle::isAlive)
                        .filter(JvmHeapMemory::isJava).forEach(tree::add);
                trees.put(root, tree);
                tree.forEach(p -> active.add(p.pid()));
            });
        }
        for (long pid : new ArrayList<>(monitors.keySet())) {
            if (!active.contains(pid)) retire(monitors.remove(pid));
        }
        Map<Long, Usage> result = new HashMap<>();
        trees.forEach((root, tree) -> {
            long used = 0, max = 0;
            boolean known = !tree.isEmpty(), maximumKnown = true;
            for (ProcessHandle process : tree) {
                Usage usage;
                if (process.pid() == ProcessHandle.current().pid()) {
                    usage = Usage.from(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
                } else {
                    Instant started = process.info().startInstant().orElse(null);
                    Monitor monitor = monitors.get(process.pid());
                    if (monitor != null && !java.util.Objects.equals(monitor.started, started)) {
                        retire(monitors.remove(process.pid()));
                        monitor = null;
                    }
                    if (monitor == null) {
                        monitor = new Monitor(process.pid(), started);
                        monitors.put(process.pid(), monitor);
                    }
                    monitor.schedule(now);
                    Reading reading = monitor.reading;
                    usage = reading == null || now - reading.at() > TimeUnit.SECONDS.toNanos(15) ? null : reading.usage();
                }
                if (usage == null) { known = false; continue; }
                used += usage.used();
                if (usage.max() == null) maximumKnown = false;
                else max += usage.max();
            }
            if (known) result.put(root, new Usage(used, maximumKnown ? max : null));
        });
        return result;
    }

    private static boolean isJava(ProcessHandle process) {
        return process.info().command().map(command -> Path.of(command).getFileName().toString())
                .map(name -> name.equals("java") || name.equalsIgnoreCase("java.exe")).orElse(false);
    }

    private void retire(Monitor monitor) {
        synchronized (monitor) {
            monitor.retired = true;
            if (!monitor.pending) {
                try { worker.execute(monitor::disconnect); }
                catch (java.util.concurrent.RejectedExecutionException ignored) {}
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        monitors.values().forEach(this::retire);
        monitors.clear();
        worker.shutdown();
        try { if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException e) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    private final class Monitor {
        private final long pid;
        private final Instant started;
        private volatile Reading reading;
        private volatile boolean retired;
        private boolean pending;
        private long nextSample;
        private JMXConnector connector;
        private MemoryMXBean memory;

        private Monitor(long pid, Instant started) { this.pid = pid; this.started = started; }
        private synchronized void schedule(long now) {
            if (pending || retired || now < nextSample) return;
            pending = true;
            try { worker.execute(this::refresh); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { pending = false; }
        }
        private void refresh() {
            boolean failed = false;
            try {
                if (retired) return;
                if (connector == null) {
                    VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
                    String address;
                    try {
                        address = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
                        if (address == null) address = vm.startLocalManagementAgent();
                    } finally { vm.detach(); }
                    connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
                    memory = ManagementFactory.newPlatformMXBeanProxy(connector.getMBeanServerConnection(),
                            ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
                }
                reading = new Reading(Usage.from(memory.getHeapMemoryUsage()), System.nanoTime());
            } catch (Exception | LinkageError ignored) {
                reading = null;
                failed = true;
                disconnect();
            } finally {
                if (retired) disconnect();
                synchronized (this) {
                    nextSample = System.nanoTime() + TimeUnit.SECONDS.toNanos(failed ? 30 : 5);
                    pending = false;
                }
            }
        }
        private void disconnect() {
            JMXConnector previous = connector;
            connector = null;
            memory = null;
            if (previous != null) try { previous.close(); } catch (Exception ignored) {}
        }
    }
}
