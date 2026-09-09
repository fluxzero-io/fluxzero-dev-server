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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WindowsProcessEnvironment {
    private static final String MACHINE_ENVIRONMENT =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";
    private static final String USER_ENVIRONMENT = "HKCU\\Environment";
    private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(2);
    private static final long CACHE_NANOS = Duration.ofSeconds(1).toNanos();
    private static final Pattern REGISTRY_PATH = Pattern.compile(
            "(?i)^\\s*Path\\s+REG_(?:EXPAND_)?SZ\\s+(.*?)\\s*$");
    private static final Pattern VARIABLE = Pattern.compile("%([^%]+)%");
    private static volatile CachedPaths cachedPaths = new CachedPaths(Long.MIN_VALUE, RegistryPaths.empty());

    private WindowsProcessEnvironment() {
    }

    static void apply(Map<String, String> target, Map<String, String> overrides) {
        String inheritedPath = value(target, "PATH");
        target.putAll(overrides);
        String explicitPath = value(overrides, "PATH");
        if (explicitPath != null) {
            putPath(target, explicitPath);
            return;
        }
        applyRefreshedPath(target, inheritedPath, currentRegistryPaths(target));
    }

    static void applyRefreshedPath(Map<String, String> target, String inheritedPath, RegistryPaths registryPaths) {
        String merged = mergePaths(
                List.of(registryPaths.machine(), registryPaths.user(), nullToEmpty(inheritedPath)), target);
        if (merged.isBlank()) {
            return;
        }
        putPath(target, merged);
    }

    private static void putPath(Map<String, String> target, String value) {
        String pathKey = target.keySet().stream()
                .filter(key -> "PATH".equalsIgnoreCase(key))
                .findFirst().orElse("Path");
        new ArrayList<>(target.keySet()).stream()
                .filter(key -> "PATH".equalsIgnoreCase(key) && !pathKey.equals(key))
                .forEach(target::remove);
        target.put(pathKey, value);
    }

    static String mergePaths(List<String> paths, Map<String, String> environment) {
        Map<String, String> entries = new LinkedHashMap<>();
        paths.stream().filter(path -> path != null && !path.isBlank())
                .flatMap(path -> List.of(path.split(";", -1)).stream())
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> expandVariables(entry, environment))
                .forEach(entry -> entries.putIfAbsent(normalize(entry), entry));
        return String.join(";", entries.values());
    }

    static String parseRegistryPath(String output) {
        return output.lines().map(REGISTRY_PATH::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .findFirst().orElse("");
    }

    private static RegistryPaths currentRegistryPaths(Map<String, String> environment) {
        long now = System.nanoTime();
        CachedPaths current = cachedPaths;
        if (current.loadedAt() > 0 && now - current.loadedAt() < CACHE_NANOS) {
            return current.paths();
        }
        synchronized (WindowsProcessEnvironment.class) {
            current = cachedPaths;
            if (current.loadedAt() > 0 && now - current.loadedAt() < CACHE_NANOS) {
                return current.paths();
            }
            RegistryPaths paths = readRegistryPaths(environment);
            cachedPaths = new CachedPaths(now, paths);
            return paths;
        }
    }

    private static RegistryPaths readRegistryPaths(Map<String, String> environment) {
        String executable = registryExecutable(environment);
        return new RegistryPaths(
                readRegistryPath(executable, MACHINE_ENVIRONMENT),
                readRegistryPath(executable, USER_ENVIRONMENT));
    }

    private static String readRegistryPath(String executable, String key) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "query", key, "/v", "Path")
                    .redirectErrorStream(true).start();
            Process started = process;
            AtomicReference<byte[]> output = new AtomicReference<>(new byte[0]);
            Thread reader = Thread.ofVirtual().name("fluxzero-dev-windows-environment").start(() -> {
                try {
                    output.set(started.getInputStream().readAllBytes());
                } catch (IOException ignored) {
                    // A failed environment refresh must not prevent the managed process from starting.
                }
            });
            if (!process.waitFor(REGISTRY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(REGISTRY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            reader.join(REGISTRY_TIMEOUT.toMillis());
            return reader.isAlive() ? "" : parseRegistryPath(
                    new String(output.get(), Charset.defaultCharset()));
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String registryExecutable(Map<String, String> environment) {
        String systemRoot = value(environment, "SystemRoot");
        if (systemRoot == null) {
            systemRoot = value(environment, "WINDIR");
        }
        if (systemRoot != null) {
            try {
                Path executable = Path.of(systemRoot, "System32", "reg.exe");
                if (Files.isRegularFile(executable)) {
                    return executable.toString();
                }
            } catch (InvalidPathException | SecurityException ignored) {
                // Fall back to normal Windows executable lookup.
            }
        }
        return "reg.exe";
    }

    private static String expandVariables(String input, Map<String, String> environment) {
        String result = input;
        for (int i = 0; i < 5; i++) {
            Matcher matcher = VARIABLE.matcher(result);
            StringBuilder expanded = new StringBuilder();
            boolean replaced = false;
            while (matcher.find()) {
                String replacement = value(environment, matcher.group(1));
                if (replacement == null) {
                    continue;
                }
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
                replaced = true;
            }
            if (!replaced) {
                return result;
            }
            matcher.appendTail(expanded);
            result = expanded.toString();
        }
        return result;
    }

    private static String normalize(String path) {
        String result = path;
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1);
        }
        result = result.replace('/', '\\');
        while (result.length() > 3 && result.endsWith("\\")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.toLowerCase(Locale.ROOT);
    }

    private static String value(Map<String, String> environment, String key) {
        return environment.entrySet().stream()
                .filter(entry -> key.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record RegistryPaths(String machine, String user) {
        RegistryPaths {
            machine = nullToEmpty(machine);
            user = nullToEmpty(user);
        }

        static RegistryPaths empty() {
            return new RegistryPaths("", "");
        }
    }

    private record CachedPaths(long loadedAt, RegistryPaths paths) {
    }
}
