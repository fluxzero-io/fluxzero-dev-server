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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FrontendBuildPauseTest {
    @Test
    void stopsTheRealProcessTreeAndResumesReadiness(@TempDir Path project) throws Exception {
        String command = DevMonitoring.launchCommand(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                Path.of(FrontendFixtureServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), FrontendFixtureServer.class.getName(), "{frontendPort}"),
                ProcessUtils.isWindows());
        var config = new DevServerConfig(project, null, "pause-test", null, false, false, false,
                Duration.ofSeconds(10), DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.command(command), List.of());
        var output = new CopyOnWriteArrayList<String>();
        try (var frontend = FrontendProcess.start(config, ignored -> {}, output::add)) {
            assertTrue(await(frontend::ready), output.toString());
            long pid = frontend.status().pid();
            var children = ProcessHandle.of(pid).orElseThrow().descendants().toList();
            frontend.suspendForBuild();
            assertEquals("paused", frontend.status().state());
            assertFalse(ProcessUtils.isAlive(pid));
            assertTrue(children.stream().noneMatch(ProcessHandle::isAlive));
            assertFalse(frontend.ready());
            frontend.refreshAfterManagedUpdate();
            assertEquals("paused", frontend.status().state());
            frontend.resumeAfterBuild();
            assertTrue(await(frontend::ready), output.toString());
            assertNotEquals(pid, frontend.status().pid());
        }
    }

    private static boolean await(BooleanSupplier ready) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < end) {
            if (ready.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return ready.getAsBoolean();
    }
}
