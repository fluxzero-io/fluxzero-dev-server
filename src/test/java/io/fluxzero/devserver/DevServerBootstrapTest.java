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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DevServerBootstrapTest {
    @TempDir Path directory;

    @Test
    void rejectsNonProjectRootWithoutStartingServices() throws Exception {
        Files.writeString(directory.resolve("brief.md"), "existing content");
        try (var bootstrap = new DevServerBootstrap()) {
            assertThrows(DevServerStartupException.class, () -> bootstrap.run(directory, List.of(), true, true));
            assertFalse(Files.exists(directory.resolve(".fluxzero/dev/session.json")));
        }
    }

    @Test
    void separateBootstrapProcessesShareOneEnvironment() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Process first = process(root, true), second = process(root, true);
        try {
            first.getOutputStream().close(); second.getOutputStream().close();
            assertTrue(first.waitFor(25, TimeUnit.SECONDS));
            assertTrue(second.waitFor(25, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue()); assertEquals(0, second.exitValue());
            DevSession session = new DevSessionStore(root).readSession().orElseThrow();
            try (var bootstrap = new DevServerBootstrap()) {
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> assertEquals(0, bootstrap.run(root, List.of(), true, false)));
            }
            assertEquals(session.sessionId(), new DevSessionStore(root).readSession().orElseThrow().sessionId());
            assertTrue(ProcessUtils.isAlive(session.pid(), session.startedAt()));
        } finally { first.destroyForcibly(); second.destroyForcibly(); stop(root); }
    }

    @Test
    void waitsForLegacyCliEnsureLockBeforeStarting() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Path lockFile = root.resolve(".fluxzero/dev/ensure.lock");
        Files.createDirectories(lockFile.getParent());
        Process process = null;
        try (var channel = java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            var lock = channel.lock();
            try {
                process = process(root, true);
                process.getOutputStream().close();
                assertFalse(process.waitFor(2, TimeUnit.SECONDS));
                assertFalse(Files.exists(root.resolve(".fluxzero/dev/session.json")));
            } finally { lock.release(); }
            assertTrue(process.waitFor(25, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertTrue(new DevSessionStore(root).readSession().isPresent());
        } finally { if (process != null) process.destroyForcibly(); stop(root); }
    }

    @Test
    void attachedOwnerEofStopsTheEnvironment() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Process process = process(root, false);
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(25, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            DevSession session = new DevSessionStore(root).readSession().orElseThrow();
            assertFalse(ProcessUtils.isAlive(session.pid(), session.startedAt()));
        } finally { process.destroyForcibly(); stop(root); }
    }

    @Test
    void explicitDetachPreservesEnvironment() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Process process = process(root, false);
        try {
            process.getOutputStream().write("d\n".getBytes());
            process.getOutputStream().flush();
            assertTrue(process.waitFor(25, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            DevSession session = new DevSessionStore(root).readSession().orElseThrow();
            assertTrue(ProcessUtils.isAlive(session.pid(), session.startedAt()));
        } finally { process.destroyForcibly(); stop(root); }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Process.destroy uses TerminateProcess, not graceful SIGTERM; EOF cleanup is covered on every OS")
    void terminatingAttachedBootstrapStopsItsServer() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Process process = process(root, false);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            var store = new DevSessionStore(root);
            while (store.readSession().filter(s -> "running".equals(s.mcp().state())).isEmpty()
                    && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(25);
            DevSession session = store.readSession().orElseThrow();
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertFalse(ProcessUtils.isAlive(session.pid(), session.startedAt()));
        } finally { process.destroyForcibly(); stop(root); }
    }

    private Process process(Path root, boolean background) throws Exception {
        var args = new java.util.ArrayList<>(List.of("--project-dir", root.toString(), "--bootstrap-agent-ready"));
        if (background) args.add("--bootstrap-background");
        return new ProcessBuilder(DevServerBootstrap.javaCommand(DevServerBootstrapMain.class.getName(), args))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve("bootstrap-test.log").toFile())).start();
    }

    private void stop(Path root) {
        new DevSessionStore(root).readSession().ifPresent(s ->
                ProcessUtils.stopIfOwned(s.pid(), root.toString(), s.startedAt(), Duration.ofSeconds(3)));
    }
}
