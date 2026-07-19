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

enum BuildTool {
    MAVEN,
    GRADLE;

    static BuildTool detect(Path projectDirectory) {
        if (Files.isRegularFile(projectDirectory.resolve("pom.xml"))
            || Files.isRegularFile(projectDirectory.resolve(windows() ? "mvnw.cmd" : "mvnw"))) {
            return MAVEN;
        }
        if (Files.isRegularFile(projectDirectory.resolve("build.gradle"))
            || Files.isRegularFile(projectDirectory.resolve("build.gradle.kts"))
            || Files.isRegularFile(projectDirectory.resolve("settings.gradle"))
            || Files.isRegularFile(projectDirectory.resolve("settings.gradle.kts"))
            || Files.isRegularFile(projectDirectory.resolve(windows() ? "gradlew.bat" : "gradlew"))) {
            return GRADLE;
        }
        // Preserve the existing Maven fallback for generated/test fixtures and projects that add their POM later.
        return MAVEN;
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
