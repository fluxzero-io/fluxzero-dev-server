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
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.fluxzero.devserver.DevLogEvent.Level.DEBUG;
import static io.fluxzero.devserver.DevLogEvent.Level.ERROR;
import static io.fluxzero.devserver.DevLogEvent.Level.INFO;
import static io.fluxzero.devserver.DevLogEvent.Level.TRACE;
import static io.fluxzero.devserver.DevLogEvent.Level.WARN;

final class DevLogStore implements AutoCloseable {
    static final String LOGS_DIRECTORY = "logs";
    static final String COMBINED_LOG_FILE = "dev.log";
    static final String EVENTS_FILE = "events.ndjson";
    static final String PROBLEMS_FILE = "problems.ndjson";
    static final String DIAGNOSTICS_FILE = "diagnostics.json";
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int MAX_ARCHIVES = 2;
    private static final int RETAINED_SESSIONS = 5;
    private static final int MAX_DETAIL_LENGTH = 8_000;
    private static final Pattern SOURCE_PREFIX = Pattern.compile("^\\[([a-zA-Z0-9_.-]+)]\\s?(.*)$");
    private static final Pattern LEVEL = Pattern.compile(
            "(?i)(?:^|\\s|\\[)(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)(?:]|:|\\s)");

    private final String sessionId;
    private final String defaultApplicationName;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper lineMapper = new ObjectMapper();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, DevProblem> activeProblems = new LinkedHashMap<>();
    private final List<Runnable> diagnosticsListeners = new ArrayList<>();
    private final Path sessionDirectory;
    private final Path combinedLog;
    private final Path eventsFile;
    private final Path problemsFile;
    private final Path diagnosticsFile;
    private final long maxFileSize;
    private final int maxArchives;
    private long combinedLogLines;
    private long lastCombinedLogLine;
    private boolean closed;

    DevLogStore(Path projectDirectory, String sessionId, String defaultApplicationName) {
        this(projectDirectory, sessionId, defaultApplicationName, MAX_FILE_SIZE, MAX_ARCHIVES);
    }

    DevLogStore(Path projectDirectory, String sessionId, String defaultApplicationName, long maxFileSize,
                int maxArchives) {
        if (maxFileSize < 1 || maxArchives < 1) {
            throw new IllegalArgumentException("Log file size and archive count must be positive");
        }
        this.sessionId = sessionId;
        this.defaultApplicationName = defaultApplicationName;
        this.maxFileSize = maxFileSize;
        this.maxArchives = maxArchives;
        Path devDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY);
        this.sessionDirectory = devDirectory.resolve(LOGS_DIRECTORY).resolve(sessionId);
        this.combinedLog = sessionDirectory.resolve(COMBINED_LOG_FILE);
        this.eventsFile = sessionDirectory.resolve(EVENTS_FILE);
        this.problemsFile = sessionDirectory.resolve(PROBLEMS_FILE);
        this.diagnosticsFile = devDirectory.resolve(DIAGNOSTICS_FILE);
        try {
            Files.createDirectories(sessionDirectory);
            retainRecentSessions(sessionDirectory.getParent(), sessionDirectory);
            combinedLogLines = countLines(combinedLog);
            writeDiagnostics();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Fluxzero dev logging in " + sessionDirectory, e);
        }
    }

    synchronized void accept(String line) {
        if (closed) {
            return;
        }
        ParsedLine parsed = parse(line);
        ServiceIdentity identity = identity(parsed.source());
        log(parsed.level(), parsed.source(), identity.serviceType(), identity.serviceId(), null, null,
            parsed.stream(), parsed.message());
    }

    synchronized LogPosition process(String source, String serviceType, String serviceId, String instanceId,
                                     String stream, String line) {
        if (closed) {
            return new LogPosition(combinedLog, Math.max(1, lastCombinedLogLine));
        }
        log(inferLevel(line), source, serviceType, serviceId, instanceId, null, stream, line);
        return new LogPosition(combinedLog, lastCombinedLogLine);
    }

    synchronized void embedded(String source, String serviceType, String serviceId, DevLogEvent.Level level,
                               String message) {
        if (closed) {
            return;
        }
        log(level, source, serviceType, serviceId, null, null, "embedded", message);
    }

    synchronized void observeStatus(String source, String serviceType, String serviceId, String instanceId,
                                    String state, String detail) {
        if (closed) {
            return;
        }
        String normalizedState = state == null ? "unknown" : state;
        boolean failed = "failed".equals(normalizedState) || "exited".equals(normalizedState);
        DevLogEvent event = writeEvent(failed ? ERROR : INFO, source, serviceType, serviceId, instanceId, null,
                                       "lifecycle", normalizedState + formatDetail(detail));
        String key = statusKey(serviceType, serviceId, instanceId);
        if (failed) {
            upsertProblem(key, ERROR, "status", event, normalizedState, detail);
        } else if (isResolutionState(serviceType, normalizedState)) {
            resolveProblems(problem -> problem.id().equals(problemId(key)), normalizedState);
            if (!"application".equals(serviceType)) {
                resolveProblems(problem -> "log".equals(problem.category())
                                           && Objects.equals(serviceType, problem.serviceType())
                                           && Objects.equals(serviceId, problem.serviceId())
                                           && (instanceId == null || Objects.equals(instanceId,
                                                                                     problem.instanceId())),
                                normalizedState);
            }
        }
    }

    synchronized void resolveInstance(String serviceId, String instanceId, String reason) {
        if (closed || instanceId == null) {
            return;
        }
        resolveProblems(problem -> Objects.equals(serviceId, problem.serviceId())
                                   && Objects.equals(instanceId, problem.instanceId()), reason);
    }

    synchronized List<DevLogEvent> readEvents(long afterSequence, int requestedLimit, String serviceId,
                                              DevLogEvent.Level minimumLevel) {
        return readEvents(afterSequence, requestedLimit,
                          event -> (serviceId == null || serviceId.equals(event.serviceId()))
                                   && (minimumLevel == null || event.level().atLeast(minimumLevel)));
    }

    synchronized List<DevLogEvent> readEvents(long afterSequence, int requestedLimit,
                                              Predicate<DevLogEvent> predicate) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        try {
            List<DevLogEvent> matching = new ArrayList<>();
            for (Path file : eventFiles()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    DevLogEvent event = lineMapper.readValue(line, DevLogEvent.class);
                    if (event.sequence() > afterSequence && predicate.test(event)) {
                        matching.add(event);
                        if (matching.size() == limit) {
                            return List.copyOf(matching);
                        }
                    }
                }
            }
            return List.copyOf(matching);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Fluxzero dev events from " + eventsFile, e);
        }
    }

    synchronized DevDiagnostics diagnostics() {
        List<DevProblem> problems = sortedProblems();
        return new DevDiagnostics(sessionId, problems.size(), count(problems, ERROR), count(problems, WARN),
                                  problems, sequence.get(), Instant.now().toEpochMilli());
    }

    synchronized long lastSequence() {
        return sequence.get();
    }

    synchronized boolean awaitEventAfter(long afterSequence, java.time.Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (!closed && sequence.get() <= afterSequence) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                long millis = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
                wait(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return sequence.get() > afterSequence;
    }

    synchronized AutoCloseable onDiagnosticsChanged(Runnable listener) {
        diagnosticsListeners.add(Objects.requireNonNull(listener));
        return () -> {
            synchronized (DevLogStore.this) {
                diagnosticsListeners.remove(listener);
            }
        };
    }

    Path sessionDirectory() {
        return sessionDirectory;
    }

    Path combinedLog() {
        return combinedLog;
    }

    Path eventsFile() {
        return eventsFile;
    }

    Path problemsFile() {
        return problemsFile;
    }

    Path diagnosticsFile() {
        return diagnosticsFile;
    }

    String sessionId() {
        return sessionId;
    }

    private void log(DevLogEvent.Level level, String source, String serviceType, String serviceId,
                     String instanceId, String operationId, String stream, String message) {
        DevLogEvent event = writeEvent(level, source, serviceType, serviceId, instanceId, operationId, stream, message);
        if (level.atLeast(WARN)) {
            String summary = summary(message);
            String key = String.join("|", "log", nullToEmpty(serviceType), nullToEmpty(serviceId),
                                     nullToEmpty(instanceId), level.name(), summary);
            upsertProblem(key, level, "log", event, summary, message);
        }
    }

    private DevLogEvent writeEvent(DevLogEvent.Level level, String source, String serviceType, String serviceId,
                                   String instanceId, String operationId, String stream, String message) {
        ensureOpen();
        long now = Instant.now().toEpochMilli();
        DevLogEvent event = new DevLogEvent(sequence.incrementAndGet(), now, level, source, serviceType, serviceId,
                                            instanceId, operationId, stream, message);
        try {
            appendRotating(eventsFile, lineMapper.writeValueAsString(event));
            lastCombinedLogLine = appendCombinedLog(humanLine(event));
            notifyAll();
            return event;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append Fluxzero dev log event", e);
        }
    }

    private void upsertProblem(String key, DevLogEvent.Level severity, String category, DevLogEvent event,
                               String summary, String detail) {
        String id = problemId(key);
        DevProblem existing = activeProblems.get(id);
        DevProblem problem = existing == null
                ? new DevProblem(id, severity, category, event.source(), event.serviceType(), event.serviceId(),
                                 event.instanceId(), event.operationId(), safeSummary(summary), safeDetail(detail), 1,
                                 event.timestamp(), event.timestamp(), event.sequence())
                : existing.observedAgain(safeDetail(detail), event.timestamp(), event.sequence());
        activeProblems.put(id, problem);
        writeProblemChange("observed", problem, null);
        writeDiagnosticsUnchecked();
    }

    private void resolveProblems(Predicate<DevProblem> predicate, String reason) {
        List<DevProblem> resolved = activeProblems.values().stream().filter(predicate).toList();
        if (resolved.isEmpty()) {
            return;
        }
        resolved.forEach(problem -> {
            activeProblems.remove(problem.id());
            writeProblemChange("resolved", problem, reason);
        });
        writeDiagnosticsUnchecked();
    }

    private void writeProblemChange(String state, DevProblem problem, String reason) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("timestamp", Instant.now().toEpochMilli());
        change.put("sequence", sequence.get());
        change.put("state", state);
        change.put("reason", reason);
        change.put("problem", problem);
        try {
            appendRotating(problemsFile, lineMapper.writeValueAsString(change));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append Fluxzero dev problem history", e);
        }
    }

    private void writeDiagnosticsUnchecked() {
        try {
            writeDiagnostics();
            diagnosticsListeners.forEach(listener -> {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // Diagnostics persistence must not fail because an optional observer disconnected.
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write Fluxzero dev diagnostics", e);
        }
    }

    private void writeDiagnostics() throws IOException {
        Files.createDirectories(diagnosticsFile.getParent());
        Path temporary = diagnosticsFile.resolveSibling(diagnosticsFile.getFileName() + ".tmp");
        objectMapper.writeValue(temporary.toFile(), diagnostics());
        try {
            Files.move(temporary, diagnosticsFile, StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, diagnosticsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void appendRotating(Path target, String line) throws IOException {
        Files.createDirectories(target.getParent());
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(target) && Files.size(target) + bytes.length > maxFileSize) {
            rotate(target);
        }
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private long appendCombinedLog(String line) throws IOException {
        Files.createDirectories(combinedLog.getParent());
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(combinedLog) && Files.size(combinedLog) + bytes.length > maxFileSize) {
            rotate(combinedLog);
            combinedLogLines = 0;
        }
        Files.write(combinedLog, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return ++combinedLogLines;
    }

    private void rotate(Path target) throws IOException {
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + "." + maxArchives));
        for (int index = maxArchives - 1; index >= 1; index--) {
            Path source = target.resolveSibling(target.getFileName() + "." + index);
            if (Files.exists(source)) {
                Files.move(source, target.resolveSibling(target.getFileName() + "." + (index + 1)),
                           StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(target, target.resolveSibling(target.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }

    private List<Path> eventFiles() {
        List<Path> files = new ArrayList<>();
        for (int index = maxArchives; index >= 1; index--) {
            Path archive = eventsFile.resolveSibling(eventsFile.getFileName() + "." + index);
            if (Files.isRegularFile(archive)) {
                files.add(archive);
            }
        }
        if (Files.isRegularFile(eventsFile)) {
            files.add(eventsFile);
        }
        return files;
    }

    private static void retainRecentSessions(Path logsDirectory, Path current) throws IOException {
        if (!Files.isDirectory(logsDirectory)) {
            return;
        }
        List<Path> sessions;
        try (var stream = Files.list(logsDirectory)) {
            sessions = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(DevLogStore::lastModified).reversed())
                    .toList();
        }
        sessions.stream().filter(path -> !path.equals(current)).skip(RETAINED_SESSIONS - 1L)
                .forEach(DevLogStore::deleteRecursively);
    }

    private ParsedLine parse(String rawLine) {
        String line = rawLine == null ? "null" : rawLine;
        String source = "dev-server";
        Matcher sourceMatcher = SOURCE_PREFIX.matcher(line);
        if (sourceMatcher.matches()) {
            source = sourceMatcher.group(1);
            line = sourceMatcher.group(2);
        }
        String stream = "internal";
        if (line.startsWith("[stderr] ")) {
            stream = "stderr";
            line = line.substring("[stderr] ".length());
        } else if (line.startsWith("[stdout] ")) {
            stream = "stdout";
            line = line.substring("[stdout] ".length());
        }
        return new ParsedLine(source, stream, inferLevel(line), line);
    }

    private ServiceIdentity identity(String source) {
        return switch (source) {
            case "app" -> new ServiceIdentity("application", defaultApplicationName);
            case "compile" -> new ServiceIdentity("build", defaultApplicationName);
            case "test", "tests" -> new ServiceIdentity("test", defaultApplicationName);
            case "commands" -> new ServiceIdentity("seed", defaultApplicationName);
            case "reload" -> new ServiceIdentity("deployment", defaultApplicationName);
            case "runtime", "proxy", "gateway", "idp", "frontend" ->
                    new ServiceIdentity("infrastructure", source);
            default -> new ServiceIdentity("supervisor", "dev-server");
        };
    }

    private static DevLogEvent.Level inferLevel(String line) {
        String value = line == null ? "" : line;
        if (value.contains("SLF4J(E)")) {
            return ERROR;
        }
        if (value.contains("SLF4J(W)")) {
            return WARN;
        }
        Matcher matcher = LEVEL.matcher(value);
        if (!matcher.find()) {
            return INFO;
        }
        return switch (matcher.group(1).toUpperCase()) {
            case "ERROR" -> ERROR;
            case "WARN", "WARNING" -> WARN;
            case "DEBUG" -> DEBUG;
            case "TRACE" -> TRACE;
            default -> INFO;
        };
    }

    private static String humanLine(DevLogEvent event) {
        StringBuilder context = new StringBuilder(event.source());
        if (event.serviceId() != null) {
            context.append(' ').append(event.serviceId());
        }
        if (event.instanceId() != null) {
            context.append('/').append(event.instanceId());
        }
        if (event.stream() != null) {
            context.append(' ').append(event.stream());
        }
        return "[" + Instant.ofEpochMilli(event.timestamp()) + "] [" + event.level() + "] [" + context + "] "
               + event.message();
    }

    private List<DevProblem> sortedProblems() {
        return activeProblems.values().stream()
                .sorted(Comparator.comparingInt((DevProblem problem) -> problem.severity().ordinal()).reversed()
                                .thenComparing(Comparator.comparingLong(DevProblem::lastSeenAt).reversed()))
                .toList();
    }

    private static int count(List<DevProblem> problems, DevLogEvent.Level severity) {
        return Math.toIntExact(problems.stream().filter(problem -> problem.severity() == severity).count());
    }

    private static boolean isResolutionState(String serviceType, String state) {
        if ("running".equals(state)) {
            return "infrastructure".equals(serviceType) || "application".equals(serviceType);
        }
        return switch (state) {
            case "succeeded", "passed", "idle", "skipped", "stopped" -> true;
            default -> false;
        };
    }

    private static String statusKey(String serviceType, String serviceId, String instanceId) {
        return String.join("|", "status", nullToEmpty(serviceType), nullToEmpty(serviceId), nullToEmpty(instanceId));
    }

    private static String problemId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String formatDetail(String detail) {
        return detail == null || detail.isBlank() ? "" : ": " + detail;
    }

    private static String summary(String message) {
        if (message == null || message.isBlank()) {
            return "No detail available";
        }
        return safeSummary(message.lines().findFirst().orElse(message));
    }

    private static String safeSummary(String value) {
        String summary = value == null ? "No detail available" : value.strip();
        return summary.length() <= 300 ? summary : summary.substring(0, 300);
    }

    private static String safeDetail(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long countLines(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    private static void deleteRecursively(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Old diagnostics are best-effort retention data.
                }
            });
        } catch (IOException ignored) {
            // Old diagnostics are best-effort retention data.
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Fluxzero dev log store is closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private record ParsedLine(String source, String stream, DevLogEvent.Level level, String message) {
    }

    private record ServiceIdentity(String serviceType, String serviceId) {
    }

    record LogPosition(Path file, long line) {
    }
}
