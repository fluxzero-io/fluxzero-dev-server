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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class SourceWatcher implements AutoCloseable {
    private final DevServerConfig config;
    private final ScheduledExecutorService scheduler;
    private final Consumer<Set<Path>> changesConsumer;
    private final WatchService watchService;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final Set<Path> pendingChanges = new LinkedHashSet<>();
    private ScheduledFuture<?> debounce;
    private Thread thread;

    SourceWatcher(DevServerConfig config, ScheduledExecutorService scheduler, Consumer<Set<Path>> changesConsumer)
            throws IOException {
        this.config = config;
        this.scheduler = scheduler;
        this.changesConsumer = changesConsumer;
        this.watchService = config.projectDirectory().getFileSystem().newWatchService();
    }

    void start() throws IOException {
        registerProjectRoots();
        thread = Thread.ofPlatform().daemon(true).name("fluxzero-dev-source-watcher").start(this::run);
    }

    private void registerProjectRoots() throws IOException {
        registerIfDirectory(config.projectDirectory());
        for (RoutedFrontend frontend : config.frontends()) {
            if (frontend.config().mode() != FrontendConfig.Mode.COMMAND
                || frontend.config().directory() == null) {
                continue;
            }
            Path configured = Path.of(frontend.config().directory());
            Path directory = configured.isAbsolute() ? configured : config.projectDirectory().resolve(configured);
            if (!directory.toAbsolutePath().normalize().startsWith(
                    config.projectDirectory().toAbsolutePath().normalize())) {
                registerIfDirectory(directory.toAbsolutePath().normalize());
            }
        }
    }

    private void registerIfDirectory(Path directory) throws IOException {
        registerIfDirectory(directory, false);
    }

    private void registerIfDirectory(Path directory, boolean enqueueExistingFiles) throws IOException {
        if (Files.isDirectory(directory)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    boolean projectRoot = dir.toAbsolutePath().normalize()
                            .equals(directory.toAbsolutePath().normalize());
                    if (projectRoot || !ignored(dir)) {
                        registerDirectory(dir);
                    }
                    return !projectRoot && ignored(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (enqueueExistingFiles && !ignored(file) && relevant(file)) {
                        enqueue(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void registerDirectory(Path directory) throws IOException {
        WatchKey key = directory.register(watchService,
                                          StandardWatchEventKinds.ENTRY_CREATE,
                                          StandardWatchEventKinds.ENTRY_MODIFY,
                                          StandardWatchEventKinds.ENTRY_DELETE);
        directories.put(key, directory);
    }

    private void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                Path directory = directories.get(key);
                if (directory != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        Path changed = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
                        if (Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            registerIfDirectory(changed, true);
                        }
                        if (!Files.isDirectory(changed) && !ignored(changed) && relevant(changed)) {
                            enqueue(changed);
                        }
                    }
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new IllegalStateException("Source watcher failed", e);
        }
    }

    private synchronized void enqueue(Path changed) {
        pendingChanges.add(changed);
        if (debounce != null) {
            debounce.cancel(false);
        }
        debounce = scheduler.schedule(this::flush, config.debounce().toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void flush() {
        Set<Path> changes = Set.copyOf(pendingChanges);
        pendingChanges.clear();
        if (!changes.isEmpty()) {
            changesConsumer.accept(changes);
        }
    }

    private boolean ignored(Path path) {
        Path relative;
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Path root = watchRoot(absolute);
            relative = root.relativize(absolute);
        } catch (IllegalArgumentException e) {
            return true;
        }
        String text = relative.toString().replace('\\', '/');
        String surrounded = "/" + text + "/";
        return text.isBlank()
               || surrounded.contains("/target/")
               || surrounded.contains("/.git/")
               || surrounded.contains("/.fluxzero/dev/")
               || surrounded.contains("/.angular/")
               || surrounded.contains("/node_modules/")
               || surrounded.contains("/dist/")
               || surrounded.contains("/build/");
    }

    private boolean relevant(Path path) {
        if (frontendPath(path)) {
            return true;
        }
        Path relative;
        try {
            relative = config.projectDirectory().toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String text = relative.toString().replace('\\', '/');
        return text.equals(DevProjectConfig.FILE.toString().replace('\\', '/'))
               || path.getFileName().toString().equals("pom.xml")
               || path.getFileName().toString().equals("build.gradle")
               || path.getFileName().toString().equals("build.gradle.kts")
               || path.getFileName().toString().equals("settings.gradle")
               || path.getFileName().toString().equals("settings.gradle.kts")
               || path.getFileName().toString().equals("gradle.properties")
               || text.endsWith("gradle/libs.versions.toml")
               || text.startsWith("src/main/")
               || text.startsWith("src/test/")
               || text.contains("/src/main/")
               || text.contains("/src/test/");
    }

    private Path watchRoot(Path path) {
        Path projectRoot = config.projectDirectory().toAbsolutePath().normalize();
        if (path.startsWith(projectRoot)) {
            return projectRoot;
        }
        return config.frontends().stream().map(RoutedFrontend::config)
                .filter(frontend -> frontend.mode() == FrontendConfig.Mode.COMMAND && frontend.directory() != null)
                .map(frontend -> {
                    Path configured = Path.of(frontend.directory());
                    return (configured.isAbsolute() ? configured : projectRoot.resolve(configured))
                            .toAbsolutePath().normalize();
                })
                .filter(path::startsWith)
                .max(java.util.Comparator.comparingInt(Path::getNameCount))
                .orElseThrow(() -> new IllegalArgumentException("path is outside watched roots"));
    }

    boolean frontendPath(Path path) {
        return config.frontends().stream().anyMatch(frontend -> frontendPath(path, frontend.config()));
    }

    private boolean frontendPath(Path path, FrontendConfig frontend) {
        if (frontend.mode() != FrontendConfig.Mode.COMMAND || frontend.directory() == null) {
            return false;
        }
        Path configured = Path.of(frontend.directory());
        Path frontendDirectory = configured.isAbsolute() ? configured.toAbsolutePath().normalize()
                : config.projectDirectory().resolve(configured).toAbsolutePath().normalize();
        Path candidate = path.isAbsolute() ? path.toAbsolutePath().normalize()
                : config.projectDirectory().resolve(path).toAbsolutePath().normalize();
        return candidate.startsWith(frontendDirectory) && !candidate.equals(frontendDirectory);
    }

    @Override
    public void close() {
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
