/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SelectedMavenSdkVersionsTest {
    @Test
    void resolvesNamedTestApplicationBeforeCompilation(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        Path source = root.resolve("src/test/java/demo/Rebound.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class Rebound { static void main(String[] args) {} }");
        Files.createDirectories(root.resolve(".fluxzero"));
        Files.writeString(root.resolve(".fluxzero/dev.yaml"), """
                version: 1
                apps: [local]
                applicationConfig:
                  local:
                    application: rebound
                """);
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString()});
        var selected = SelectedMavenSdkVersions.select(config, List.copyOf(MavenReactor.load(root).modules()));
        assertEquals(1, selected.size()); assertEquals("local", selected.getFirst().id());
        assertTrue(selected.getFirst().test()); assertEquals(root, selected.getFirst().module().directory());
        Files.writeString(root.resolve(".fluxzero/dev.yaml"), "version: 1\napps: [absent]\n");
        var absent = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString()});
        assertThrows(DevServerStartupException.class, () -> SelectedMavenSdkVersions.select(absent,
                List.copyOf(MavenReactor.load(root).modules())));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void interruptStopsOwnedMavenProcess(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        Path wrapper = root.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\necho $$ > inspection.pid\nexec sleep 60\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString(), "--app", "app"});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { SelectedMavenSdkVersions.detect(config); } catch (Throwable e) { failure.set(e); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Path pidFile = root.resolve("inspection.pid");
        try {
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile));
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            worker.interrupt(); worker.join(5000);
            assertFalse(worker.isAlive());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertInstanceOf(DevServerStartupException.class, failure.get());
        } finally { worker.interrupt(); worker.join(5000); }
    }
}
