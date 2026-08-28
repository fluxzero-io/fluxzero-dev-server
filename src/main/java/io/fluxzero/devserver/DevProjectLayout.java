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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class DevProjectLayout {
    private static final List<String> BUILD_FILES = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    private DevProjectLayout() {
    }

    static void requireBuildProject(Path projectDirectory) {
        if (!isBuildProject(projectDirectory)) {
            throw new DevServerStartupException(
                    "No Maven or Gradle project found in '" + projectDirectory.toAbsolutePath().normalize()
                    + "'. Run fz dev from a project root or initialize a new project first.");
        }
    }

    static void requireBuildProjectOrGreenfieldWorkspace(DevServerConfig config) {
        if (config.projects().stream().allMatch(project -> isBuildProject(project.directory()))
            || isGreenfieldWorkspace(config)) {
            return;
        }
        Path invalidProject = config.projects().stream()
                .map(DevBuildProject::directory)
                .filter(project -> !isBuildProject(project))
                .findFirst().orElse(config.projectDirectory());
        requireBuildProject(invalidProject);
    }

    static boolean isGreenfieldWorkspace(DevServerConfig config) {
        if (config.projects().size() != 1
            || !config.projects().getFirst().directory().equals(config.projectDirectory())
            || isBuildProject(config.projectDirectory())) {
            return false;
        }
        return containsOnlyManagedDevState(config.projectDirectory());
    }

    static boolean containsOnlyManagedDevState(Path projectDirectory) {
        if (!Files.isDirectory(projectDirectory, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(projectDirectory)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(projectDirectory)) {
            return entries.allMatch(DevProjectLayout::isManagedDevState);
        } catch (Exception e) {
            throw new DevServerStartupException(
                    "Could not inspect project directory '" + projectDirectory.toAbsolutePath().normalize() + "'", e);
        }
    }

    private static boolean isManagedDevState(Path path) {
        if (!".fluxzero".equals(path.getFileName().toString())
            || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(path)) {
            List<Path> children = entries.toList();
            return children.size() == 1 && children.getFirst().getFileName().toString().equals("dev")
                   && !Files.isSymbolicLink(children.getFirst())
                   && Files.isDirectory(children.getFirst(), LinkOption.NOFOLLOW_LINKS);
        } catch (Exception e) {
            throw new DevServerStartupException(
                    "Could not inspect managed Fluxzero state in '" + path.toAbsolutePath().normalize() + "'", e);
        }
    }

    static boolean isBuildProject(Path projectDirectory) {
        return BUILD_FILES.stream().anyMatch(file -> Files.isRegularFile(projectDirectory.resolve(file)));
    }
}
