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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class OnePasswordEnvironment {
    private final Path projectDirectory;
    private final String executable;

    OnePasswordEnvironment(Path projectDirectory) {
        this(projectDirectory, "op");
    }

    OnePasswordEnvironment(Path projectDirectory, String executable) {
        this.projectDirectory = projectDirectory;
        this.executable = executable;
    }

    static void cleanupReferenceFiles(Path projectDirectory) {
        Path directory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("secrets");
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Reference-only files are cleaned best-effort.
                }
            });
        } catch (IOException ignored) {
            // A later app launch can still create its own uniquely named file.
        }
    }

    PreparedCommand prepare(String launchId, List<String> applicationCommand, Map<String, String> references)
            throws IOException {
        if (references.isEmpty()) {
            return new PreparedCommand(applicationCommand, List.of());
        }
        Path resolvedExecutable = resolveExecutable();
        Path directory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("secrets");
        Files.createDirectories(directory);
        Path envFile = directory.resolve(safeId(launchId) + "-" + UUID.randomUUID() + ".env");
        StringBuilder contents = new StringBuilder();
        references.forEach((name, reference) -> contents.append(name).append("=\"")
                .append(escape(reference)).append("\"\n"));
        Files.writeString(envFile, contents, StandardCharsets.UTF_8);
        restrictPermissions(envFile);

        List<String> command = new ArrayList<>();
        command.add(resolvedExecutable.toString());
        command.add("run");
        command.add("--env-file=" + envFile);
        command.add("--");
        command.addAll(applicationCommand);
        return new PreparedCommand(List.copyOf(command), List.of(envFile));
    }

    private Path resolveExecutable() throws IOException {
        if (executable.contains("/") || executable.contains("\\")) {
            Path candidate = Path.of(executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
            throw unavailableExecutable();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(Pattern.quote(System.getProperty("path.separator")))) {
                if (entry.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(entry).resolve(executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
                if (isWindows()) {
                    candidate = Path.of(entry).resolve(executable + ".exe");
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().normalize();
                    }
                }
            }
        }
        throw unavailableExecutable();
    }

    private IOException unavailableExecutable() {
        String installHint = isMac()
                ? " Install it with `brew install --cask 1password-cli`." : "";
        return new IOException("1Password CLI executable '" + executable
                               + "' is not installed or not available on PATH." + installHint);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                                                       PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX platforms still keep this file in the ignored session directory.
        }
    }

    private static String safeId(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    record PreparedCommand(List<String> command, List<Path> cleanupFiles) {
    }
}
