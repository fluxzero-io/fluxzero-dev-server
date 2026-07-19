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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class MavenCommand {
    private MavenCommand() {
    }

    static List<String> command(Path projectDirectory, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable(projectDirectory));
        command.add("--batch-mode");
        command.add("--no-transfer-progress");
        command.addAll(List.of(args));
        return command;
    }

    static Map<String, String> environment() {
        return Map.of("MAVEN_OPTS", jvmOptions(System.getenv("MAVEN_OPTS")));
    }

    static String jvmOptions(String existing) {
        java.util.ArrayList<String> options = new java.util.ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            options.add(existing);
        }
        addIfMissing(options, "--enable-native-access=ALL-UNNAMED");
        if (Runtime.version().feature() >= 24) {
            addIfMissing(options, "--sun-misc-unsafe-memory-access=allow");
        }
        return String.join(" ", options);
    }

    private static void addIfMissing(List<String> options, String option) {
        if (options.stream().noneMatch(value -> value.contains(option))) {
            options.add(option);
        }
    }

    private static String executable(Path projectDirectory) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path wrapper = projectDirectory.resolve(windows ? "mvnw.cmd" : "mvnw");
        return Files.isRegularFile(wrapper) ? wrapper.toAbsolutePath().toString() : "mvn";
    }
}
