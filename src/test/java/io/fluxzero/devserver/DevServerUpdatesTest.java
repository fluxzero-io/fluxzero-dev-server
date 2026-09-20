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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DevServerUpdatesTest {
    @Test void pinsNeverCheckOrPrepare() {
        try (var updates = new DevServerUpdates(false, args -> {fail("must not invoke CLI");return Map.of();}, false)) {
            updates.check();
            assertThrows(IllegalStateException.class, () -> updates.prepare("1.99.0"));
        }
    }
    @Test void offlineChecksDoNotClaimAnUpdate() {
        try (var updates = new DevServerUpdates(true, args -> {throw new Exception("offline");}, false)) {
            updates.check(); assertEquals("unavailable", updates.status().get("status"));
        }
    }
    @Test void requiresSameVersionAndAUsablePreparedJar(@TempDir Path root) throws Exception {
        Path artifact = root.resolve("server.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new java.util.jar.JarEntry("io/fluxzero/devserver/DevServerBootstrapMain.class"));jar.closeEntry();
        }
        var calls = new AtomicInteger();
        try (var updates = new DevServerUpdates(true, args -> {
            calls.incrementAndGet();
            return Map.of("status", "available", "latestVersion", "1.99.0", "artifact", artifact.toString());
        }, false)) {
            updates.check();
            assertThrows(IllegalStateException.class, () -> updates.prepare("1.98.0"));
            assertEquals(1, calls.get());
            assertEquals(artifact, updates.prepare("1.99.0"));
            Files.writeString(artifact,"broken");
            assertThrows(Exception.class, () -> updates.prepare("1.99.0"));
        }
    }
    @Test void replacementRetainsArgumentsAndUsesPreparedClasspath(@TempDir Path root) {
        Path jar = root.resolve("new server.jar");
        var arguments = new String[]{"--project-dir",root.toString(),"--port","4321","--profile=demo","--no-tests"};
        var command = DevServerUpgrade.command(jar, arguments);
        assertEquals(jar.toString(),command.get(command.indexOf("-cp") + 1));
        assertEquals(List.of(arguments),command.subList(command.size()-arguments.length,command.size()));
        assertTrue(command.contains("--bootstrap-background"));
    }
}
