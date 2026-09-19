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
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DevMcpWorkspaceTest {
    @Test void preservesRootBuildAndDoesNotGuessBetweenApps(@TempDir Path root) throws Exception {
        Path app = Files.createDirectory(root.resolve("app"));
        Files.createDirectories(app.resolve(".fluxzero"));
        Files.writeString(app.resolve(".fluxzero/dev.yaml"), "version: 1");
        assertEquals(app, DevMcpWorkspace.initialDirectory(root));
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"root\"");
        assertEquals(root, DevMcpWorkspace.initialDirectory(root));
        Files.delete(root.resolve("settings.gradle.kts"));
        Path second = Files.createDirectories(root.resolve("second/.fluxzero"));
        Files.writeString(second.resolve("dev.yaml"), "version: 1");
        assertEquals(root, DevMcpWorkspace.initialDirectory(root));
    }

    @Test void ignoresBuildOutputsAndDoesNotStartAnything(@TempDir Path root) throws Exception {
        for (String name : new String[]{"node_modules", "target", "build", ".cache"}) {
            Path config = Files.createDirectories(root.resolve(name).resolve(".fluxzero"));
            Files.writeString(config.resolve("dev.yaml"), "version: 1");
        }
        assertEquals(root, DevMcpWorkspace.initialDirectory(root));
        assertTrue(DevMcpWorkspace.candidates(root).isEmpty());
        assertFalse(Files.exists(root.resolve(".fluxzero")));
    }
}
