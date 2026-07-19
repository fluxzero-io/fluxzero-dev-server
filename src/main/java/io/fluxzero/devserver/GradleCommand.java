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

final class GradleCommand {
    private GradleCommand() {
    }

    static List<String> command(Path projectDirectory, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable(projectDirectory));
        command.add("--console=plain");
        if (Boolean.getBoolean("fluxzero.dev.gradle.noDaemon")) {
            command.add("--no-daemon");
        }
        command.addAll(List.of(args));
        return command;
    }

    static Map<String, String> environment() {
        return Map.of("GRADLE_OPTS", MavenCommand.jvmOptions(System.getenv("GRADLE_OPTS")));
    }

    private static String executable(Path projectDirectory) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path wrapper = projectDirectory.resolve(windows ? "gradlew.bat" : "gradlew");
        return Files.isRegularFile(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
    }
}
