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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded background checks; artifact resolution remains owned by the CLI. */
final class DevServerUpdates implements AutoCloseable {
    static final String ATTEMPT_PROPERTY = "fluxzero.dev.updateAttempt";
    static final String ERROR_PROPERTY = "fluxzero.dev.updateError";
    static final String PHASE_PROPERTY = "fluxzero.dev.updatePhase";
    private static final int MAX_STDOUT = 16_384;
    private static final int MAX_DIAGNOSTIC = 8_192;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(authorization|password|passwd|token|secret|api[-_]?key|client[-_]?secret)\\b"
            + "(\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+\\S+");
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
        String attempt = System.getProperty(ATTEMPT_PROPERTY);
        String failure = System.getProperty(ERROR_PROPERTY);
        String phase = System.getProperty(PHASE_PROPERTY);
        if (attempt != null) result.put("attemptId", attempt);
        if (failure != null) result.put("error", failure);
        if (phase != null) result.put("phase", phase);
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
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        BoundedOutput stdout = new BoundedOutput(MAX_STDOUT, false);
        BoundedOutput stderr = new BoundedOutput(MAX_DIAGNOSTIC, true);
        Thread stdoutReader = stdout.readFrom(process.getInputStream(), "dev-update-stdout");
        Thread stderrReader = stderr.readFrom(process.getErrorStream(), "dev-update-stderr");
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessUtils.stopTree(process, Duration.ofSeconds(3));
                awaitReaders(stdoutReader, stderrReader);
                throw commandFailure("Update command timed out after " + timeout, stderr, stdout);
            }
            awaitReaders(stdoutReader, stderrReader);
            if (process.exitValue() != 0) {
                throw commandFailure("Update command failed with exit code " + process.exitValue(), stderr, stdout);
            }
            if (stdout.truncated()) {
                throw new IllegalStateException("Unexpected update response: output exceeded " + MAX_STDOUT + " characters.");
            }
            return stdout.value();
        } finally {
            if (process.isAlive()) {
                ProcessUtils.forceStopTree(process);
            }
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            awaitReadersUninterruptibly(stdoutReader, stderrReader);
        }
    }

    private static IllegalStateException commandFailure(
            String summary, BoundedOutput stderr, BoundedOutput stdout
    ) {
        String detail = stderr.value().isBlank() ? stdout.value() : stderr.value();
        detail = redact(oneLine(detail));
        if (detail.isBlank()) {
            return new IllegalStateException(summary + ".");
        }
        return new IllegalStateException(summary + ": " + detail);
    }

    private static String redact(String value) {
        String redacted = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        return SENSITIVE_VALUE.matcher(redacted).replaceAll("$1$2[REDACTED]");
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static void awaitReaders(Thread... readers) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        for (Thread reader : readers) {
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (!remaining.isZero()) {
                reader.join(remaining);
            }
        }
        if (java.util.Arrays.stream(readers).anyMatch(Thread::isAlive)) {
            throw new IllegalStateException("Update command output did not close.");
        }
    }

    private static void awaitReadersUninterruptibly(Thread... readers) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        for (Thread reader : readers) {
            while (reader.isAlive() && System.nanoTime() < deadline) {
                try {
                    reader.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (reader.isAlive()) {
                reader.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The process may already have closed the pipe.
        }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final boolean retainTail;
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;

        private BoundedOutput(int limit, boolean retainTail) {
            this.limit = limit;
            this.retainTail = retainTail;
        }

        private Thread readFrom(InputStream input, String name) {
            return Thread.ofVirtual().name(name).start(() -> {
                try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int read;
                    while ((read = reader.read(buffer)) >= 0) {
                        append(buffer, read);
                    }
                } catch (IOException ignored) {
                    // Process termination may close the pipe while a reader is draining it.
                }
            });
        }

        private synchronized void append(char[] buffer, int length) {
            if (retainTail) {
                value.append(buffer, 0, length);
                if (value.length() > limit) {
                    value.delete(0, value.length() - limit);
                    truncated = true;
                }
                return;
            }
            int remaining = limit - value.length();
            if (remaining > 0) {
                value.append(buffer, 0, Math.min(remaining, length));
            }
            truncated |= length > remaining;
        }

        private synchronized String value() {
            return value.toString();
        }

        private synchronized boolean truncated() {
            return truncated;
        }
    }

    @Override public void close() { worker.shutdownNow(); }
}
