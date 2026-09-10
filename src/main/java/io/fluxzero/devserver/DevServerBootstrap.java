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

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** Shared project bootstrap for CLI launchers and the agent-owned stdio server. */
final class DevServerBootstrap implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final AtomicReference<OwnedProcess> owned = new AtomicReference<>();
    private boolean closed;

    int run(Path directory, List<String> arguments, boolean background, boolean agentReady) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        var args = new ArrayList<>(arguments);
        if (!args.contains("--project-dir")) args.addAll(List.of("--project-dir", root.toString()));
        Path lockFile = root.resolve(".fluxzero/dev/ensure.lock");
        Files.createDirectories(lockFile.getParent());
        ReentrantLock local = LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
        long pid;
        boolean reused;
        local.lockInterruptibly();
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            DevSession active = new DevSessionStore(root).readSession()
                    .filter(s -> !s.status().startsWith("stopped") && ProcessUtils.isAlive(s.pid(), s.startedAt()))
                    .orElse(null);
            reused = active != null;
            if (active != null) {
                pid = active.pid();
                if (!background) owned.set(new OwnedProcess(pid, active.startedAt(), root));
                if (agentReady && !"running".equals(active.mcp().state())) {
                    int ready = DevServerControlMain.waitForStartup(root, pid, Duration.ofMinutes(2), true, true);
                    if (ready != 0) return ready;
                }
            } else {
                var config = DevServerConfig.fromArgs(args.toArray(String[]::new));
                int preflight = DevServerPreflightMain.run(config,
                        agentReady ? port -> new DevServerPreflightMain.PortConflictChoice(
                                DevServerPreflightMain.PortConflictAction.FAIL, null)
                                   : DevServerPreflightMain::choosePortConflict,
                        PortListenerProcess::stop);
                if (preflight == DevServerPreflightMain.CANCEL_STARTUP_EXIT_CODE) return 0;
                if (preflight == DevServerPreflightMain.USE_DYNAMIC_PORT_EXIT_CODE) args.addAll(List.of("--port", "0"));
                else if (preflight != 0) return preflight;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Bootstrap cancelled");
                synchronized (owned) {
                    if (closed) throw new InterruptedException("Bootstrap cancelled");
                    pid = startDetached(root, javaCommand(DevServerMain.class.getName(), args));
                    long startedAt = ProcessHandle.of(pid).flatMap(p -> p.info().startInstant())
                            .map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis());
                    owned.set(new OwnedProcess(pid, startedAt, root));
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Bootstrap cancelled");
                int ready = DevServerControlMain.waitForStartup(root, pid, Duration.ofMinutes(2), true, true);
                if (ready != 0) return ready;
            }
        } finally {
            local.unlock();
        }
        if (background) {
            int result = agentReady || reused ? 0 : DevServerControlMain.waitForStartup(root, pid,
                    Duration.ofMinutes(2), args.contains("--no-compile-on-start"), false);
            // A running environment with application problems remains available for inspection, like fz dev today.
            owned.set(null);
            return result;
        }
        var attachment = System.console() != null ? new DevTerminalAttachment(root)
                : new DevTerminalAttachment(root, new java.io.FilterInputStream(System.in) {}, System.out,
                        true, TerminalProgress.system());
        int result = attachment.run(pid);
        if (result == DevTerminalAttachment.OWNER_DISCONNECTED_EXIT_CODE) {
            close();
            return 0;
        }
        if (result == 0) owned.set(null); // explicit detach or normal server stop
        return result;
    }

    static List<String> javaCommand(String main, List<String> args) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", ProcessUtils.isWindows() ? "java.exe" : "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        if (Runtime.version().feature() >= 24) command.add("--sun-misc-unsafe-memory-access=allow");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-D"))
                .forEach(command::add);
        command.addAll(List.of("-cp", Arrays.stream(System.getProperty("java.class.path").split(
                java.util.regex.Pattern.quote(File.pathSeparator))).map(p -> Path.of(p).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator)), main));
        command.addAll(args);
        return command;
    }

    static long startDetached(Path root, List<String> command) throws Exception {
        Path log = root.resolve(".fluxzero/dev/bootstrap.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "");
        if (ProcessUtils.isWindows()) {
            return new ProcessBuilder(command).directory(root.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("NUL")))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).start().pid();
        }
        var shell = new ArrayList<>(List.of("/bin/sh", "-c",
                "nohup \"$@\" </dev/null >>\"$0\" 2>&1 & echo $!", log.toString()));
        shell.addAll(command);
        Process bootstrap = new ProcessBuilder(shell).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            boolean interrupted = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            try {
                while (bootstrap.isAlive() && System.nanoTime() < deadline) {
                    try { bootstrap.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (bootstrap.isAlive() || bootstrap.exitValue() != 0)
                    throw new IllegalStateException("Detached bootstrap failed. See " + log);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            try (var output = bootstrap.inputReader()) { return Long.parseLong(output.readLine().strip()); }
        } finally {
            if (bootstrap.isAlive()) bootstrap.destroyForcibly();
        }
    }

    void release() { owned.set(null); }

    @Override
    public void close() {
        OwnedProcess process;
        synchronized (owned) {
            closed = true;
            process = owned.getAndSet(null);
        }
        if (process != null) ProcessUtils.stopIfOwned(process.pid(), process.root().toString(),
                process.startedAt(), Duration.ofSeconds(3));
    }

    private record OwnedProcess(long pid, long startedAt, Path root) {}
}
