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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Latest result per testcase, independent of the current run's selection. */
final class TestInventory {
    private static final int MAX_TESTS = 100_000;
    private final Path file;
    private final Map<String, Map<String, String>> modules = new LinkedHashMap<>();
    private final Map<String, Set<String>> templates = new LinkedHashMap<>();
    private final Map<String, Set<String>> selected = new LinkedHashMap<>();
    private final Map<String, Set<String>> discoveredThisRun = new LinkedHashMap<>();
    private boolean known;

    TestInventory(Path directory) {
        file = directory.resolve("test-inventory.json");
        try {
            if (Files.isRegularFile(file) && Files.size(file) < 32_000_000) {
                Saved saved = new ObjectMapper().readValue(file.toFile(), Saved.class);
                Map<String, Map<String, String>> restored = saved.modules();
                if (restored.values().stream().mapToInt(Map::size).sum() <= MAX_TESTS) {
                    restored.forEach((scope, tests) -> modules.put(scope, new LinkedHashMap<>(tests)));
                    known = saved.known();
                    if (saved.templates() != null) templates.putAll(saved.templates());
                }
            }
        } catch (Exception ignored) { /* A missing inventory is unknown, not zero tests. */ }
    }

    synchronized void reset(List<String> selectors) {
        selected.clear();
        discoveredThisRun.clear();
        modules.forEach((scope, tests) -> {
            Set<String> ids = tests.keySet().stream().filter(id -> selectors.isEmpty()
                    || selectors.stream().anyMatch(selector -> matches(id, selector))).collect(java.util.stream.Collectors.toSet());
            selected.put(scope, ids);
            ids.forEach(id -> tests.put(id, "pending"));
        });
        save();
    }

    private static boolean matches(String id, String selector) {
        String[] parts = selector.split("#", 2);
        String decoded;
        try { decoded = java.net.URLDecoder.decode(id, java.nio.charset.StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { decoded = id; }
        boolean classMatch = decoded.contains("[class:" + parts[0] + "]")
                || decoded.startsWith(parts[0] + "#") || decoded.contains("[nested-class:" + parts[0] + "]");
        if (!classMatch) return false;
        if (parts.length == 1) return true;
        return decoded.contains(":" + parts[1] + "(") || decoded.startsWith(parts[0] + "#" + parts[1] + "(")
                || decoded.equals(parts[0] + "#" + parts[1]);
    }

    synchronized void discover(String scope, Set<String> ids, Set<String> dynamicTemplates) {
        if (ids.size() > MAX_TESTS || modules.size() >= 256 && !modules.containsKey(scope)) return;
        Map<String, String> old = modules.getOrDefault(scope, Map.of());
        Map<String, String> next = new LinkedHashMap<>();
        ids.forEach(id -> next.put(id, old.getOrDefault(id, "pending")));
        // Parameterized/dynamic invocations become known during execution; retain their last discovered shape.
        old.forEach((id, result) -> {if (dynamicTemplates.stream().anyMatch(t -> id.startsWith(t + "/"))) next.put(id, result);});
        if (modules.values().stream().mapToInt(Map::size).sum() - old.size() + next.size() <= MAX_TESTS) {
            modules.put(scope, next);
            discoveredThisRun.put(scope, Set.copyOf(ids));
            templates.put(scope, Set.copyOf(dynamicTemplates));
            known = true;
        }
    }

    synchronized void event(String scope, String kind, String id) {
        if (!Set.of("registered", "started", "passed", "failed", "skipped").contains(kind)) return;
        if (modules.size() >= 256 && !modules.containsKey(scope)) return;
        Map<String, String> tests = modules.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
        if (!tests.containsKey(id) && modules.values().stream().mapToInt(Map::size).sum() >= MAX_TESTS) return;
        if (kind.equals("registered")) tests.putIfAbsent(id, "pending");
        else tests.put(id, kind.equals("started") ? "pending" : kind);
    }

    synchronized Snapshot snapshot() {
        int passed = 0, failed = 0, skipped = 0, total = 0;
        for (Map<String, String> tests : modules.values()) for (String result : tests.values()) {
            total++;
            switch (result) { case "passed" -> passed++; case "failed" -> failed++; case "skipped" -> skipped++; }
        }
        boolean shapeKnown = known && templates.entrySet().stream().allMatch(entry -> entry.getValue().stream()
                .allMatch(template -> modules.getOrDefault(entry.getKey(), Map.of()).keySet().stream()
                        .anyMatch(id -> id.startsWith(template + "/"))));
        return new Snapshot(shapeKnown, total, new TestCounts(passed, failed, skipped));
    }

    synchronized void finish(boolean fullRun, boolean complete, Map<String, Set<String>> observed) {
        if (complete) selected.forEach((scope, ids) -> {
            Map<String, String> tests = modules.get(scope);
            if (tests != null) ids.stream().filter(id -> !observed.getOrDefault(scope, Set.of()).contains(id)
                    && !discoveredThisRun.getOrDefault(scope, Set.of()).contains(id)).forEach(tests::remove);
        });
        if (fullRun && complete) {
            modules.keySet().retainAll(observed.keySet());
            modules.forEach((scope, tests) -> tests.keySet().removeIf(id -> !observed.get(scope).contains(id)
                    && !discoveredThisRun.getOrDefault(scope, Set.of()).contains(id)));
            templates.keySet().retainAll(observed.keySet());
            // A completed full run also establishes empty dynamic factories as empty.
            templates.clear();
            known = true;
        }
        save();
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            new ObjectMapper().writeValue(temp.toFile(), new Saved(known, modules, templates));
            try {Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);}
            catch (java.nio.file.AtomicMoveNotSupportedException e) {Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);}
        } catch (Exception ignored) { /* Live status remains available if the local cache cannot be written. */ }
    }

    record Saved(boolean known, Map<String, Map<String, String>> modules, Map<String, Set<String>> templates) {}

    record Snapshot(boolean known, int total, TestCounts counts) {}
}
