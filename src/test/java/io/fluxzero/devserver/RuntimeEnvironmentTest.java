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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEnvironmentTest {
    @TempDir Path directory;

    @Test
    void readsArbitraryEnvironmentOverridesAndPreservesThemForBuildProjects() throws Exception {
        write("runtime:\n  env:\n    FLUXZERO_LOG_RETENTION: P2D\n    CUSTOM_SETTING: arbitrary-value\n");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", directory.toString()});
        assertEquals(Map.of("FLUXZERO_LOG_RETENTION", "P2D", "CUSTOM_SETTING", "arbitrary-value"), config.runtime().env());
        assertEquals(config.runtime(), config.forProject(config.projects().getFirst()).runtime());
        assertFalse(config.toString().contains("arbitrary-value"));
    }

    @Test
    void acceptsProfileRuntimeAndDefaultsToNoOverrides() throws Exception {
        assertTrue(DevServerConfig.fromArgs(new String[]{"--project-dir", directory.toString()}).runtime().env().isEmpty());
        write("defaultProfile: training\nprofiles:\n  training:\n    runtime:\n      env: {FLUXZERO_LOG_RETENTION: P2D}\n");
        assertEquals("P2D", DevServerConfig.fromArgs(new String[]{"--project-dir", directory.toString()}).runtime().env().get("FLUXZERO_LOG_RETENTION"));
    }

    @Test
    void rejectsInvalidNamesWithoutLeakingValues() {
        var error = assertThrows(IllegalArgumentException.class, () -> new DevRuntimeConfig(Map.of("bad=name", "private-value")));
        assertFalse(error.getMessage().contains("private-value"));
        assertThrows(IllegalArgumentException.class, () -> new DevRuntimeConfig(Map.of("VALUE", "bad\0value")));
    }

    @Test
    void processReceivesOverridesWithoutChangingParentEnvironment() throws Exception {
        var config = new DevRuntimeConfig(Map.of("FLUXZERO_LOG_RETENTION", "P2D", "CUSTOM_SETTING", "a value"));
        var output = new java.util.concurrent.CopyOnWriteArrayList<String>();
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = ProcessUtils.start(List.of(java, "-cp", System.getProperty("java.class.path"), Echo.class.getName()), directory, config.env(), output::add);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals(List.of("P2D|a value"), output);
    }

    private void write(String content) throws Exception {
        Files.createDirectories(directory.resolve(".fluxzero"));
        Files.writeString(directory.resolve(DevProjectConfig.FILE), "version: 1\n" + content);
    }

    public static class Echo {
        public static void main(String[] args) {
            System.out.println(System.getenv("FLUXZERO_LOG_RETENTION") + "|" + System.getenv("CUSTOM_SETTING"));
        }
    }
}
