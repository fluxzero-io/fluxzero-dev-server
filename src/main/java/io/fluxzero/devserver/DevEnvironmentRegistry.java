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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Global, non-sensitive index of project-local development sessions. */
final class DevEnvironmentRegistry {
    static final String DIRECTORY_PROPERTY = "fluxzero.dev.registryDirectory";
    static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(10);
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path directory;

    DevEnvironmentRegistry(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    static DevEnvironmentRegistry global() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".fluxzero", "dev", "environments")
                : Path.of(configured);
        return new DevEnvironmentRegistry(directory);
    }

    synchronized void register(DevSession session) {
        Path projectDirectory = canonicalProject(Path.of(session.projectDirectory()));
        Registration registration = new Registration(
                SCHEMA_VERSION, projectDirectory.toString(), session.sessionId(), session.pid(), session.startedAt(),
                session.devServerVersion(), Instant.now().toEpochMilli());
        write(registrationFile(projectDirectory), registration);
        remember(session);
        // Import older, currently registered dev servers into persistent discovery once.
        try (var registrations = Files.list(directory)) {
            registrations.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::readRegistration).flatMap(Optional::stream).forEach(r -> {
                        try { Path project = Path.of(r.projectDirectory());
                              if (!Files.exists(historyFile(project))) new DevSessionStore(project).readSession().ifPresent(this::remember); }
                        catch (RuntimeException ignored) { /* A missing legacy session must not block startup. */ }
                    });
        } catch (IOException e) { throw new IllegalStateException("Cannot discover existing dev environments", e); }
    }

    synchronized void unregister(DevSession session) {
        unregister(Path.of(session.projectDirectory()));
    }

    synchronized void unregister(Path projectDirectory) {
        delete(registrationFile(canonicalProject(projectDirectory)));
    }

    synchronized List<Environment> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readRegistration)
                    .flatMap(Optional::stream)
                    .map(this::resolve)
                    .sorted(Comparator.comparing(Environment::projectName, String.CASE_INSENSITIVE_ORDER)
                                    .thenComparing(Environment::projectDirectory))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list Fluxzero dev environments in " + directory, e);
        }
    }

    /** Persist discovery separately so CLI unregister/cleanup keeps its existing behavior. */
    private void remember(DevSession session) {
        Path project = canonicalProject(Path.of(session.projectDirectory()));
        write(historyFile(project), new KnownProject(SCHEMA_VERSION, project.toString(), session.gateway().url(), false));
    }

    private Path historyFile(Path project) {
        return directory.resolve("history").resolve(hash(project.toString()) + ".json");
    }

    synchronized Optional<ConsoleEnvironment> findKnown(String id) {
        return listKnown().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** A tombstone hides legacy registrations without touching CLI ownership or project files. */
    synchronized void forget(String id) {
        var project = findKnown(id).orElseThrow(() -> new IllegalArgumentException("Project is no longer listed."));
        if (!"stopped".equals(project.status())) throw new IllegalStateException("Stop the dev server before removing it from the overview.");
        Path path = Path.of(project.projectDirectory());
        write(historyFile(path), new KnownProject(SCHEMA_VERSION, path.toString(), null, true));
    }

    /** Read-only UI projection; does not reconcile, stop, or start another environment. */
    synchronized List<ConsoleEnvironment> listKnown() {
        var projects = new java.util.TreeMap<String, KnownProject>();
        Path history = directory.resolve("history");
        if (Files.isDirectory(history)) {
            try (var files = Files.list(history)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    try {
                        KnownProject known = objectMapper.readValue(p.toFile(), KnownProject.class);
                        if (known.version() == SCHEMA_VERSION && Path.of(known.projectDirectory()).isAbsolute())
                            projects.put(known.projectDirectory(), known);
                    } catch (IOException | RuntimeException ignored) { /* Ignore incomplete history entries. */ }
                });
            } catch (IOException e) { throw new IllegalStateException("Cannot read known dev environments", e); }
        }
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(this::readRegistration).flatMap(Optional::stream)
                        .forEach(r -> projects.putIfAbsent(r.projectDirectory(),
                                                         new KnownProject(SCHEMA_VERSION, r.projectDirectory(), null, false)));
            } catch (IOException e) { throw new IllegalStateException("Cannot read dev environments", e); }
        }
        return projects.values().stream().map(known -> {
                    var environment = consoleEnvironment(known);
                    // A racing restart or an older server version must always remain discoverable.
                    return known.hidden() && !"running".equals(environment.status()) ? null : environment;
                }).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ConsoleEnvironment::projectName, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(ConsoleEnvironment::projectDirectory)).toList();
    }

    private ConsoleEnvironment consoleEnvironment(KnownProject known) {
        Path project = Path.of(known.projectDirectory());
        DevSession current;
        try { current = new DevSessionStore(project).readSession().orElse(null); }
        catch (RuntimeException ignored) { current = null; }
        boolean running = current != null && !current.status().startsWith("stopped")
                && ProcessUtils.isAlive(current.pid(), current.startedAt());
        boolean responsive = running && Instant.now().toEpochMilli() - current.heartbeatAt() <= HEARTBEAT_TIMEOUT.toMillis();
        String address = current != null && current.gateway().url() != null ? current.gateway().url() : known.url();
        String consoleUrl = null;
        Integer port = null;
        try {
            var uri = java.net.URI.create(address);
            String host = uri.getHost();
            // The local overview must never turn registry contents into external links.
            if (java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host)
                    && "http".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getPort() > 0) {
                port = uri.getPort();
                if (responsive && "running".equals(current.gateway().state())) {
                    consoleUrl = "http://" + host + ":" + port + DevConsole.ROOT;
                }
            }
        } catch (RuntimeException ignored) { /* No usable public local URL. */ }
        return new ConsoleEnvironment(hash(project.toString()), Files.isDirectory(project), project.getFileName() == null ? project.toString() : project.getFileName().toString(),
                project.toString(), running ? "running" : "stopped", port, consoleUrl,
                running && !responsive ? "Not responding" : null);
    }

    record KnownProject(int version, String projectDirectory, String url, boolean hidden) { }
    record ConsoleEnvironment(String id, boolean directoryExists, String projectName, String projectDirectory, String status, Integer port,
                              String consoleUrl, String detail) { }

    private Environment resolve(Registration registration) {
        Path projectDirectory = Path.of(registration.projectDirectory());
        Optional<DevSession> current;
        try {
            current = new DevSessionStore(projectDirectory).reconcileUnexpectedStop();
        } catch (RuntimeException e) {
            return Environment.stale(registration, "could not read project session: " + e.getMessage());
        }
        if (current.isEmpty()) {
            return Environment.stale(registration, "project session file is missing");
        }
        DevSession session = current.get();
        if (!registration.sessionId().equals(session.sessionId())) {
            return Environment.stale(registration, "project session was replaced");
        }
        if ("stopped".equals(session.status()) || "stopped-unexpectedly".equals(session.status())
            || !ProcessUtils.isAlive(session.pid(), session.startedAt())) {
            return Environment.from(session, "stale", false, "dev server process is not running");
        }
        long heartbeatAge = Math.max(0, Instant.now().toEpochMilli() - session.heartbeatAt());
        if (heartbeatAge > HEARTBEAT_TIMEOUT.toMillis()) {
            return Environment.from(session, "unresponsive", false,
                                    "last heartbeat was " + formatAge(heartbeatAge) + " ago");
        }
        return Environment.from(session, session.status(), true, null);
    }

    private Optional<Registration> readRegistration(Path path) {
        try {
            Registration registration = objectMapper.readValue(path.toFile(), Registration.class);
            return registration.version() == SCHEMA_VERSION ? Optional.of(registration) : Optional.empty();
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void write(Path target, Object registration) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            objectMapper.writeValue(temporary.toFile(), registration);
            AtomicFileUtils.replace(temporary, target);
            temporary = null;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to register Fluxzero dev environment in " + directory, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Temporary files are harmless and may be cleaned up by a later registration.
                }
            }
        }
    }

    private void delete(Path target) {
        try {
            AtomicFileUtils.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to unregister Fluxzero dev environment " + target, e);
        }
    }

    private Path registrationFile(Path projectDirectory) {
        return directory.resolve(hash(projectDirectory.toString()) + ".json");
    }

    private static Path canonicalProject(Path projectDirectory) {
        Path normalized = projectDirectory.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String formatAge(long millis) {
        if (millis < 60_000) {
            return Math.max(1, millis / 1_000) + "s";
        }
        return millis / 60_000 + "m";
    }

    record Registration(int version, String projectDirectory, String sessionId, long pid, long startedAt,
                        String devServerVersion, long registeredAt) {
    }

    record Environment(String status, boolean active, String projectName, String projectDirectory,
                       List<String> applications, String url, long pid, String devServerVersion, String sessionId,
                       long startedAt, long heartbeatAt, String detail) {
        static Environment from(DevSession session, String status, boolean active, String detail) {
            Path project = Path.of(session.projectDirectory()).toAbsolutePath().normalize();
            String projectName = project.getFileName() == null ? project.toString() : project.getFileName().toString();
            List<String> applications = session.app().metadata().keySet().stream()
                    .filter(key -> key.startsWith("application.") && key.endsWith(".pid"))
                    .map(key -> key.substring("application.".length(), key.length() - ".pid".length()))
                    .sorted()
                    .toList();
            String url = "running".equals(session.gateway().state()) && session.gateway().url() != null
                    ? session.gateway().url() : session.proxy().url();
            return new Environment(status, active, projectName, project.toString(), applications, url, session.pid(),
                                   session.devServerVersion(), session.sessionId(), session.startedAt(),
                                   session.heartbeatAt(), detail);
        }

        static Environment stale(Registration registration, String detail) {
            Path project = Path.of(registration.projectDirectory());
            String projectName = project.getFileName() == null ? project.toString() : project.getFileName().toString();
            return new Environment("stale", false, projectName, project.toString(), List.of(), null,
                                   registration.pid(), registration.devServerVersion(), registration.sessionId(),
                                   registration.startedAt(), 0, detail);
        }
    }
}
