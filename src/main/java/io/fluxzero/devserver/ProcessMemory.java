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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Resident process-tree memory (RSS on Unix, working set on Windows), sampled at most every five seconds. */
final class ProcessMemory {
    private long sampledAt;
    private Set<Long> lastRoots = Set.of();
    private Set<Long> lastProcessOnly = Set.of();
    private Map<Long, Long> values = Map.of();
    private Map<Long, Integer> processCounts = Map.of();

    synchronized int processCount(Long root) { return root == null ? 0 : processCounts.getOrDefault(root, 0); }

    synchronized Map<Long, Long> sample(Set<Long> roots) {
        return sample(roots, Set.of());
    }

    synchronized Map<Long, Long> sample(Set<Long> roots, Set<Long> processOnly) {
        if (roots.equals(lastRoots) && processOnly.equals(lastProcessOnly) && System.currentTimeMillis() - sampledAt < 5000) return values;
        Map<Long, Set<Long>> trees = new LinkedHashMap<>();
        roots.stream().filter(pid -> pid != null && pid > 0).forEach(pid -> ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive).ifPresent(process -> {
                    Set<Long> tree = new HashSet<>(); tree.add(pid);
                    if (!processOnly.contains(pid)) process.descendants().filter(ProcessHandle::isAlive).forEach(child -> tree.add(child.pid()));
                    trees.put(pid, tree);
                }));
        Set<Long> pids = new TreeSet<>(); trees.values().forEach(pids::addAll);
        Map<Long, Long> result = new HashMap<>();
        if (!pids.isEmpty()) {
            Process command = null;
            try {
                boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                command = new ProcessBuilder(command(pids, windows)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (command.waitFor(3, TimeUnit.SECONDS)) {
                    Map<Long, Long> measurements = parse(new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8), windows);
                    trees.forEach((root, tree) -> {
                        if (measurements.keySet().containsAll(tree)) result.put(root, tree.stream().mapToLong(measurements::get).sum());
                    });
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally { if (command != null && command.isAlive()) command.destroyForcibly(); }
        }
        Map<Long, Integer> counts = new HashMap<>();
        trees.forEach((root, tree) -> counts.put(root, tree.size()));
        processCounts = Map.copyOf(counts);
        sampledAt = System.currentTimeMillis(); lastRoots = Set.copyOf(roots); lastProcessOnly = Set.copyOf(processOnly); values = Map.copyOf(result);
        return values;
    }

    static List<String> command(Set<Long> pids, boolean windows) {
        String ids = pids.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return windows ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-Process -Id " + ids + " -ErrorAction SilentlyContinue | ForEach-Object { '{0} {1}' -f $_.Id,$_.WorkingSet64 }")
                : List.of("ps", "-o", "pid=,rss=", "-p", ids);
    }

    static Map<Long, Long> parse(String output, boolean windows) {
        Map<Long, Long> result = new HashMap<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length != 2) continue;
            try { result.put(Long.parseLong(fields[0]), Math.multiplyExact(Long.parseLong(fields[1]), windows ? 1 : 1024)); }
            catch (NumberFormatException | ArithmeticException ignored) { }
        }
        return result;
    }
}
