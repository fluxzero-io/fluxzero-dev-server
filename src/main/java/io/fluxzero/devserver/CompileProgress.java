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

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompileProgress {
    private static final Pattern PLUGIN = Pattern.compile(
            "^--- ([^:]+):.+:([^ ]+) \\(([^)]+)\\) @ ([^ ]+) ---$");
    private static final Pattern JAVA_SOURCES = Pattern.compile("^Compiling (\\d+) source files?.*\\bjavac\\b.*$");
    private static final Pattern RESOURCES = Pattern.compile("^Copying (\\d+) resources?.*$");

    private final String action;
    private String module;

    CompileProgress(boolean recompiling) {
        action = recompiling ? "Rebuilding" : "Building";
    }

    String initialMessage() {
        return action;
    }

    Optional<String> update(String rawLine) {
        String line = clean(rawLine);
        String buildingModule = buildingModule(line);
        if (buildingModule != null) {
            module = buildingModule;
            return Optional.of(message(null));
        }
        Matcher plugin = PLUGIN.matcher(line);
        if (plugin.matches()) {
            module = plugin.group(4);
            return Optional.of(message(pluginDetail(plugin.group(1), plugin.group(2), plugin.group(3))));
        }
        Matcher javaSources = JAVA_SOURCES.matcher(line);
        if (javaSources.matches()) {
            return Optional.of(message("compiling " + javaSources.group(1) + " Java sources"));
        }
        Matcher resources = RESOURCES.matcher(line);
        if (resources.matches()) {
            return Optional.of(message("copying " + resources.group(1) + " resources"));
        }
        if (line.startsWith("Nothing to compile")) {
            return Optional.of(message("classes are up to date"));
        }
        if (line.startsWith("maven-full because") || line.startsWith("maven-compile because")) {
            return Optional.of(message("running Maven build"));
        }
        if (line.startsWith("javac-fast because")) {
            return Optional.of(message("running fast Java compile"));
        }
        if (line.startsWith("javac-fast failed")) {
            return Optional.of(message("falling back to Maven"));
        }
        return Optional.empty();
    }

    private String pluginDetail(String plugin, String goal, String execution) {
        String normalized = plugin.toLowerCase(Locale.ROOT);
        if (normalized.contains("resources")) {
            return goal.toLowerCase(Locale.ROOT).contains("test")
                    ? "processing test resources" : "processing resources";
        }
        if (normalized.contains("compiler")) {
            return goal.toLowerCase(Locale.ROOT).contains("test")
                    ? "compiling test sources" : "compiling Java sources";
        }
        if (normalized.contains("kotlin")) {
            return goal.toLowerCase(Locale.ROOT).contains("test")
                    ? "compiling Kotlin test sources" : "compiling Kotlin sources";
        }
        if (normalized.contains("dependency")) {
            return "resolving dependencies";
        }
        String operation = execution.startsWith("default") ? goal : execution;
        return "running " + operation.replace('-', ' ');
    }

    private String message(String detail) {
        StringBuilder result = new StringBuilder(action).append(": Backend");
        if (module != null && !module.isBlank()) {
            result.append(": ").append(module);
        }
        if (detail != null && !detail.isBlank()) {
            result.append(": ").append(detail);
        }
        return result.toString();
    }

    private static String clean(String line) {
        String result = line == null ? "" : line.strip();
        for (String prefix : new String[]{"[compile]", "[stderr]", "[INFO]", "[WARNING]"}) {
            if (result.startsWith(prefix)) {
                result = result.substring(prefix.length()).stripLeading();
            }
        }
        return result;
    }

    private static String buildingModule(String line) {
        if (!line.startsWith("Building ")) {
            return null;
        }
        String value = line.substring("Building ".length()).strip()
                .replaceFirst("\\s+\\[\\d+/\\d+]$", "").strip();
        int versionSeparator = value.lastIndexOf(' ');
        return versionSeparator < 1 ? null : value.substring(0, versionSeparator).strip();
    }
}
