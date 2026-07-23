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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevProjectConfigMainTest {
    @Test
    void referenceIsAcceptedByTheCurrentProjectConfigParser(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, DevProjectConfigMain.reference());

        DevProjectConfig config = DevProjectConfig.load(projectDirectory);

        assertEquals(1, config.version());
        assertEquals("local", config.environment());
        assertEquals("app", config.apps().getFirst());
        assertEquals("worker", config.applicationConfig().get("worker-local").application());
        assertEquals("frontend", config.frontend().directory());
        assertTrue(config.frontend().command().contains("{port}"));
        assertEquals("/api", config.frontend().backendPaths().getFirst());
    }
}
