/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerControlMainTest {

    @Test
    void stopsOwnedProcessWhenCommandLineMetadataDoesNotContainProject(@TempDir Path projectDirectory)
            throws Exception {
        Process process = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("java.class.path"),
                                             SleepingFixture.class.getName()).start();
        try {
            DevSession base = DevSession.empty(DevServerConfig.defaults(projectDirectory));
            long processStartedAt = ProcessUtils.startedAt(process).orElseThrow();
            DevSession session = new DevSession(
                    base.sessionId(), process.pid(), base.devServerVersion(), base.projectDirectory(),
                    base.observability(), "running", base.runtime(), base.proxy(), base.gateway(), base.idp(),
                    base.app(), base.reload(), base.compile(), base.tests(), base.commands(), base.frontend(),
                    base.mcp(), processStartedAt, base.heartbeatAt(), base.updatedAt());
            new DevSessionStore(projectDirectory).writeSession(session);

            DevServerControlMain.main(new String[]{
                    "stop", "--project-dir", projectDirectory.toString(), "--force"
            });

            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "controlled process did not exit");
            assertFalse(process.isAlive());
        } finally {
            if (process.isAlive()) {
                ProcessUtils.forceStopTree(process);
            }
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static final class SleepingFixture {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Duration.ofMinutes(1));
        }
    }
}
