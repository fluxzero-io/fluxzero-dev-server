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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class DevServerDetachedLifecycleTest {
    @TempDir Path directory;

    @Test
    void backgroundServerAndManagedChildSurviveLaunchingSessionAndStopNormally() throws Throwable {
        Path root = Files.createDirectory(directory.resolve("project with spaces and 'quotes'"));
        Path config = root.resolve(".fluxzero/dev.yaml");
        Files.createDirectories(config.getParent());
        String fixture = Path.of(DevServiceFixtureServer.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        String command = quote(Path.of(System.getProperty("java.home"), "bin",
                ProcessUtils.isWindows() ? "java.exe" : "java").toString())
                + " -cp " + quote(fixture) + " " + DevServiceFixtureServer.class.getName() + " {frontendPort}";
        Files.writeString(config, "version: 1\nfrontendOnly: true\nfrontend:\n  command: '"
                + command.replace("'", "''") + "'\n");
        var ownerCommand = DevServerBootstrap.javaCommand(SessionOwner.class.getName(), List.of(root.toString()));
        ownerCommand.add(1, "-D" + DevMonitoringDefaults.FILE_PROPERTY + "="
                + Path.of("src/test/resources/monitoring-disabled.yaml").toAbsolutePath());
        Process owner = new ProcessBuilder(ownerCommand).redirectErrorStream(true)
                .redirectOutput(directory.resolve("owner.log").toFile()).start();
        DevSession session = null;
        try {
            await(() -> Files.exists(root.resolve("owner-ready")) || !owner.isAlive());
            assertTrue(owner.isAlive(), () -> read(directory.resolve("owner.log")));
            assertEquals("0", read(root.resolve("bootstrap-exit")));
            session = new DevSessionStore(root).readSession().orElseThrow();
            long serverPid = session.pid();
            long childPid = session.frontend().pid();
            assertReachable(session);
            if (ProcessUtils.isWindows()) {
                // Owner checks its real console's process list before releasing the last attachment.
                owner.getOutputStream().write('\n');
                owner.getOutputStream().flush();
                assertTrue(owner.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, owner.exitValue(), () -> read(directory.resolve("owner.log")));
            } else {
                // Only kill the dedicated session created by our helper, never the test runner's group.
                assertEquals(owner.pid(), nativeInt("getpgid", (int) owner.pid()));
                assertEquals(0, nativeInt("kill", -(int) owner.pid(), 9));
                assertTrue(owner.waitFor(10, TimeUnit.SECONDS));
            }
            assertTrue(ProcessUtils.isAlive(serverPid), "server died with its launching session");
            assertTrue(ProcessUtils.isAlive(childPid), "managed child died with its launching session");
            assertReachable(session);
            assertEquals(0, control(root, "probe"));
            assertEquals(0, control(root, "stop"));
            await(() -> !ProcessUtils.isAlive(serverPid) && !ProcessUtils.isAlive(childPid));
            assertFalse(ProcessUtils.isAlive(serverPid));
            assertFalse(ProcessUtils.isAlive(childPid));
        } finally {
            if (session == null) session = new DevSessionStore(root).readSession().orElse(null);
            if (session != null) ProcessUtils.stopIfOwned(session.pid(), root.toString(), session.startedAt(), Duration.ofSeconds(3));
            owner.destroyForcibly();
            owner.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void isolationFailureDoesNotStartAnEnvironment() throws Exception {
        var command = DevServerBootstrap.javaCommand(AlreadySessionLeader.class.getName(),
                List.of("--project-dir", directory.toString()));
        Path log = directory.resolve("failed-detach.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertTrue(read(log).contains("Could not detach the Fluxzero dev server"), () -> read(log));
            assertFalse(Files.exists(directory.resolve(".fluxzero/dev/session.json")));
        } finally { process.destroyForcibly(); }
    }

    public static class AlreadySessionLeader {
        public static void main(String[] args) throws Exception {
            DevServerDetachedMain.detach();
            // POSIX forbids setsid for a process that is already a group leader.
            DevServerDetachedMain.main(args);
        }
    }

    private static void assertReachable(DevSession session) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(session.gateway().url()))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            assertEquals(200, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    private int control(Path root, String action) throws Exception {
        var command = DevServerBootstrap.javaCommand(DevServerControlMain.class.getName(),
                List.of(action, "--project-dir", root.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve(action + ".log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            return process.exitValue();
        } finally { process.destroyForcibly(); }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception e) { return e.toString(); }
    }

    private static String quote(String value) {
        return ProcessUtils.isWindows() ? "\"" + value + "\"" : "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "Lifecycle condition timed out");
    }

    private static int nativeInt(String name, int... args) throws Throwable {
        var linker = Linker.nativeLinker();
        var layouts = new java.lang.foreign.MemoryLayout[args.length];
        java.util.Arrays.fill(layouts, JAVA_INT);
        return (int) linker.downcallHandle(linker.defaultLookup().findOrThrow(name),
                FunctionDescriptor.of(JAVA_INT, layouts))
                .invokeWithArguments(java.util.Arrays.stream(args).boxed().toList());
    }

    public static class SessionOwner {
        public static void main(String[] args) throws Throwable {
            Path root = Path.of(args[0]);
            DevServerDetachedMain.detach();
            try (var arena = Arena.ofConfined()) {
                SymbolLookup kernel = ProcessUtils.isWindows() ? SymbolLookup.libraryLookup("Kernel32.dll", arena) : null;
                if (kernel != null) {
                    int allocated = (int) Linker.nativeLinker().downcallHandle(kernel.findOrThrow("AllocConsole"),
                            FunctionDescriptor.of(JAVA_INT)).invokeExact();
                    if (allocated == 0) throw new IllegalStateException("Test console allocation failed");
                }
                var command = DevServerBootstrap.javaCommand(DevServerBootstrapMain.class.getName(), List.of(
                        "--bootstrap-background", "--bootstrap-agent-ready", "--project-dir", root.toString(),
                        "--no-watch", "--no-tests", "--no-compile-on-start"));
                Process bootstrap = new ProcessBuilder(command).inheritIO().start();
                if (!bootstrap.waitFor(30, TimeUnit.SECONDS)) {
                    bootstrap.destroyForcibly();
                    throw new IllegalStateException("Bootstrap timed out");
                }
                Files.writeString(root.resolve("bootstrap-exit"), Integer.toString(bootstrap.exitValue()));
                if (bootstrap.exitValue() != 0) throw new IllegalStateException("Bootstrap failed: " + bootstrap.exitValue());
                await(() -> new DevSessionStore(root).readSession()
                        .filter(s -> "running".equals(s.frontend().state())).isPresent());
                if (kernel != null) {
                    var buffer = arena.allocate(JAVA_INT, 64);
                    int count = (int) Linker.nativeLinker().downcallHandle(kernel.findOrThrow("GetConsoleProcessList"),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)).invokeExact(buffer, 64);
                    if (count != 1 || buffer.get(JAVA_INT, 0) != (int) ProcessHandle.current().pid()) {
                        throw new IllegalStateException("Background processes still share the launching console: " + count);
                    }
                }
                Files.writeString(root.resolve("owner-ready"), "ready");
                System.in.read();
                if (kernel != null) DevServerDetachedMain.detach();
            }
        }
    }
}
