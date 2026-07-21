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

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Project-local lifecycle controls and global discovery for Fluxzero dev environments. */
public final class DevServerControlMain {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DevServerControlMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        int exitCode = switch (arguments.action()) {
            case "status" -> status(arguments);
            case "list" -> list(arguments);
            case "logs" -> logs(arguments);
            case "stop" -> stop(arguments);
            case "wait" -> waitForStartup(arguments);
            case "attach" -> new DevTerminalAttachment(arguments.projectDirectory()).run(arguments.pid());
            case "probe" -> probe(arguments);
            default -> throw new IllegalArgumentException("Unknown dev control action: " + arguments.action());
        };
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int status(Arguments arguments) throws Exception {
        Optional<DevSession> current = store(arguments).reconcileUnexpectedStop();
        if (current.isEmpty()) {
            System.out.println(arguments.json() ? "{\"status\":\"not-running\"}" : "Fluxzero dev is not running.");
            return 1;
        }
        DevSession session = current.get();
        if (arguments.json()) {
            System.out.println(OBJECT_MAPPER.writeValueAsString(session));
            return active(session) ? 0 : 1;
        }
        System.out.println("Fluxzero dev is " + displayState(session.status()) + ".");
        System.out.println("PID: " + session.pid());
        publicUrl(session).ifPresent(url -> System.out.println("URL: " + url));
        System.out.println("Runtime: " + session.runtime().state());
        System.out.println("Applications: " + session.app().state());
        System.out.println("Tests: " + session.tests().state());
        System.out.println("Commands: " + session.commands().state());
        System.out.println("Problems: " + session.observability().diagnostics());
        System.out.println("Log: " + session.observability().combinedLog());
        if ("stopped-unexpectedly".equals(session.status())) {
            System.out.println("In-memory runtime data was lost; startup commands will run again next time.");
        }
        return active(session) ? 0 : 1;
    }

    private static int list(Arguments arguments) throws Exception {
        List<DevEnvironmentRegistry.Environment> environments = DevEnvironmentRegistry.global().list();
        if (arguments.json()) {
            System.out.println(OBJECT_MAPPER.writeValueAsString(new EnvironmentList(
                    environments,
                    environments.stream().filter(DevEnvironmentRegistry.Environment::active).count(),
                    environments.stream().filter(environment -> !environment.active()).count())));
            return 0;
        }
        if (environments.isEmpty()) {
            System.out.println("No Fluxzero dev environments found.");
            return 0;
        }
        int statusWidth = Math.max("STATUS".length(), environments.stream()
                .mapToInt(environment -> environment.status().length()).max().orElse(0));
        int projectWidth = Math.max("PROJECT".length(), environments.stream()
                .map(DevEnvironmentRegistry.Environment::projectDirectory)
                .map(DevServerControlMain::displayPath)
                .mapToInt(String::length).max().orElse(0));
        int appsWidth = Math.max("APPLICATIONS".length(), environments.stream()
                .map(DevServerControlMain::displayApplications).mapToInt(String::length).max().orElse(0));
        String format = "%-" + statusWidth + "s  %-" + projectWidth + "s  %-" + appsWidth + "s  %s%n";
        System.out.printf(format, "STATUS", "PROJECT", "APPLICATIONS", "URL");
        for (DevEnvironmentRegistry.Environment environment : environments) {
            System.out.printf(format, environment.status(), displayPath(environment.projectDirectory()),
                              displayApplications(environment), environment.url() == null ? "-" : environment.url());
            if (environment.detail() != null) {
                System.out.println("  " + environment.detail());
            }
        }
        long active = environments.stream().filter(DevEnvironmentRegistry.Environment::active).count();
        long stale = environments.size() - active;
        System.out.printf("%n%d active, %d stale.%n", active, stale);
        return 0;
    }

    private static int probe(Arguments arguments) {
        return store(arguments).reconcileUnexpectedStop().filter(DevServerControlMain::active).isPresent() ? 0 : 1;
    }

    private static int logs(Arguments arguments) throws Exception {
        DevSession session = store(arguments).reconcileUnexpectedStop().orElseThrow(() ->
                new IllegalStateException("No Fluxzero dev session found in " + arguments.projectDirectory()));
        Path logFile = Path.of(session.observability().combinedLog());
        if (!Files.isRegularFile(logFile)) {
            throw new IllegalStateException("Dev log does not exist yet: " + logFile);
        }
        try (RandomAccessFile input = new RandomAccessFile(logFile.toFile(), "r")) {
            long start = arguments.follow() ? Math.max(0, input.length() - 64 * 1024) : 0;
            int stoppedPolls = 0;
            input.seek(start);
            if (start > 0) {
                input.readLine();
            }
            while (true) {
                String line = input.readLine();
                if (line != null) {
                    String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                    if (matches(decoded, arguments)) {
                        System.out.println(decoded);
                    }
                    continue;
                }
                if (!arguments.follow()) {
                    return 0;
                }
                Optional<DevSession> current = store(arguments).reconcileUnexpectedStop();
                if (current.isEmpty() || !active(current.get())) {
                    if (++stoppedPolls >= 3) {
                        return 0;
                    }
                } else {
                    stoppedPolls = 0;
                }
                TimeUnit.MILLISECONDS.sleep(200);
            }
        }
    }

    private static int stop(Arguments arguments) throws Exception {
        DevSessionStore store = store(arguments);
        Optional<DevSession> current = store.reconcileUnexpectedStop();
        if (current.isEmpty() || !active(current.get())) {
            System.out.println("Fluxzero dev is not running.");
            return 0;
        }
        DevSession session = current.get();
        System.out.println(DevServerMain.STOPPING_MESSAGE);
        System.out.flush();
        Duration timeout = arguments.force() ? Duration.ZERO : Duration.ofSeconds(2);
        boolean owned = ProcessUtils.stopIfOwned(
                session.pid(), session.projectDirectory(), session.startedAt(), timeout);
        if (!owned && ProcessUtils.isAlive(session.pid())) {
            throw new IllegalStateException("Refusing to stop PID " + session.pid()
                                            + " because it is not owned by this project");
        }
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        while (ProcessUtils.isAlive(session.pid()) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(25);
        }
        store.reconcileUnexpectedStop();
        System.out.println(DevServerMain.STOPPED_MESSAGE);
        return 0;
    }

    private static int waitForStartup(Arguments arguments) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(arguments.timeoutMillis()).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessUtils.isAlive(arguments.pid())) {
                System.err.println("Fluxzero dev process exited before startup completed. See "
                                   + bootstrapLog(arguments));
                return 2;
            }
            Optional<DevSession> current = store(arguments).readSession()
                    .filter(session -> session.pid() == arguments.pid());
            if (current.isPresent()) {
                DevSession session = current.get();
                if (startupFailed(session)) {
                    System.err.println("Fluxzero dev is running with problems.");
                    System.err.println(startupFailure(session));
                    System.err.println("Problems: " + session.observability().diagnostics());
                    System.err.println("Logs: fz dev logs --follow");
                    return 1;
                }
                if (arguments.allowEmpty() ? infrastructureReady(session) : applicationReady(session)) {
                    System.out.println("Fluxzero dev started in the background.");
                    publicUrl(session).ifPresent(url -> System.out.println("Open: " + url));
                    System.out.println("PID: " + session.pid());
                    System.out.println("Logs: fz dev logs --follow");
                    System.out.println("Stop: fz dev stop");
                    return 0;
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        System.err.println("Fluxzero dev startup is still in progress. Check: fz dev status");
        return 1;
    }

    private static boolean matches(String line, Arguments arguments) {
        if (arguments.errors() && !(line.contains("[ERROR]") || line.contains("[WARN]")
                                    || line.contains("level=ERROR") || line.contains("level=WARN")
                                    || line.contains(" ERROR ") || line.contains(" WARN "))) {
            return false;
        }
        return arguments.application() == null || line.contains("/" + arguments.application() + "/")
               || line.contains("[" + arguments.application() + "]");
    }

    private static boolean active(DevSession session) {
        return !"stopped".equals(session.status()) && !"stopped-unexpectedly".equals(session.status())
               && ProcessUtils.isAlive(session.pid());
    }

    private static boolean infrastructureReady(DevSession session) {
        return "running".equals(session.runtime().state()) && "running".equals(session.proxy().state());
    }

    private static boolean applicationReady(DevSession session) {
        boolean frontendReady = "skipped".equals(session.gateway().state())
                                || "running".equals(session.frontend().state());
        return infrastructureReady(session) && "running".equals(session.app().state())
               && "succeeded".equals(session.reload().state()) && frontendReady;
    }

    private static boolean startupFailed(DevSession session) {
        return "failed".equals(session.compile().state()) || "failed".equals(session.reload().state())
               || "degraded".equals(session.reload().state()) || "failed".equals(session.frontend().state())
               || "exited".equals(session.frontend().state());
    }

    private static String startupFailure(DevSession session) {
        for (DevSession.ServiceStatus status : List.of(session.compile(), session.reload(), session.frontend())) {
            if ("failed".equals(status.state()) || "degraded".equals(status.state())
                || "exited".equals(status.state())) {
                return status.name() + ": " + status.detail();
            }
        }
        return "unknown startup failure";
    }

    private static Optional<String> publicUrl(DevSession session) {
        if ("running".equals(session.gateway().state()) && session.gateway().url() != null) {
            return Optional.of(session.gateway().url());
        }
        return Optional.ofNullable(session.proxy().url());
    }

    private static String displayState(String state) {
        return switch (state) {
            case "stopped-unexpectedly" -> "stopped unexpectedly";
            default -> state;
        };
    }

    private static String displayPath(String path) {
        String home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().toString();
        return path.equals(home) ? "~" : path.startsWith(home + java.io.File.separator)
                ? "~" + path.substring(home.length()) : path;
    }

    private static String displayApplications(DevEnvironmentRegistry.Environment environment) {
        return environment.applications().isEmpty() ? "-" : String.join(",", environment.applications());
    }

    private static Path bootstrapLog(Arguments arguments) {
        return arguments.projectDirectory().resolve(DevSessionStore.DEV_DIRECTORY).resolve("bootstrap.log");
    }

    private static DevSessionStore store(Arguments arguments) {
        return new DevSessionStore(arguments.projectDirectory());
    }

    private record Arguments(String action, Path projectDirectory, boolean json, boolean follow, boolean errors,
                             boolean force, boolean allowEmpty, String application, long pid, long timeoutMillis) {
        static Arguments parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException(
                        "Expected dev control action: attach, list, status, logs, stop or wait");
            }
            String action = args[0];
            Path projectDirectory = Path.of("").toAbsolutePath().normalize();
            boolean json = false;
            boolean follow = false;
            boolean errors = false;
            boolean force = false;
            boolean allowEmpty = false;
            String application = null;
            long pid = -1;
            long timeoutMillis = 120_000;
            List<String> values = new ArrayList<>(List.of(args).subList(1, args.length));
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                switch (value) {
                    case "--project-dir" -> projectDirectory = Path.of(values.get(++index)).toAbsolutePath().normalize();
                    case "--json" -> json = true;
                    case "--follow", "-f" -> follow = true;
                    case "--errors" -> errors = true;
                    case "--force" -> force = true;
                    case "--allow-empty" -> allowEmpty = true;
                    case "--app" -> application = values.get(++index);
                    case "--pid" -> pid = Long.parseLong(values.get(++index));
                    case "--timeout-ms" -> timeoutMillis = Long.parseLong(values.get(++index));
                    default -> throw new IllegalArgumentException("Unknown dev control option: " + value);
                }
            }
            return new Arguments(action, projectDirectory, json, follow, errors, force, allowEmpty, application,
                                 pid, timeoutMillis);
        }
    }

    private record EnvironmentList(List<DevEnvironmentRegistry.Environment> environments, long active, long stale) {
    }
}
