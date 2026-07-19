/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevTerminalAttachmentTest {

    @Test
    void detachPersistsCursorAndReattachOnlyReplaysMissedOutput(@TempDir Path project) throws Exception {
        DevSession session = readySession(project);
        new DevSessionStore(project).writeSession(session);
        Path log = bootstrapLog(project);
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Fluxzero dev server ready\nOpen in browser http://localhost:4200\n");

        String firstOutput = attach(project, "d\n");

        assertTrue(firstOutput.contains("Fluxzero dev server ready"), firstOutput);
        assertTrue(firstOutput.contains("[q] quit   [d] detach   [Ctrl+C] stop\n\n"), firstOutput);
        assertTrue(firstOutput.contains("Fluxzero dev continues in the background."), firstOutput);
        DevAttachCursorStore.Cursor cursor = new DevAttachCursorStore(project)
                .read(session.sessionId()).orElseThrow();
        assertEquals(Files.size(log), cursor.offset());

        Files.writeString(log, "\nFrontend change detected\n  Changed frontend/src/app/page.ts\n",
                          java.nio.file.StandardOpenOption.APPEND);
        String secondOutput = attach(project, "detach\n");

        assertTrue(secondOutput.contains("Fluxzero dev attached"), secondOutput);
        assertTrue(secondOutput.contains("Frontend change detected"), secondOutput);
        assertFalse(secondOutput.contains("Fluxzero dev server ready\n"), secondOutput);
        assertEquals(Files.size(log), new DevAttachCursorStore(project)
                .read(session.sessionId()).orElseThrow().offset());
    }

    @Test
    void quitMenuSupportsLongAndShortDetachCommands(@TempDir Path project) throws Exception {
        DevSession session = readySession(project);
        new DevSessionStore(project).writeSession(session);
        Path log = bootstrapLog(project);
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Fluxzero dev server ready\n");

        String output = attach(project, "quit\nd\n");

        assertTrue(output.contains("What should happen to this development environment?"), output);
        assertTrue(output.contains("Keep running in background"), output);
        assertTrue(output.contains("Fluxzero dev continues in the background."), output);
    }

    @Test
    void quitMenuSupportsArrowNavigationAndEnter(@TempDir Path project) throws Exception {
        DevSession session = readySession(project);
        new DevSessionStore(project).writeSession(session);
        Path log = bootstrapLog(project);
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Fluxzero dev server ready\n");

        String output = attach(project, String.join("\n", "q", TerminalKeyReader.UP, TerminalKeyReader.DOWN,
                                                   TerminalKeyReader.DOWN, TerminalKeyReader.UP,
                                                   TerminalKeyReader.UP, TerminalKeyReader.ENTER) + "\n", true);

        assertTrue(output.contains("› Keep running in background"), output);
        assertTrue(output.contains("› Return to live view"), output);
        assertTrue(output.contains("› Stop environment and all applications"), output);
        assertTrue(output.contains("Fluxzero dev continues in the background."), output);
    }

    @Test
    void cursorFromAnotherSessionIsIgnored(@TempDir Path project) {
        DevAttachCursorStore store = new DevAttachCursorStore(project);
        store.write("old-session", 123);

        assertTrue(store.read("old-session").isPresent());
        assertTrue(store.read("new-session").isEmpty());
    }

    @Test
    void reportsStartupFailureWhenServerExitsBeforeCreatingSession(@TempDir Path project) throws Exception {
        Path log = bootstrapLog(project);
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Fluxzero dev could not start: Port 4200 is already in use.\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int exitCode;
        try (TerminalProgress progress = new TerminalProgress(false, output)) {
            exitCode = new DevTerminalAttachment(
                    project, InputStream.nullInputStream(), output, false, progress).run(Long.MAX_VALUE);
        }

        String terminal = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode);
        assertTrue(terminal.contains("Port 4200 is already in use"), terminal);
        assertFalse(terminal.contains("Fluxzero dev is not running"), terminal);
    }

    private static String attach(Path project, String input) throws Exception {
        return attach(project, input, false);
    }

    private static String attach(Path project, String input, boolean terminalMenus) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try (TerminalProgress progress = new TerminalProgress(false, output)) {
            int exitCode = new DevTerminalAttachment(
                    project, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, true, progress,
                    terminalMenus)
                    .run(ProcessHandle.current().pid());
            assertEquals(0, exitCode);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static DevSession readySession(Path project) {
        DevSession session = DevSession.empty(DevServerConfig.defaults(project));
        return session.withStatus("running")
                .withRuntime(DevSession.ServiceStatus.running(
                        "runtime", "ws://localhost:1234", 1234, null, "embedded"))
                .withProxy(DevSession.ServiceStatus.running(
                        "proxy", "http://localhost:1235", 1235, null, "embedded"))
                .withGateway(DevSession.ServiceStatus.running(
                        "gateway", "http://localhost:4200", 4200, null, "public"))
                .withApp(DevSession.ServiceStatus.running("app", null, null, null, "ready"))
                .withReload(DevSession.ServiceStatus.running("reload", null, null, null, "ready")
                                    .withState("succeeded", "ready"));
    }

    private static Path bootstrapLog(Path project) {
        return project.resolve(DevSessionStore.DEV_DIRECTORY).resolve("bootstrap.log");
    }
}
