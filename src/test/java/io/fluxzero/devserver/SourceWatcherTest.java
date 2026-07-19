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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceWatcherTest {

    @Test
    void detectsManagedFrontendInputsWithoutIncludingGeneratedFrontendOutput(@TempDir Path project)
            throws Exception {
        Path frontendSource = project.resolve("frontend/src/app/app.component.ts");
        Path packageFile = project.resolve("frontend/package.json");
        Path angularCache = project.resolve("frontend/.angular/cache/index.pack");
        Path nodeModules = project.resolve("frontend/node_modules/library/index.js");
        for (Path file : List.of(frontendSource, packageFile, angularCache, nodeModules)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "before");
        }
        List<Set<Path>> batches = new CopyOnWriteArrayList<>();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        DevServerConfig config = new DevServerConfig(
                project, null, "test", null, true, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                Duration.ofMillis(50),
                FrontendConfig.command("npm run dev").withLaunchSetup("frontend", null), List.of());

        try (SourceWatcher watcher = new SourceWatcher(config, scheduler, batches::add)) {
            watcher.start();
            Files.writeString(frontendSource, "after");
            Files.writeString(packageFile, "after");
            Files.writeString(angularCache, "after");
            Files.writeString(nodeModules, "after");
            assertTrue(await(() -> batches.stream().flatMap(Set::stream).collect(
                    java.util.stream.Collectors.toSet()).containsAll(Set.of(
                            frontendSource.toAbsolutePath().normalize(),
                            packageFile.toAbsolutePath().normalize()))));
        } finally {
            scheduler.shutdownNow();
        }

        Set<Path> changes = batches.stream().flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(frontendSource, packageFile).stream()
                             .map(path -> path.toAbsolutePath().normalize())
                             .collect(java.util.stream.Collectors.toSet()),
                     changes);
    }

    @Test
    void detectsSourcesAlreadyPresentInNewDirectory(@TempDir Path project) throws Exception {
        Path sourceRoot = project.resolve("app/src/main/java/com/acme");
        Path stagedDirectory = project.resolve("app/target/new-feature");
        Path stagedSource = stagedDirectory.resolve("NewHandler.java");
        Path source = sourceRoot.resolve("newfeature/NewHandler.java");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(stagedDirectory);
        Files.writeString(stagedSource, "class NewHandler {}");
        List<Set<Path>> batches = new CopyOnWriteArrayList<>();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        DevServerConfig config = new DevServerConfig(
                project, null, "test", null, true, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                Duration.ofMillis(50), FrontendConfig.none(), List.of());

        try (SourceWatcher watcher = new SourceWatcher(config, scheduler, batches::add)) {
            watcher.start();
            Files.move(stagedDirectory, source.getParent(), StandardCopyOption.ATOMIC_MOVE);
            assertTrue(await(() -> batches.stream().flatMap(Set::stream)
                    .anyMatch(source.toAbsolutePath().normalize()::equals)));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void watchesOnlyMavenProjectInputs(@TempDir Path project) throws Exception {
        Path source = project.resolve("app/src/main/java/com/acme/App.java");
        Path testResource = project.resolve("app/src/test/resources/dev/seed.json");
        Path generatorInput = project.resolve("core/src/main/typescript-generator/extensions.ts");
        Path modulePom = project.resolve("app/pom.xml");
        Path devConfig = project.resolve(DevProjectConfig.FILE);
        Path output = project.resolve("app/target/classes/com/acme/App.class");
        Path ideaState = project.resolve(".idea/workspace.xml");
        Path runConfiguration = project.resolve(".run/webhooks.run.xml");
        Path angularCache = project.resolve("frontend/.angular/cache/index.pack");
        Path frontendSource = project.resolve("frontend/src/app/app.component.ts");
        for (Path file : List.of(source, testResource, generatorInput, modulePom, devConfig, output, ideaState,
                                 runConfiguration, angularCache, frontendSource)) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(source, "before");
        Files.writeString(testResource, "before");
        Files.writeString(generatorInput, "before");
        Files.writeString(modulePom, "before");
        Files.writeString(devConfig, "version: 1\n");
        List<Set<Path>> batches = new CopyOnWriteArrayList<>();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        DevServerConfig config = new DevServerConfig(
                project, null, "test", null, true, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                Duration.ofMillis(50), FrontendConfig.none(), List.of());

        try (SourceWatcher watcher = new SourceWatcher(config, scheduler, batches::add)) {
            watcher.start();
            Files.writeString(output, "generated");
            Files.writeString(ideaState, "generated");
            Files.writeString(runConfiguration, "generated");
            Files.writeString(angularCache, "generated");
            Files.writeString(frontendSource, "generated");
            Files.writeString(source, "after");
            Files.writeString(testResource, "after");
            Files.writeString(generatorInput, "after");
            Files.writeString(modulePom, "after");
            Files.writeString(devConfig, "version: 1\ncommands: {}\n");
            assertTrue(await(() -> !batches.isEmpty()));
        } finally {
            scheduler.shutdownNow();
        }

        Set<Path> changes = batches.stream().flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(source, testResource, generatorInput, modulePom, devConfig).stream()
                             .map(path -> path.toAbsolutePath().normalize())
                             .collect(java.util.stream.Collectors.toSet()),
                     changes);
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }
}
