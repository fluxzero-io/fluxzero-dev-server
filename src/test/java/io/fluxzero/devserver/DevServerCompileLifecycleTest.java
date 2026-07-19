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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class DevServerCompileLifecycleTest {

    @Test
    void startsManagedFrontendWhileInitialBackendCompileIsStillRunning(@TempDir Path projectDirectory)
            throws Exception {
        installBlockingFakeMaven(projectDirectory);
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "parallel-start-test", null,
                false, true, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command("touch frontend-started; sleep 30"), null);

        try (DevServer devServer = new DevServer(config).start()) {
            assertTrue(awaitFile(projectDirectory.resolve("compile-started")));
            assertTrue(awaitFile(projectDirectory.resolve("frontend-started")));
            assertEquals("running", devServer.session().compile().state());
            assertEquals("starting", devServer.session().frontend().state());
        }
    }

    @Test
    void oneCloseInterruptsAnActiveCompileWithoutPublishingAStartupFailure(@TempDir Path projectDirectory)
            throws Exception {
        installBlockingFakeMaven(projectDirectory);
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream();
        DevServer devServer = new DevServer(
                config, ignored -> true,
                new TerminalProgress(false, new PrintStream(terminalBytes, true, StandardCharsets.UTF_8))).start();

        devServer.requestCompile(Set.of(projectDirectory.resolve("pom.xml")));
        assertTrue(awaitFile(projectDirectory.resolve("compile-started")));
        long compilePid = Long.parseLong(Files.readString(projectDirectory.resolve("compile-pid")).strip());

        long started = System.nanoTime();
        devServer.close();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Thread.sleep(100);

        String terminal = terminalBytes.toString(StandardCharsets.UTF_8);
        assertTrue(elapsedMillis < 2_000, "shutdown took " + elapsedMillis + "ms");
        assertFalse(ProcessUtils.isAlive(compilePid), "compile process is still alive: " + compilePid);
        assertEquals("stopped", devServer.session().status());
        assertEquals("stopped", devServer.session().compile().state());
        assertFalse(terminal.contains("Fluxzero dev could not start"), terminal);
        assertFalse(terminal.contains("Watching for changes"), terminal);
    }

    @Test
    void coalescesRepeatedSavesAndRecoversAfterFailedCompile(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("wait"));
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        try (DevServer devServer = new DevServer(config).start()) {
            devServer.requestCompile(Set.of(projectDirectory.resolve("pom.xml")));
            assertTrue(awaitFile(projectDirectory.resolve("started-1")));

            devServer.requestCompile(Set.of(projectDirectory.resolve("src/main/java/com/acme/OrderHandler.java")));
            devServer.requestCompile(Set.of(projectDirectory.resolve("src/main/java/com/acme/PaymentHandler.java")));
            Files.createFile(projectDirectory.resolve("release"));

            assertTrue(awaitRunCount(projectDirectory, 2));
            assertTrue(awaitCompileDetailStartingWith(devServer, "build 2 ready"));

            Files.createFile(projectDirectory.resolve("fail"));
            devServer.requestCompile(Set.of(projectDirectory.resolve("src/main/java/com/acme/Broken.java")));

            assertTrue(awaitRunCount(projectDirectory, 3));
            assertTrue(awaitCompileState(devServer, "failed"));
            assertTrue(awaitCompileProblem(projectDirectory, true));

            Files.delete(projectDirectory.resolve("fail"));
            devServer.requestCompile(Set.of(projectDirectory.resolve("src/main/java/com/acme/Fixed.java")));

            assertTrue(awaitRunCount(projectDirectory, 4));
            assertTrue(awaitCompileDetailStartingWith(devServer, "build 3 ready"));
            assertTrue(awaitCompileProblem(projectDirectory, false));
        }

        assertEquals("4", Files.readString(projectDirectory.resolve("run-count.txt")).strip());
        String compileRuns = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(compileRuns.contains("1 --batch-mode --no-transfer-progress test-compile dependency:build-classpath"),
                   compileRuns);
        assertTrue(compileRuns.lines().noneMatch(line -> line.contains("compile test-compile")), compileRuns);
        assertTrue(compileRuns.contains("2 --batch-mode --no-transfer-progress compile"), compileRuns);
        assertTrue(compileRuns.contains("3 --batch-mode --no-transfer-progress compile"), compileRuns);
        assertTrue(compileRuns.contains("4 --batch-mode --no-transfer-progress compile"), compileRuns);
        assertTrue(compileRuns.lines().filter(line -> line.startsWith("2 ")).noneMatch(line -> line.contains("test-compile")),
                   compileRuns);
        assertTrue(compileRuns.lines().filter(line -> line.startsWith("3 ")).noneMatch(line -> line.contains("test-compile")),
                   compileRuns);
        assertTrue(compileRuns.lines().filter(line -> line.startsWith("4 ")).noneMatch(line -> line.contains("test-compile")),
                   compileRuns);
    }

    private static void installFakeMaven(Path projectDirectory) throws Exception {
        Path mvnw = projectDirectory.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                set -eu
                count_file="$PWD/run-count.txt"
                count=0
                if [ -f "$count_file" ]; then
                  count="$(cat "$count_file")"
                fi
                count=$((count + 1))
                echo "$count" > "$count_file"
                echo "$count $*" >> "$PWD/compile-runs.log"
                touch "$PWD/started-$count"
                if [ -f "$PWD/wait" ]; then
                  while [ ! -f "$PWD/release" ]; do
                    sleep 0.05
                  done
                fi
                if [ -f "$PWD/fail" ]; then
                  echo "simulated compile failure"
                  exit 7
                fi
                mkdir -p "$PWD/target/classes" "$PWD/target/fluxzero-dev"
                for arg in "$@"; do
                  case "$arg" in
                    -Dmdep.outputFile=*)
                      cp_file="${arg#-Dmdep.outputFile=}"
                      mkdir -p "$(dirname "$cp_file")"
                      : > "$cp_file"
                      ;;
                  esac
                done
                exit 0
                """);
        assertTrue(mvnw.toFile().setExecutable(true));
    }

    private static void installBlockingFakeMaven(Path projectDirectory) throws Exception {
        Path mvnw = projectDirectory.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                echo "$$" > "$PWD/compile-pid"
                touch "$PWD/compile-started"
                trap '' INT TERM
                while true; do
                  sleep 1
                done
                """);
        assertTrue(mvnw.toFile().setExecutable(true));
    }

    private static boolean awaitRunCount(Path projectDirectory, int expected) throws Exception {
        return await(() -> {
            Path file = projectDirectory.resolve("run-count.txt");
            return Files.isRegularFile(file) && Integer.parseInt(Files.readString(file).strip()) >= expected;
        });
    }

    private static boolean awaitFile(Path file) throws Exception {
        return await(() -> Files.isRegularFile(file));
    }

    private static boolean awaitCompileState(DevServer devServer, String state) throws Exception {
        return await(() -> state.equals(devServer.session().compile().state()));
    }

    private static boolean awaitCompileDetailStartingWith(DevServer devServer, String detail) throws Exception {
        return await(() -> devServer.session().compile().detail() != null
                           && devServer.session().compile().detail().startsWith(detail));
    }

    private static boolean awaitCompileProblem(Path projectDirectory, boolean expected) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path diagnostics = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                .resolve(DevLogStore.DIAGNOSTICS_FILE);
        return await(() -> {
            if (!Files.isRegularFile(diagnostics)) {
                return false;
            }
            DevDiagnostics status = objectMapper.readValue(diagnostics.toFile(), DevDiagnostics.class);
            boolean present = status.problems().stream().anyMatch(problem -> "compile".equals(problem.source()));
            return present == expected;
        });
    }

    private static boolean await(CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
