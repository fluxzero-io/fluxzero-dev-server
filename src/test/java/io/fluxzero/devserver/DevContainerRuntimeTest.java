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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DevContainerRuntimeTest {
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void closingDuringRuntimePreparationIsBoundedAndStopsTheCliTree(@TempDir Path project) throws Exception {
        Path script = project.resolve("runtime"), started = project.resolve("started");
        Files.writeString(script, """
                #!/bin/sh
                if [ "$1" = info ]; then
                  echo $$ > started
                  sleep 30
                fi
                """);
        assertTrue(script.toFile().setExecutable(true));
        var container = new DevContainerConfig("registry.example/web@sha256:" + "a".repeat(64), script.toString(), "never",
                Map.of(), List.of(), null, null, null, false, List.of(), List.of(), List.of(), "docker", false);
        var config = new DevServiceConfig(null, null, null, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness(null, null, Pattern.compile("READY"), Duration.ofSeconds(40)), List.of(), null, container);
        var service = DevServiceProcess.prepare("web", config, project, UUID.randomUUID().toString(), Duration.ofSeconds(5), ignored -> {}, ignored -> {});
        var failures = new CopyOnWriteArrayList<Throwable>();
        Thread start = Thread.ofVirtual().start(() -> {try {service.start();} catch (Throwable e) {failures.add(e);}});
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(started));
            long pid = Long.parseLong(Files.readString(started).strip());
            long began = System.nanoTime();
            service.close();
            start.join(Duration.ofSeconds(2));
            assertFalse(start.isAlive());
            assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(7)) < 0);
            assertFalse(ProcessUtils.isAlive(pid));
            assertEquals("stopped", service.status().state());
            assertEquals(1, failures.size());
            DevContainerRuntime.reconcile(project);
        } finally {service.close(); start.interrupt();}
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void registryFailureCannotExposeRuntimeCredentialOutput(@TempDir Path project) throws Exception {
        Path script = project.resolve("podman");
        Files.writeString(script, """
                #!/bin/sh
                case "$1 $2" in
                  'info ') exit 0;;
                  'image inspect') echo sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; exit 0;;
                  'manifest exists') exit 1;;
                  'manifest inspect') echo 'unauthorized token=private-fixture-token' >&2; exit 1;;
                  'pull '*) echo unexpected-pull >> unexpected; exit 1;;
                esac
                """);
        assertTrue(script.toFile().setExecutable(true));
        var container = new DevContainerConfig("registry.example/web@sha256:" + "a".repeat(64), script.toString(), "verify",
                Map.of(), List.of(), null, null, null, false, List.of(), List.of(), List.of(), null, false);
        assertEquals("podman", container.driver());
        var runtime = new DevContainerRuntime(container, project, project, UUID.randomUUID().toString(), "web",
                Map.of(), Map.of(), new DevPlaceholderResolver(Map.of(), java.util.Set.of()), ignored -> {});
        var error = assertThrows(IllegalStateException.class, () -> runtime.prepare(Duration.ofSeconds(5)));
        assertFalse(error.getMessage().contains("private-fixture-token"));
        assertEquals("true", runtime.metadata().get("container.cachedBeforePull"));
        assertEquals("failed", runtime.metadata().get("container.registryAccess"));
        assertEquals("registry-access", runtime.metadata().get("container.failure"));
        assertFalse(Files.exists(project.resolve("unexpected")));
        assertNull(runtime.close(Duration.ofSeconds(5)));
        DevContainerRuntime.reconcile(project);
    }
}
