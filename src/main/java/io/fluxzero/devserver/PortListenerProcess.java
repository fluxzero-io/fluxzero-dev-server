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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record PortListenerProcess(long pid, String commandLine) {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STOP_TIMEOUT = Duration.ofMillis(750);
    private static final Pattern WINDOWS_LISTENER = Pattern.compile(
            "(?i)^\\s*TCP\\s+\\S+:(\\d+)\\s+\\S+\\s+LISTENING\\s+(\\d+)\\s*$");
    private static final Pattern SS_LISTENER = Pattern.compile("pid=(\\d+)");

    static Optional<PortListenerProcess> find(int port) {
        Optional<Long> pid = isWindows() ? windowsListener(port) : unixListener(port);
        return pid.flatMap(PortListenerProcess::fromPid);
    }

    static void stop(PortListenerProcess listener) {
        if (listener.pid() == ProcessHandle.current().pid()) {
            throw new DevServerStartupException("Refusing to stop the current process");
        }
        ProcessHandle handle = ProcessHandle.of(listener.pid()).filter(ProcessHandle::isAlive).orElse(null);
        if (handle != null) {
            ProcessUtils.stopTree(handle, STOP_TIMEOUT);
        }
    }

    String displayCommand() {
        if (commandLine == null || commandLine.isBlank()) {
            return "unknown command";
        }
        String compact = commandLine.replaceAll("\\s+", " ").strip();
        return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
    }

    static Optional<Long> parseWindowsListener(List<String> lines, int port) {
        for (String line : lines) {
            Matcher matcher = WINDOWS_LISTENER.matcher(line);
            if (matcher.matches() && Integer.parseInt(matcher.group(1)) == port) {
                return Optional.of(Long.parseLong(matcher.group(2)));
            }
        }
        return Optional.empty();
    }

    static Optional<Long> parseSsListener(List<String> lines) {
        return lines.stream().map(SS_LISTENER::matcher).filter(Matcher::find)
                .map(matcher -> Long.parseLong(matcher.group(1))).findFirst();
    }

    private static Optional<PortListenerProcess> fromPid(long pid) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(handle -> {
            ProcessHandle.Info info = handle.info();
            String command = info.commandLine().filter(value -> !value.equals(info.command().orElse(null)))
                    .orElseGet(() -> externalCommandLine(pid).orElseGet(
                            () -> info.command().orElse("unknown command")));
            return new PortListenerProcess(pid, command);
        });
    }

    private static Optional<String> externalCommandLine(long pid) {
        List<String> output = isWindows()
                ? run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                      "(Get-CimInstance Win32_Process -Filter 'ProcessId = " + pid + "').CommandLine")
                : run(firstExecutable("/bin/ps", "/usr/bin/ps", "ps"),
                      "-p", Long.toString(pid), "-o", "command=");
        return output.stream().map(String::strip).filter(value -> !value.isBlank()).findFirst();
    }

    private static Optional<Long> unixListener(int port) {
        List<String> lsof = run(firstExecutable("/usr/sbin/lsof", "/usr/bin/lsof", "lsof"),
                                "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t");
        Optional<Long> result = lsof.stream().map(String::strip).filter(value -> value.matches("\\d+"))
                .map(Long::parseLong).findFirst();
        if (result.isPresent() || isMac()) {
            return result;
        }
        return parseSsListener(run(firstExecutable("/usr/sbin/ss", "/usr/bin/ss", "ss"),
                                   "-ltnp", "sport = :" + port));
    }

    private static Optional<Long> windowsListener(int port) {
        return parseWindowsListener(run("netstat", "-ano", "-p", "tcp"), port);
    }

    private static String firstExecutable(String... candidates) {
        return Arrays.stream(candidates).filter(candidate -> !candidate.contains("/")
                        || Files.isExecutable(Path.of(candidate))).findFirst().orElse(candidates[candidates.length - 1]);
    }

    private static List<String> run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> output = Collections.synchronizedList(new ArrayList<>());
            Thread reader = Thread.ofVirtual().name("fluxzero-dev-port-process-output").start(() -> {
                try (BufferedReader input = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        output.add(line);
                    }
                } catch (IOException ignored) {
                    // Process discovery is best effort and has command fallbacks.
                }
            });
            boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return List.of();
            }
            reader.join(COMMAND_TIMEOUT.toMillis());
            synchronized (output) {
                return List.copyOf(output);
            }
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
