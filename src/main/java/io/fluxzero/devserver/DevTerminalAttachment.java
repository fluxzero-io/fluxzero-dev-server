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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class DevTerminalAttachment {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final String INPUT_CLOSED = "\u0000-input-closed";

    private final Path projectDirectory;
    private final DevSessionStore sessionStore;
    private final DevAttachCursorStore cursorStore;
    private final InputStream input;
    private final PrintStream output;
    private final TerminalProgress progress;
    private final boolean interactive;
    private final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
    private volatile TerminalKeyReader keyReader;
    private volatile boolean terminalMenus;

    DevTerminalAttachment(Path projectDirectory) {
        this(projectDirectory, System.in, System.out, System.console() != null, TerminalProgress.system());
    }

    DevTerminalAttachment(Path projectDirectory, InputStream input, PrintStream output, boolean interactive,
                          TerminalProgress progress) {
        this(projectDirectory, input, output, interactive, progress, false);
    }

    DevTerminalAttachment(Path projectDirectory, InputStream input, PrintStream output, boolean interactive,
                          TerminalProgress progress, boolean terminalMenus) {
        this.projectDirectory = projectDirectory.toAbsolutePath().normalize();
        this.sessionStore = new DevSessionStore(this.projectDirectory);
        this.cursorStore = new DevAttachCursorStore(this.projectDirectory);
        this.input = input;
        this.output = output;
        this.interactive = interactive;
        this.progress = progress;
        this.terminalMenus = terminalMenus;
    }

    int run(long expectedPid) throws Exception {
        Path bootstrapLog = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("bootstrap.log");
        Optional<DevSession> initial = awaitSession(expectedPid, Duration.ofSeconds(5));
        if (initial.isEmpty()) {
            if (!replayStartupFailure(bootstrapLog)) {
                output.println("Fluxzero dev server exited before startup completed.");
                output.println("Log: " + projectDirectory.relativize(bootstrapLog));
            }
            return 2;
        }
        DevSession session = initial.get();
        waitForLogFile(bootstrapLog, expectedPid);
        long offset = cursorStore.read(session.sessionId()).map(DevAttachCursorStore.Cursor::offset).orElse(0L);
        long length = Files.size(bootstrapLog);
        if (offset > length) {
            offset = 0;
        }
        boolean resumed = offset > 0;
        boolean ready = applicationReady(session);
        boolean failed = startupFailed(session);
        if (!ready && !failed) {
            progress.start("Starting Fluxzero dev environment");
        } else if (resumed) {
            printAttached(session);
        }
        startInputReader();
        try (RandomAccessFile log = new RandomAccessFile(bootstrapLog.toFile(), "r")) {
            log.seek(offset);
            boolean controlsShown = false;
            while (true) {
                String line;
                while ((line = log.readLine()) != null) {
                    String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                    progress.printReplayedLine(decoded, projectDirectory);
                    cursorStore.write(session.sessionId(), log.getFilePointer());
                }
                Optional<DevSession> current = sessionStore.reconcileUnexpectedStop();
                if (current.isPresent() && current.get().sessionId().equals(session.sessionId())) {
                    session = current.get();
                    ready = applicationReady(session);
                    failed = startupFailed(session);
                    if (ready || failed) {
                        progress.stop();
                        if (interactive && !controlsShown) {
                            printControls();
                            controlsShown = true;
                        }
                    } else {
                        updateStartupProgress(session);
                    }
                }
                String command = commands.poll();
                if (INPUT_CLOSED.equals(command)) {
                    progress.stop();
                    printDetached();
                    return 0;
                }
                if (command != null) {
                    Action action = handle(command);
                    if (action == Action.DETACH) {
                        progress.stop();
                        printDetached();
                        return 0;
                    }
                    if (action == Action.STOP) {
                        progress.stop();
                        return stopEnvironment(session);
                    }
                }
                if (!active(session)) {
                    progress.stop();
                    return 0;
                }
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
            }
        } finally {
            closeInputReader();
            progress.close();
        }
    }

    private Optional<DevSession> awaitSession(long expectedPid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<DevSession> session = sessionStore.readSession().filter(current -> expectedPid < 1
                    || current.pid() == expectedPid);
            if (session.isPresent()) {
                return session;
            }
            if (expectedPid > 0 && !ProcessUtils.isAlive(expectedPid)) {
                return Optional.empty();
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
        }
        return Optional.empty();
    }

    private boolean replayStartupFailure(Path bootstrapLog) {
        if (!Files.isRegularFile(bootstrapLog)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(bootstrapLog, StandardCharsets.UTF_8);
            if (lines.stream().noneMatch(line -> !line.isBlank())) {
                return false;
            }
            lines.forEach(line -> progress.printReplayedLine(line, projectDirectory));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void waitForLogFile(Path logFile, long expectedPid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.isRegularFile(logFile) && System.nanoTime() < deadline) {
            if (expectedPid > 0 && !ProcessUtils.isAlive(expectedPid)) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
        }
        if (!Files.isRegularFile(logFile)) {
            throw new IllegalStateException("Dev bootstrap log does not exist yet: " + logFile);
        }
    }

    private void startInputReader() {
        if (!interactive) {
            return;
        }
        if (input == System.in && output == System.out) {
            try {
                keyReader = TerminalKeyReader.open(commands);
                terminalMenus = true;
                keyReader.start(() -> commands.offer(INPUT_CLOSED));
                return;
            } catch (Exception ignored) {
                closeInputReader();
                // Fall back to line input when the current terminal cannot enter raw mode.
            }
        }
        Thread.ofPlatform().daemon(true).name("fluxzero-dev-attach-input").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    commands.offer(line.strip());
                }
            } catch (IOException ignored) {
                // A closed terminal simply detaches the client.
            } finally {
                commands.offer(INPUT_CLOSED);
            }
        });
    }

    private void closeInputReader() {
        TerminalKeyReader current = keyReader;
        keyReader = null;
        if (current != null) {
            current.close();
        }
    }

    private Action handle(String command) throws InterruptedException {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "d", "detach" -> Action.DETACH;
            case "q", "quit" -> quitMenu();
            case TerminalKeyReader.INTERRUPT -> Action.STOP;
            case "stop" -> confirmStop();
            case "help", "?" -> {
                printControls();
                yield Action.CONTINUE;
            }
            case "" -> Action.CONTINUE;
            default -> {
                output.println("Unknown attach command '" + command + "'. Use d, detach, q, quit, stop or help.");
                yield Action.CONTINUE;
            }
        };
    }

    private Action quitMenu() throws InterruptedException {
        output.println();
        output.println("What should happen to this development environment?");
        output.println();
        if (!terminalMenus) {
            return lineQuitMenu();
        }
        List<MenuOption> options = List.of(
                new MenuOption("Keep running in background", Action.DETACH),
                new MenuOption("Stop environment and all applications", Action.STOP),
                new MenuOption("Return to live view", Action.CONTINUE));
        int selected = 0;
        printMenu(options, selected, false);
        while (true) {
            String choice = commands.take();
            switch (choice) {
                case TerminalKeyReader.UP -> {
                    selected = (selected + options.size() - 1) % options.size();
                    printMenu(options, selected, true);
                }
                case TerminalKeyReader.DOWN -> {
                    selected = (selected + 1) % options.size();
                    printMenu(options, selected, true);
                }
                case TerminalKeyReader.ENTER -> {
                    output.println();
                    Action action = options.get(selected).action();
                    if (action == Action.CONTINUE) {
                        resumeLineInput();
                    }
                    return action;
                }
                case "d", "D" -> {
                    return Action.DETACH;
                }
                case "s", "S", TerminalKeyReader.INTERRUPT -> {
                    return Action.STOP;
                }
                case "c", "C", "q", "Q", TerminalKeyReader.ESCAPE -> {
                    output.println();
                    resumeLineInput();
                    return Action.CONTINUE;
                }
                case INPUT_CLOSED -> {
                    return Action.DETACH;
                }
                default -> { }
            }
        }
    }

    private Action lineQuitMenu() throws InterruptedException {
        output.println("  [d] Keep running in background");
        output.println("  [s] Stop environment and all applications");
        output.println("  [c] Return to live view");
        output.print("Choice: ");
        output.flush();
        while (true) {
            String choice = commands.take().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "d", "detach", "1" -> { return Action.DETACH; }
                case "s", "stop", "2" -> { return Action.STOP; }
                case "c", "cancel", "3", "", "q", "quit" -> {
                    output.println("Continuing live view.");
                    return Action.CONTINUE;
                }
                default -> {
                    output.print("Choose d, s or c: ");
                    output.flush();
                }
            }
        }
    }

    private void resumeLineInput() {
        TerminalKeyReader current = keyReader;
        if (current == null) {
            terminalMenus = false;
        } else {
            current.resumeLineInput();
        }
    }

    private void printMenu(List<MenuOption> options, int selected, boolean redraw) {
        if (redraw) {
            output.printf("\u001b[%dA", options.size());
        }
        for (int i = 0; i < options.size(); i++) {
            if (redraw) {
                output.print("\r\u001b[2K");
            }
            output.println((i == selected ? "\u001b[36m› " : "  ") + options.get(i).label()
                           + (i == selected ? "\u001b[0m" : ""));
        }
        output.flush();
    }

    private Action confirmStop() throws InterruptedException {
        output.print("Stop Fluxzero dev and all running applications? [y/N] ");
        output.flush();
        String confirmation = commands.take().toLowerCase(Locale.ROOT);
        return confirmation.equals("y") || confirmation.equals("yes") ? Action.STOP : Action.CONTINUE;
    }

    private int stopEnvironment(DevSession session) throws Exception {
        output.println(DevServerMain.STOPPING_MESSAGE);
        output.flush();
        boolean owned = ProcessUtils.stopIfCommandLineContains(
                session.pid(), session.projectDirectory(), Duration.ofSeconds(2));
        if (!owned && ProcessUtils.isAlive(session.pid())) {
            throw new IllegalStateException("Refusing to stop PID " + session.pid()
                                            + " because it is not owned by this project");
        }
        sessionStore.reconcileUnexpectedStop();
        long deadline = System.nanoTime() + Duration.ofMillis(750).toNanos();
        while (ProcessUtils.isAlive(session.pid()) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(25);
        }
        output.println(DevServerMain.STOPPED_MESSAGE);
        return 0;
    }

    private void printControls() {
        progress.printControlHints();
    }

    private void printDetached() {
        output.println();
        output.println("Fluxzero dev continues in the background.");
        output.println();
        output.println("Attach  fz dev attach");
        output.println("Status  fz dev status");
        output.println("Logs    fz dev logs --follow");
        output.println("Stop    fz dev stop");
    }

    private void printAttached(DevSession session) {
        String target = publicUrl(session).map(url -> "Open in browser: " + url)
                .orElse("Status: running");
        progress.printReady("Fluxzero dev attached", target);
    }

    private void updateStartupProgress(DevSession session) {
        if ("running".equals(session.compile().state())) {
            progress.update("Building: Backend: " + value(session.compile().detail(), "compiling"));
        } else if ("starting".equals(session.frontend().state())) {
            progress.update("Starting Fluxzero dev environment: Frontend: "
                            + value(session.frontend().detail(), "starting"));
        } else if ("starting".equals(session.app().state()) || "running".equals(session.reload().state())) {
            progress.update("Starting applications");
        } else {
            progress.update("Starting Fluxzero dev environment");
        }
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean active(DevSession session) {
        return !"stopped".equals(session.status()) && !"stopped-unexpectedly".equals(session.status())
               && ProcessUtils.isAlive(session.pid());
    }

    private static boolean applicationReady(DevSession session) {
        boolean frontendReady = "skipped".equals(session.gateway().state())
                                || "stopped".equals(session.frontend().state())
                                || "running".equals(session.frontend().state());
        return "running".equals(session.runtime().state()) && "running".equals(session.proxy().state())
               && "running".equals(session.app().state()) && "succeeded".equals(session.reload().state())
               && frontendReady;
    }

    private static boolean startupFailed(DevSession session) {
        return "failed".equals(session.compile().state()) || "failed".equals(session.reload().state())
               || "degraded".equals(session.reload().state()) || "failed".equals(session.frontend().state())
               || "exited".equals(session.frontend().state());
    }

    private static Optional<String> publicUrl(DevSession session) {
        if ("running".equals(session.gateway().state()) && session.gateway().url() != null) {
            return Optional.of(session.gateway().url());
        }
        return Optional.ofNullable(session.proxy().url());
    }

    private enum Action {
        CONTINUE, DETACH, STOP
    }

    private record MenuOption(String label, Action action) {
    }
}
