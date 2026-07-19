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

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TerminalProgress implements AutoCloseable {
    private static final String CLEAR_LINE = "\r\033[2K";
    private static final String CURSOR_UP = "\033[1A";
    private static final String RESET = "\033[0m";
    private static final String BOLD_GREEN = "\033[1;32m";
    private static final String BOLD_RED = "\033[1;31m";
    private static final String CYAN = "\033[36m";
    private static final String DIM = "\033[2m";
    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int DETAIL_LABEL_WIDTH = 14;
    private static final char[] FRAMES = {'|', '/', '-', '\\'};
    private static final Pattern LOG_LOCATION = Pattern.compile("^(\\s+Details\\s+)(.+\\.log):(\\d+)\\s*$");

    private final boolean enabled;
    private final PrintStream output;
    private final Clock clock;
    private final boolean jetBrainsTerminal;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private volatile Progress progress;
    private Thread renderer;
    private int frame;
    private int renderedLines;

    static TerminalProgress system() {
        String term = System.getenv("TERM");
        boolean interactive = System.console() != null && !"dumb".equalsIgnoreCase(term)
                              && System.getenv("CI") == null;
        return new TerminalProgress(interactive, System.out);
    }

    TerminalProgress(boolean enabled, PrintStream output) {
        this(enabled, output, Clock.systemDefaultZone(), isJetBrainsTerminal());
    }

    TerminalProgress(boolean enabled, PrintStream output, Clock clock) {
        this(enabled, output, clock, isJetBrainsTerminal());
    }

    TerminalProgress(boolean enabled, PrintStream output, Clock clock, boolean jetBrainsTerminal) {
        this.enabled = enabled;
        this.output = output;
        this.clock = clock;
        this.jetBrainsTerminal = jetBrainsTerminal;
    }

    synchronized void start(String message) {
        if (!enabled || closed.get()) {
            return;
        }
        long now = System.nanoTime();
        progress = new Progress(message, now, now);
        ensureRenderer();
        render();
    }

    synchronized void update(String message) {
        if (!enabled || closed.get()) {
            return;
        }
        Progress current = progress;
        long now = System.nanoTime();
        progress = current == null
                ? new Progress(message, now, now)
                : new Progress(message, current.startedNanos(),
                               message.equals(current.message()) ? current.stepStartedNanos() : now);
        ensureRenderer();
        render();
    }

    synchronized void stop() {
        if (progress != null) {
            clearLine();
            progress = null;
        }
        tasks.clear();
    }

    synchronized void updateTask(String id, String label, String message) {
        if (!enabled || closed.get()) {
            return;
        }
        long now = System.nanoTime();
        Task current = tasks.get(id);
        tasks.put(id, current != null && current.message().equals(message)
                ? current : new Task(label, message, now));
        if (progress != null) {
            render();
        }
    }

    synchronized void removeTask(String id) {
        if (!enabled || closed.get() || tasks.remove(id) == null) {
            return;
        }
        if (progress != null) {
            render();
        }
    }

    synchronized void println(String line) {
        if (closed.get()) {
            return;
        }
        if (progress != null) {
            clearLine();
        }
        output.println(line);
        if (progress != null) {
            render();
        }
    }

    synchronized void printReplayedLine(String line) {
        printReplayedLine(line, null);
    }

    synchronized void printReplayedLine(String line, Path projectDirectory) {
        if (closed.get()) {
            return;
        }
        if (progress != null) {
            clearLine();
        }
        String ansi = replayStyle(line);
        String rendered = hyperlinkLogLocation(line, projectDirectory);
        output.println(ansi == null ? rendered : style(ansi, rendered));
        if (progress != null) {
            render();
        }
    }

    synchronized void printControlHints() {
        if (closed.get()) {
            return;
        }
        prepareBlock();
        output.printf("%s[q]%s quit   %s[d]%s detach   %s[Ctrl+C]%s stop%n%n",
                      enabled ? CYAN : "", enabled ? RESET : "",
                      enabled ? CYAN : "", enabled ? RESET : "",
                      enabled ? CYAN : "", enabled ? RESET : "");
        resumeProgress();
    }

    synchronized void printBlock(List<String> lines) {
        if (closed.get()) {
            return;
        }
        if (progress != null) {
            clearLine();
        }
        lines.forEach(output::println);
        if (progress != null) {
            render();
        }
    }

    synchronized void printReady(String title, String target) {
        if (closed.get()) {
            return;
        }
        prepareBlock();
        output.println(style(BOLD_GREEN, title));
        printDetails(List.of("Time: " + currentTime(), target), target);
        output.println();
        resumeProgress();
    }

    synchronized void printFailure(String title, List<String> details) {
        if (closed.get()) {
            return;
        }
        prepareBlock();
        output.println(style(BOLD_RED, title));
        printDetails(withTime(details), null);
        output.println();
        resumeProgress();
    }

    synchronized void printActivity(String title, List<String> details) {
        printStyledBlock(CYAN, title, details);
    }

    synchronized void printSuccess(String title, List<String> details) {
        printStyledBlock(BOLD_GREEN, title, details);
    }

    private void printStyledBlock(String style, String title, List<String> details) {
        if (closed.get()) {
            return;
        }
        prepareBlock();
        output.println(style(style, title));
        printDetails(withTime(details), null);
        output.println();
        resumeProgress();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stop();
        if (renderer != null) {
            renderer.interrupt();
        }
    }

    private void ensureRenderer() {
        if (renderer != null) {
            return;
        }
        renderer = Thread.ofPlatform().daemon(true).name("fluxzero-dev-terminal-progress").start(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (this) {
                    if (progress != null) {
                        render();
                    }
                }
            }
        });
    }

    private void render() {
        Progress current = progress;
        if (current == null) {
            return;
        }
        long now = System.nanoTime();
        long elapsedMillis = Duration.ofNanos(now - current.startedNanos()).toMillis();
        long stepElapsedMillis = Duration.ofNanos(now - current.stepStartedNanos()).toMillis();
        clearRenderedLines();
        output.print(CLEAR_LINE);
        Message message = Message.parse(current.message());
        output.printf(Locale.ROOT, "%s%c%s %s %s(%s total)%s", CYAN, FRAMES[frame++ % FRAMES.length], RESET,
                      message.title(), DIM, progressSeconds(elapsedMillis), RESET);
        renderedLines = 1;
        if (message.detail() != null) {
            nextLine();
            output.printf("  %s%s (%s)%s", DIM, message.detail(), progressSeconds(stepElapsedMillis), RESET);
        }
        for (Task task : tasks.values()) {
            nextLine();
            long taskElapsedMillis = Duration.ofNanos(now - task.startedNanos()).toMillis();
            output.printf("  %s%s: %s (%s)%s", DIM, task.label(), task.message(),
                          progressSeconds(taskElapsedMillis), RESET);
        }
        output.flush();
    }

    private void nextLine() {
        output.print("\n");
        output.print(CLEAR_LINE);
        renderedLines++;
    }

    private void prepareBlock() {
        if (progress != null) {
            clearLine();
        }
    }

    private void resumeProgress() {
        if (progress != null) {
            render();
        }
    }

    private String style(String ansi, String value) {
        return enabled ? ansi + value + RESET : value;
    }

    private static String replayStyle(String line) {
        if (line.startsWith("Fluxzero dev server ready") || line.startsWith("Fluxzero dev ready")
            || line.equals("Backend ready")
            || line.equals("Tests passed")) {
            return BOLD_GREEN;
        }
        if (line.equals("Fluxzero dev could not start") || line.equals("Tests failed")
            || line.equals("Application error")) {
            return BOLD_RED;
        }
        if (line.equals("Backend change detected") || line.equals("Frontend change detected")
            || line.equals("Tests started") || line.equals("Tests queued")
            || line.startsWith("  Open in browser ") || line.startsWith("  Backend ")
            || line.stripLeading().startsWith("Details ")) {
            return CYAN;
        }
        return null;
    }

    String currentTime() {
        return LocalTime.now(clock).format(CLOCK_TIME);
    }

    private List<String> withTime(List<String> details) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(details.size() + 1);
        result.add("Time: " + currentTime());
        result.addAll(details);
        return result;
    }

    private void printDetails(List<String> details, String highlightedDetail) {
        int longestLabel = details.stream().mapToInt(TerminalProgress::detailLabelLength).max().orElse(0);
        int labelWidth = Math.max(DETAIL_LABEL_WIDTH, longestLabel + 1);
        for (String detail : details) {
            int separator = detail.indexOf(": ");
            if (separator < 0) {
                output.println("  " + detail);
                continue;
            }
            String label = detail.substring(0, separator);
            String value = detail.substring(separator + 2);
            String line = String.format(Locale.ROOT, "  %-" + labelWidth + "s%s", label, value);
            output.println(detail.equals(highlightedDetail) ? style(CYAN, line) : line);
        }
    }

    private static int detailLabelLength(String detail) {
        int separator = detail.indexOf(": ");
        return separator < 0 ? 0 : separator;
    }

    private String hyperlinkLogLocation(String line, Path projectDirectory) {
        if (!enabled || projectDirectory == null) {
            return line;
        }
        Matcher matcher = LOG_LOCATION.matcher(line);
        if (!matcher.matches()) {
            return line;
        }
        Path displayedPath;
        try {
            displayedPath = Path.of(matcher.group(2));
        } catch (RuntimeException ignored) {
            return line;
        }
        Path absolutePath = (displayedPath.isAbsolute() ? displayedPath : projectDirectory.resolve(displayedPath))
                .toAbsolutePath().normalize();
        String location = matcher.group(2) + ":" + matcher.group(3);
        String fileUri = absolutePath.toUri().toASCIIString();
        if (jetBrainsTerminal) {
            return matcher.group(1) + fileUri + ":" + matcher.group(3) + ":1";
        }
        String target = fileUri + "#" + matcher.group(3);
        return matcher.group(1) + "\033]8;;" + target + "\033\\" + location + "\033]8;;\033\\";
    }

    private static boolean isJetBrainsTerminal() {
        String emulator = System.getenv("TERMINAL_EMULATOR");
        return emulator != null && emulator.toLowerCase(Locale.ROOT).contains("jetbrains");
    }

    private void clearLine() {
        clearRenderedLines();
        output.flush();
    }

    private void clearRenderedLines() {
        if (renderedLines == 0) {
            return;
        }
        output.print(CLEAR_LINE);
        for (int i = 1; i < renderedLines; i++) {
            output.print(CURSOR_UP);
            output.print(CLEAR_LINE);
        }
        renderedLines = 0;
    }

    private static String progressSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1_000.0);
    }

    private record Progress(String message, long startedNanos, long stepStartedNanos) {
    }

    private record Task(String label, String message, long startedNanos) {
    }

    private record Message(String title, String detail) {
        private static Message parse(String value) {
            int separator = value.indexOf(": ");
            return separator < 0
                    ? new Message(value, null)
                    : new Message(value.substring(0, separator), value.substring(separator + 2));
        }
    }
}
