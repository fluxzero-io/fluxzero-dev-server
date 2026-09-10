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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded asynchronous startup using the same bootstrap as the CLI entrypoint. */
final class DevMcpDevStarter implements AutoCloseable {
    private final Path directory;
    private DevServerBootstrap bootstrap;
    private final Callable<Integer> action;
    private final Duration timeout;
    private final java.util.concurrent.ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "fluxzero-mcp-start-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private Future<Integer> attempt;
    private String failure;
    private boolean closed;
    private boolean observedReady;

    DevMcpDevStarter(Path directory) {
        this(directory, null, Duration.ofMinutes(2));
    }

    DevMcpDevStarter(Path directory, Callable<Integer> action, Duration timeout) {
        this.directory = directory;
        this.action = action;
        this.timeout = timeout;
    }

    synchronized void start() {
        if (closed) throw new IllegalStateException("MCP connection is closed");
        if (attempt != null && !attempt.isDone()) return;
        failure = null;
        observedReady = false;
        var selectedBootstrap = new DevServerBootstrap();
        bootstrap = selectedBootstrap;
        attempt = worker.submit(() -> {
            try { return action == null ? selectedBootstrap.run(directory, List.of(), true, true) : action.call(); }
            catch (DevServerStartupException | IllegalArgumentException e) {
                System.err.println("Fluxzero dev could not start: " + e.getMessage());
                throw e;
            }
            finally { selectedBootstrap.close(); }
        });
        Future<Integer> selected = attempt;
        timer.schedule(() -> expire(selected), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized void observedReady() {
        observedReady = true;
        if (bootstrap != null) bootstrap.release();
    }

    synchronized Map<String, Object> status() {
        if (observedReady) return Map.of();
        if (failure != null) return result("dev-server-start-failed", failure);
        if (attempt == null) return Map.of();
        if (!attempt.isDone()) return result("dev-server-starting", "Development startup is in progress. Poll get_status.");
        try {
            return result("dev-server-start-failed", "Development bootstrap exited with code " + attempt.get()
                    + " without a reachable session. Inspect .fluxzero/dev/bootstrap.log and retry start_dev after repair.");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return result("dev-server-start-failed", "Development bootstrap failed. Verify the project directory and "
                    + "configuration, inspect .fluxzero/dev/bootstrap.log, then retry start_dev. Documentation remains available.");
        }
    }

    private Map<String, Object> result(String status, String message) {
        return Map.of("status", status, "message", message, "documentationAvailable", true,
                "projectDirectory", directory.toString());
    }

    private synchronized void expire(Future<Integer> selected) {
        if (attempt == selected && !selected.isDone()) {
            failure = "Development startup exceeded its time limit. Inspect startup diagnostics before retrying start_dev.";
            selected.cancel(true);
            if (bootstrap != null) bootstrap.close();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (attempt != null && !attempt.isDone()) attempt.cancel(true);
        worker.shutdownNow();
        timer.shutdownNow();
        if (bootstrap != null) bootstrap.close();
    }
}
