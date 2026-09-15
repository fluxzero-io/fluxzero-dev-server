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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs one external writer while managed builds and frontend watchers are paused. */
public final class DevVerificationMain {
    private DevVerificationMain() {}

    public static void main(String[] args) {
        Thread owner = Thread.currentThread();
        Thread cleanup = new Thread(() -> {
            owner.interrupt();
            try { owner.join(45_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "verification-shutdown");
        Runtime.getRuntime().addShutdownHook(cleanup);
        int exit = 1;
        try {
            Path project = Path.of("").toAbsolutePath();
            long timeout = 1800;
            int index = 0;
            while (index < args.length && !args[index].equals("--")) {
                switch (args[index++]) {
                    case "--project-dir" -> project = Path.of(args[index++]).toAbsolutePath().normalize();
                    case "--timeout-seconds" -> timeout = Long.parseLong(args[index++]);
                    default -> throw new IllegalArgumentException("Usage: DevVerificationMain --project-dir DIR [--timeout-seconds N] -- COMMAND ARG...");
                }
            }
            if (index >= args.length - 1 || timeout < 1 || timeout > 86400)
                throw new IllegalArgumentException("Supply a command after -- and a timeout between 1 and 86400 seconds");
            exit = run(project, Arrays.asList(args).subList(index + 1, args.length), Duration.ofSeconds(timeout));
        } catch (Exception e) {
            System.err.println("Exclusive verification failed: " + e.getMessage());
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException shutdownInProgress) { /* The hook is waiting for this cleanup. */ }
        }
        if (!Thread.currentThread().isInterrupted() && exit != 0) System.exit(exit);
    }

    static int run(Path project, List<String> command, Duration timeout) throws Exception {
        DevSession session = new DevSessionStore(project).readSession().orElseThrow(
                () -> new IllegalStateException("Start the project dev server before exclusive verification"));
        if (!"running".equals(session.status()) || session.gateway().url() == null)
            throw new IllegalStateException("A running dev server with a local console is required");
        URI base = URI.create(session.gateway().url());
        if (!"http".equals(base.getScheme()) || !List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(base.getHost()))
            throw new IllegalStateException("Verification requires a loopback console URL");
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            action(http, base, "pause-builds");
            Process process = null;
            int result = 1;
            try {
                awaitState(http, base, "paused", Duration.ofSeconds(65));
                System.out.println("Managed builds and frontends paused; running external verification");
                process = ProcessUtils.start(command, project, Map.of(), System.out::println);
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Verification timed out");
                }
                result = process.exitValue();
                return result;
            } finally {
                boolean writerStopped = true;
                if (process != null && process.isAlive()) {
                    var descendants = process.descendants().toList();
                    ProcessUtils.forceStopTree(process);
                    writerStopped = !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
                }
                boolean interrupted = Thread.interrupted();
                try {
                    if (!writerStopped) throw new IllegalStateException(
                            "External writer is still alive; builds remain paused until it is stopped");
                    DevSession current = new DevSessionStore(project).readSession().orElseThrow();
                    if (!current.sessionId().equals(session.sessionId()))
                        throw new IllegalStateException("Dev session changed; refusing to resume another environment");
                    action(http, base, "resume-builds");
                    awaitState(http, base, "running", Duration.ofSeconds(35));
                    System.out.println("Managed development resumed");
                } catch (Exception resumeFailure) {
                    System.err.println("Could not resume managed development: " + resumeFailure.getMessage());
                    if (result == 0) throw resumeFailure;
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    private static void action(HttpClient http, URI base, String action) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/_fluxzero/dev/actions/" + action))
                .timeout(Duration.ofSeconds(10)).header("Origin", base.toString()).header("X-Fluxzero-Console", "1")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 202) throw new IllegalStateException("Console action " + action + " failed: " + response.statusCode());
    }

    private static void awaitState(HttpClient http, URI base, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var request = HttpRequest.newBuilder(base.resolve("/_fluxzero/dev/status.json")).timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Console status unavailable");
            JsonNode maintenance = new ObjectMapper().readTree(response.body()).path("maintenance");
            String error = maintenance.path("error").asText();
            if (!error.isEmpty()) throw new IllegalStateException(error);
            if (expected.equals(maintenance.path("buildPauseState").asText())
                    && (!expected.equals("running") || !maintenance.path("busy").asBoolean())) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for build state " + expected);
    }
}
