/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

record ChangeSummary(List<String> paths) {
    static ChangeSummary of(Path projectDirectory, Set<Path> changedFiles) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        List<String> paths = changedFiles.stream().map(path -> {
            Path absolute = path.isAbsolute() ? path.toAbsolutePath().normalize()
                    : root.resolve(path).toAbsolutePath().normalize();
            try {
                return root.relativize(absolute).toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                return absolute.toString().replace('\\', '/');
            }
        }).sorted().toList();
        return new ChangeSummary(paths);
    }

    String displayPaths() {
        if (paths.isEmpty()) {
            return "unknown change";
        }
        int shown = Math.min(3, paths.size());
        String result = String.join(", ", paths.subList(0, shown));
        return paths.size() == shown ? result : result + " (and " + (paths.size() - shown) + " more)";
    }

    String category() {
        if (paths.stream().allMatch(ChangeSummary::testPath)) {
            return paths.size() == 1 ? "test source changed" : paths.size() + " test files changed";
        }
        if (paths.stream().allMatch(ChangeSummary::commandPath)) {
            return paths.size() == 1 ? "dev command changed" : paths.size() + " dev commands changed";
        }
        if (paths.stream().allMatch(path -> path.endsWith(".java"))) {
            return paths.size() == 1 ? "Java source changed" : paths.size() + " Java sources changed";
        }
        if (paths.stream().allMatch(path -> path.endsWith(".kt"))) {
            return paths.size() == 1 ? "Kotlin source changed" : paths.size() + " Kotlin sources changed";
        }
        if (paths.stream().anyMatch(path -> path.equals("pom.xml") || path.endsWith("/pom.xml"))) {
            return "Maven build changed";
        }
        if (paths.stream().anyMatch(ChangeSummary::gradleBuildPath)) {
            return "Gradle build changed";
        }
        return paths.size() == 1 ? "project input changed" : paths.size() + " project inputs changed";
    }

    private static boolean testPath(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    private static boolean commandPath(String path) {
        String config = DevProjectConfig.FILE.toString().replace('\\', '/');
        String commandDirectory = DevCommandPipeline.COMMAND_DIRECTORY.toString().replace('\\', '/');
        return path.equals(config) || path.startsWith(commandDirectory + "/")
               || path.contains("/" + commandDirectory + "/");
    }

    private static boolean gradleBuildPath(String path) {
        return path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")
               || path.endsWith("settings.gradle") || path.endsWith("settings.gradle.kts")
               || path.endsWith("gradle.properties") || path.endsWith("gradle/libs.versions.toml");
    }
}
