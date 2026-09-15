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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Starts known projects with the shared CLI bootstrap, once per pending project. */
final class DevConsoleProjectStarter implements AutoCloseable {
    @FunctionalInterface interface Launcher { int start(Path directory) throws Exception; }
    private final DevEnvironmentRegistry environments;
    private final Launcher launcher;
    private final Duration timeout;
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "dev-console-start-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<DevEnvironmentRegistry.ConsoleEnvironment>> pending = new HashMap<>();
    private final Set<DevServerBootstrap> bootstraps = new HashSet<>();
    private boolean closed;

    DevConsoleProjectStarter(DevEnvironmentRegistry environments) { this(environments, null); }
    DevConsoleProjectStarter(DevEnvironmentRegistry environments, Launcher launcher) {
        this(environments, launcher, Duration.ofMinutes(2));
    }
    DevConsoleProjectStarter(DevEnvironmentRegistry environments, Launcher launcher, Duration timeout) {
        this.timeout = timeout;
        this.environments = environments;
        this.launcher = launcher == null ? this::launch : launcher;
    }

    synchronized CompletableFuture<DevEnvironmentRegistry.ConsoleEnvironment> start(String id) {
        if (closed) throw new IllegalStateException("This dev server is shutting down.");
        if (pending.containsKey(id)) return pending.get(id);
        var project = environments.findKnown(id).orElseThrow(() -> new IllegalArgumentException("Project is no longer listed."));
        if (project.consoleUrl() != null) return CompletableFuture.completedFuture(project);
        if (!"stopped".equals(project.status())) throw new IllegalStateException("This dev server is already running but is not responding.");
        Path directory = Path.of(project.projectDirectory());
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("The project folder no longer exists.");
        var result = new CompletableFuture<DevEnvironmentRegistry.ConsoleEnvironment>();
        pending.put(id, result);
        var task = worker.submit(() -> {
            try {
                int exit = launcher.start(directory);
                var started = environments.findKnown(id).filter(e -> e.consoleUrl() != null);
                if (exit != 0 || started.isEmpty()) throw new IllegalStateException(
                        "Unable to start the dev server. Check the project configuration and .fluxzero/dev/bootstrap.log.");
                result.complete(started.orElseThrow());
            } catch (Exception e) {
                result.completeExceptionally(e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally { synchronized (this) { pending.remove(id, result); } }
        });
        var deadline = timer.schedule(() -> {
            if (result.completeExceptionally(new java.util.concurrent.TimeoutException("Dev server startup timed out."))) task.cancel(true);
        }, timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        result.whenComplete((value, error) -> deadline.cancel(false));
        return result;
    }

    private int launch(Path directory) throws Exception {
        var bootstrap = new DevServerBootstrap();
        synchronized (this) {
            if (closed) throw new IllegalStateException("This dev server is shutting down.");
            bootstraps.add(bootstrap);
        }
        try (bootstrap) {
            // Agent readiness is the non-interactive gateway/control-plane readiness path.
            return bootstrap.run(directory, java.util.List.of(), true, true);
        } finally { synchronized (this) { bootstraps.remove(bootstrap); } }
    }

    @Override public void close() {
        Set<DevServerBootstrap> active;
        synchronized (this) { closed = true; active = Set.copyOf(bootstraps); }
        worker.shutdownNow();
        timer.shutdownNow();
        active.forEach(DevServerBootstrap::close);
    }
}
