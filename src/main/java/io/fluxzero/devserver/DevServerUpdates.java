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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded background checks; artifact resolution remains owned by the CLI. */
final class DevServerUpdates implements AutoCloseable {
    @FunctionalInterface interface Cli { Map<String, String> run(List<String> arguments) throws Exception; }
    private final Cli cli;
    private final boolean enabled;
    private final java.util.concurrent.ScheduledExecutorService worker;
    private volatile Map<String, String> status = Map.of("status", "unavailable");

    DevServerUpdates(Path directory) {
        this("latest".equals(System.getProperty("fluxzero.dev.updatePolicy"))
                && System.getenv("FLUXZERO_DEV_SERVER_VERSION") == null,
                args -> runCli(directory, args), true);
    }

    DevServerUpdates(boolean enabled, Cli cli, boolean schedule) {
        this.enabled = enabled;
        this.cli = cli;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "dev-server-update-check"); thread.setDaemon(true); return thread;
        });
        if (enabled && schedule) worker.scheduleWithFixedDelay(this::check, 10, 3600, TimeUnit.SECONDS);
    }

    Map<String, String> status() {
        var result = new java.util.LinkedHashMap<>(status);
        String attempt = System.getProperty("fluxzero.dev.updateAttempt");
        String failure = System.getProperty("fluxzero.dev.updateError");
        if (attempt != null) result.put("attemptId", attempt);
        if (failure != null) result.put("error", failure);
        return result;
    }

    void check() {
        if (!enabled) return;
        try { status = Map.copyOf(cli.run(List.of("check-update", "--current-version", DevServerVersion.current()))); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            status = Map.of("status", "unavailable"); // Offline is not a workspace issue.
        }
    }

    Path prepare(String version) throws Exception {
        if (!enabled || !"available".equals(status.get("status")) || !version.equals(status.get("latestVersion")))
            throw new IllegalStateException("This update is no longer available. Wait for the next check.");
        Map<String, String> result = cli.run(List.of("prepare-update", "--current-version", DevServerVersion.current(),
                "--dev-server-version", version));
        if (!"available".equals(result.get("status")) || !version.equals(result.get("latestVersion")))
            throw new IllegalStateException("The selected update could not be prepared.");
        String value = result.get("artifact");
        if (value == null || !Files.isRegularFile(Path.of(value)) || !Path.of(value).isAbsolute())
            throw new IllegalStateException("The update artifact is unavailable.");
        Path artifact = Path.of(value);
        // Catch incompatible/damaged artifacts before stopping any application.
        try (var jar = new java.util.jar.JarFile(artifact.toFile())) {
            if (jar.getEntry("io/fluxzero/devserver/DevServerBootstrapMain.class") == null)
                throw new IllegalStateException("The update does not support managed startup.");
        }
        return artifact;
    }

    private static Map<String, String> runCli(Path directory, List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of("fz", "dev")); command.addAll(arguments);
        command.addAll(List.of("--project-dir", directory.toString(), "--json"));
        var result = run(command, directory, arguments.getFirst().equals("check-update")
                ? Duration.ofSeconds(20) : Duration.ofMinutes(3));
        if (result.length() > 16384) throw new IllegalStateException("Unexpected update response.");
        return new ObjectMapper().readValue(result, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    static String run(List<String> command, Path directory, Duration timeout) throws Exception {
        Path stdout = Files.createTempFile("fluxzero-update-", ".out");
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(stdout.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Update preparation timed out. The current app is still running.");
            if (process.exitValue() != 0) throw new IllegalStateException("Could not prepare the update. Try again later.");
            if (Files.size(stdout) > 16384) throw new IllegalStateException("Unexpected update response.");
            return Files.readString(stdout);
        } finally {
            if (process != null && process.isAlive()) {
                process.toHandle().descendants().forEach(p -> p.destroy());
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.toHandle().descendants().forEach(p -> p.destroyForcibly()); process.destroyForcibly();
                }
            }
            Files.deleteIfExists(stdout);
        }
    }

    @Override public void close() { worker.shutdownNow(); }
}
