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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Local project discovery and CLI-owned scaffolding. Never removes project files. */
final class DevConsoleProjects implements AutoCloseable {
    @FunctionalInterface interface Scaffolder { void create(Path directory, String name) throws Exception; }
    private final DevEnvironmentRegistry registry;
    private final Scaffolder scaffolder;
    private final java.util.concurrent.ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    DevConsoleProjects(DevEnvironmentRegistry registry) {
        this(registry, (directory, name) -> DevServerUpdates.run(List.of("fz", "init", "--dir", directory.toString(),
                "--in-place", "--name", name, "--template", "flux-basic-java", "--package", "com.example.app",
                "--build", "maven", "--git"), directory, Duration.ofMinutes(3)));
    }
    DevConsoleProjects(DevEnvironmentRegistry registry, Scaffolder scaffolder) {
        this.registry = registry; this.scaffolder = scaffolder;
    }
    static Path folder(String value) throws java.io.IOException {
        if (value == null || value.isBlank()) value = System.getProperty("user.home");
        if (value.equals("~")) value = System.getProperty("user.home");
        else if (value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Choose an absolute folder path.");
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("This folder does not exist.");
        return path.toRealPath();
    }
    static boolean isProject(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml")) || Files.isRegularFile(path.resolve("build.gradle"))
                || Files.isRegularFile(path.resolve("build.gradle.kts")) || Files.isRegularFile(path.resolve(".fluxzero/dev.yaml"));
    }
    Map<String, Object> folders(String value) throws java.io.IOException {
        Path path = folder(value);
        try (var entries = Files.list(path)) {
            var folders = entries.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(201).map(p -> Map.of("name", p.getFileName().toString(), "path", p.toString())).toList();
            return Map.of("path", path.toString(), "parent", path.getParent() == null ? "" : path.getParent().toString(),
                    "folders", folders.subList(0, Math.min(200, folders.size())), "truncated", folders.size() > 200,
                    "project", isProject(path));
        }
    }
    DevEnvironmentRegistry.ConsoleEnvironment open(String value) throws java.io.IOException {
        Path path = folder(value);
        if (!isProject(path)) throw new IllegalArgumentException("Choose a Fluxzero project folder with a Maven, Gradle or .fluxzero/dev.yaml configuration.");
        return registry.add(path);
    }
    CompletableFuture<DevEnvironmentRegistry.ConsoleEnvironment> create(String parent, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (name == null || !name.matches("[a-z][a-z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("Use a name starting with a lowercase letter, followed by letters, numbers, hyphens or underscores (up to 64 characters).");
                Path target = folder(parent).resolve(name);
                // Reserve the new directory atomically: never scaffold over an existing folder.
                Files.createDirectory(target);
                scaffolder.create(target, name);
                if (!Files.isRegularFile(target.resolve("pom.xml")))
                    throw new IllegalStateException("The CLI could not create the project. Check the new folder before retrying.");
                return registry.add(target);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalArgumentException("A folder with this name already exists. Choose another name.");
            } catch (IllegalArgumentException | IllegalStateException e) { throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Could not create the project. Make sure the Fluxzero CLI is installed and the folder is writable. Any created files have been kept.", e);
            }
        }, worker);
    }
    @Override public void close() { worker.shutdownNow(); }
}
