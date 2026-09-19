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
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.*;

class DevMonitoringVersionTest {
    @TempDir Path directory;

    @Test void readsTheLaunchedJarVersionWithMavenFallback() throws Exception {
        Path jar = directory.resolve("auditlog.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("META-INF/maven/io.fluxzero/auditlog/pom.properties"));
            output.write("version=0.0.1-SNAPSHOT\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertEquals("0.0.1-SNAPSHOT", DevMonitoring.artifactVersion(jar));
        manifest.getMainAttributes().putValue("Implementation-Version", "2.3.4");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        assertEquals("2.3.4", DevMonitoring.artifactVersion(jar));
    }

    @Test void omitsUnknownVersionsWithoutBlockingMonitoring() throws Exception {
        Path jar = directory.resolve("unversioned.jar");
        assertNull(DevMonitoring.artifactVersion(jar));
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) { }
        assertNull(DevMonitoring.artifactVersion(jar));
    }
}
