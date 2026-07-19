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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ProcessUtils {
    static final String PROCESS_STARTED_AT = "processStartedAt";
    private static final Duration FORCE_STOP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private ProcessUtils() {
    }

    static Process start(List<String> command, Path directory, Map<String, String> environment,
                         Consumer<String> outputConsumer) throws IOException {
        return startWithStreams(command, directory, environment, output -> outputConsumer.accept(
                "stderr".equals(output.stream()) ? "[stderr] " + output.line() : output.line()));
    }

    static Process startWithStreams(List<String> command, Path directory, Map<String, String> environment,
                                    Consumer<ProcessOutput> outputConsumer) throws IOException {
        return startCaptured(command, directory, environment, outputConsumer).process();
    }

    private static StartedProcess startCaptured(List<String> command, Path directory, Map<String, String> environment,
                                                Consumer<ProcessOutput> outputConsumer) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false);
        builder.environment().putAll(environment);
        Process process = builder.start();
        CountDownLatch outputComplete = new CountDownLatch(2);
        readOutput(process.getInputStream(), "stdout", outputConsumer, outputComplete);
        readOutput(process.getErrorStream(), "stderr", outputConsumer, outputComplete);
        return new StartedProcess(process, outputComplete);
    }

    private static void readOutput(java.io.InputStream input, String stream,
                                   Consumer<ProcessOutput> outputConsumer, CountDownLatch outputComplete) {
        Thread.ofPlatform().daemon(true).name("fluxzero-dev-process-" + stream).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputConsumer.accept(new ProcessOutput(stream, line));
                }
            } catch (IOException ignored) {
                // Process output is best-effort diagnostic information.
            } finally {
                outputComplete.countDown();
            }
        });
    }

    static ProcessResult run(List<String> command, Path directory, Map<String, String> environment,
                             Consumer<String> outputConsumer) throws IOException, InterruptedException {
        return run(command, directory, environment, outputConsumer, ignored -> {
        });
    }

    static ProcessResult run(List<String> command, Path directory, Map<String, String> environment,
                             Consumer<String> outputConsumer, Consumer<Process> processConsumer)
            throws IOException, InterruptedException {
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        StartedProcess startedProcess = startCaptured(command, directory, environment, processOutput -> {
            String line = "stderr".equals(processOutput.stream())
                    ? "[stderr] " + processOutput.line() : processOutput.line();
            output.add(line);
            outputConsumer.accept(line);
        });
        Process process = startedProcess.process();
        processConsumer.accept(process);
        try {
            int exitCode = process.waitFor();
            startedProcess.awaitOutput();
            synchronized (output) {
                return new ProcessResult(exitCode, List.copyOf(output));
            }
        } catch (InterruptedException e) {
            forceStopTree(process);
            throw e;
        }
    }

    static void stopTree(Process process, Duration timeout) {
        stopTree(process.toHandle(), timeout);
    }

    static void forceStopTree(Process process) {
        forceStopTree(process.toHandle(), FORCE_STOP_TIMEOUT);
    }

    private static void forceStopTree(ProcessHandle handle, Duration timeout) {
        if (!handle.isAlive() || isCurrentProcess(handle)) {
            return;
        }
        List<ProcessHandle> processes = new ArrayList<>(handle.descendants()
                .filter(ProcessUtils::isNotCurrentProcess).toList().reversed());
        processes.add(handle);
        processes.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);

        // A shell may spawn one last descendant while it is being terminated. Capture and kill those as well.
        handle.descendants().filter(ProcessUtils::isNotCurrentProcess).toList().reversed().stream()
                .filter(ProcessHandle::isAlive).forEach(process -> {
                    if (!processes.contains(process)) {
                        processes.add(process);
                    }
                    process.destroyForcibly();
                });
        awaitStopped(processes, timeout);
    }

    private static void awaitStopped(List<ProcessHandle> processes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static void stopTree(ProcessHandle handle, Duration timeout) {
        if (!handle.isAlive() || isCurrentProcess(handle)) {
            return;
        }
        List<ProcessHandle> processes = new ArrayList<>(handle.descendants()
                .filter(ProcessUtils::isNotCurrentProcess).toList().reversed());
        processes.add(handle);
        processes.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        awaitStopped(processes, timeout);

        handle.descendants().filter(ProcessUtils::isNotCurrentProcess).toList().reversed().stream()
                .filter(ProcessHandle::isAlive).forEach(process -> {
                    if (!processes.contains(process)) {
                        processes.add(process);
                    }
                });
        processes.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        awaitStopped(processes, FORCE_STOP_TIMEOUT);
    }

    private static boolean isNotCurrentProcess(ProcessHandle process) {
        return !isCurrentProcess(process);
    }

    private static boolean isCurrentProcess(ProcessHandle process) {
        return process.pid() == ProcessHandle.current().pid();
    }

    static boolean isAlive(long pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean stopIfCommandLineContains(Long pid, String marker, Duration timeout) {
        return stopIfOwned(pid, marker, null, timeout);
    }

    static boolean stopIfOwned(Long pid, String marker, Long processStartedAt, Duration timeout) {
        if (pid == null || marker == null || marker.isBlank()) {
            return false;
        }
        Optional<ProcessHandle> handle;
        try {
            handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (handle.isEmpty()) {
            return false;
        }
        ProcessHandle process = handle.get();
        if (!containsOwnershipMarker(process.info(), marker)
            && !matchesStartTime(process.info(), processStartedAt)) {
            return false;
        }
        stopTree(process, timeout);
        return true;
    }

    static Optional<Long> startedAt(Process process) {
        return process == null ? Optional.empty() : process.info().startInstant()
                .map(java.time.Instant::toEpochMilli);
    }

    private static boolean matchesStartTime(ProcessHandle.Info info, Long expected) {
        return expected != null && info.startInstant()
                .map(java.time.Instant::toEpochMilli)
                .filter(actual -> actual.equals(expected))
                .isPresent();
    }

    private static boolean containsOwnershipMarker(ProcessHandle.Info info, String marker) {
        String expected = normalizedCommandLine(marker);
        if (info.commandLine().map(ProcessUtils::normalizedCommandLine)
                .filter(commandLine -> commandLine.contains(expected)).isPresent()) {
            return true;
        }
        if (info.arguments().stream().flatMap(java.util.Arrays::stream)
                .map(ProcessUtils::normalizedCommandLine)
                .anyMatch(argument -> argument.contains(expected))) {
            return true;
        }
        return canonicalPath(marker).map(project -> info.arguments().stream()
                .flatMap(java.util.Arrays::stream)
                .flatMap(ProcessUtils::pathCandidates)
                .map(ProcessUtils::canonicalPath)
                .flatMap(Optional::stream)
                .anyMatch(path -> belongsToProject(path, project))).orElse(false);
    }

    private static Stream<String> pathCandidates(String argument) {
        Stream.Builder<String> candidates = Stream.builder();
        candidates.add(argument);
        int separator = argument.indexOf('=');
        if (separator >= 0 && separator + 1 < argument.length()) {
            candidates.add(argument.substring(separator + 1));
        }
        if (argument.indexOf(File.pathSeparatorChar) >= 0) {
            Stream.of(argument.split(Pattern.quote(File.pathSeparator))).forEach(candidates::add);
        }
        return candidates.build();
    }

    private static Optional<Path> canonicalPath(String value) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? Optional.of(path.toRealPath()) : Optional.empty();
        } catch (IOException | InvalidPathException ignored) {
            return Optional.empty();
        }
    }

    private static boolean belongsToProject(Path path, Path project) {
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                if (Files.isSameFile(current, project)) {
                    return true;
                }
            } catch (IOException ignored) {
                // Keep checking parents when a classpath entry disappeared after the process started.
            }
        }
        return false;
    }

    private static String normalizedCommandLine(String value) {
        String normalized = value.replace('\\', '/');
        return isWindows() ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record ProcessResult(int exitCode, List<String> output) {
        boolean success() {
            return exitCode == 0;
        }

        String tail(int maxLines) {
            int start = Math.max(0, output.size() - maxLines);
            return String.join(System.lineSeparator(), output.subList(start, output.size()));
        }
    }

    record ProcessOutput(String stream, String line) {
    }

    private record StartedProcess(Process process, CountDownLatch outputComplete) {
        void awaitOutput() throws InterruptedException {
            outputComplete.await(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
