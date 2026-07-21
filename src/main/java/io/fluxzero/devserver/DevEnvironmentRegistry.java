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
import java.nio.file.StandardCopyOption;
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
    }

    synchronized void unregister(DevSession session) {
        Path target = registrationFile(canonicalProject(Path.of(session.projectDirectory())));
        readRegistration(target).filter(registration -> session.sessionId().equals(registration.sessionId()))
                .ifPresent(ignored -> delete(target));
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

    private void write(Path target, Registration registration) {
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
            objectMapper.writeValue(temporary.toFile(), registration);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to register Fluxzero dev environment in " + directory, e);
        }
    }

    private void delete(Path target) {
        try {
            Files.deleteIfExists(target);
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
