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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevProjectLayoutTest {

    @Test
    void recognizesCompletelyEmptyGreenfieldWorkspace(@TempDir Path project) {
        assertTrue(DevProjectLayout.isGreenfieldWorkspace(DevServerConfig.defaults(project)));
    }

    @Test
    void recognizesGreenfieldWorkspaceWithOnlyManagedRuntimeState(@TempDir Path project) throws Exception {
        Path session = project.resolve(".fluxzero/dev/session.json");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{}");

        assertTrue(DevProjectLayout.isGreenfieldWorkspace(DevServerConfig.defaults(project)));
    }

    @Test
    void rejectsUnrelatedContentWithoutBuildRoot(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("notes.txt"), "unrelated");

        assertFalse(DevProjectLayout.isGreenfieldWorkspace(DevServerConfig.defaults(project)));
    }

    @Test
    void rejectsTrackedFluxzeroConfigurationWithoutManagedRuntimeDirectory(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve(".fluxzero"));
        Files.writeString(project.resolve(".fluxzero/dev.yaml"), "version: 1\n");

        assertFalse(DevProjectLayout.isGreenfieldWorkspace(DevServerConfig.defaults(project)));
    }

    @Test
    void buildProjectUsesNormalProjectLifecycle(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        assertTrue(DevProjectLayout.isBuildProject(project));
        assertFalse(DevProjectLayout.isGreenfieldWorkspace(DevServerConfig.defaults(project)));
    }
}
