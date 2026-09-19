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
import static org.junit.jupiter.api.Assertions.*;

class DevMonitoringConfigTest {
    @TempDir Path project;
    @Test void loadsMonitoringFromSelectedProfile() throws Exception {
        Files.createDirectories(project.resolve(".fluxzero"));
        Files.writeString(project.resolve(".fluxzero/dev.yaml"), """
                version: 1
                profiles:
                  monitored:
                    monitoring:
                      auditlogJar: auditlog.jar
                      uiDirectory: ui
                      storage: testserver
                  plain:
                    environment: local
                """);
        assertEquals("testserver", DevProjectConfig.load(project).select("monitored").config().monitoring().storage());
        assertNull(DevProjectConfig.load(project).select("plain").config().monitoring());
    }
    @Test void rejectsSubdayVictoriaLogsRetentionAndUnsupportedPlatform() {
        assertThrows(IllegalArgumentException.class, () -> new DevMonitoringConfig("a.jar", "ui", "victorialogs", null, null, null, "PT15M", null, null));
        assertEquals("darwin-arm64", VictoriaLogsArtifact.platform("Mac OS X", "aarch64"));
        assertEquals("linux-amd64", VictoriaLogsArtifact.platform("Linux", "amd64"));
        assertEquals("windows-amd64", VictoriaLogsArtifact.platform("Windows 11", "amd64"));
        assertThrows(IllegalArgumentException.class, () -> VictoriaLogsArtifact.platform("Windows 11", "aarch64"));
    }
    @Test void quotesWindowsAndUnixProcessCommandsWithoutLosingOwnership() {
        var args = java.util.List.of("C:/Program Files/Java/bin/java", "-jar", "my auditlog.jar");
        assertTrue(DevMonitoring.launchCommand(args, true).startsWith("\"C:/Program Files"));
        assertFalse(DevMonitoring.launchCommand(args, true).contains("wait $!"));
        assertTrue(DevMonitoring.launchCommand(java.util.List.of("/my folder/java", "-jar", "app.jar"), false).endsWith(" & wait $!"));
        assertThrows(IllegalArgumentException.class, () -> DevMonitoring.launchCommand(java.util.List.of("%TEMP%/java"), true));
    }
    @Test void launchesMonitoringArgumentsThroughTheOwnedShell() throws Exception {
        Path classes = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        String executable = Path.of(System.getProperty("java.home"), "bin",
                ProcessUtils.isWindows() ? "java.exe" : "java").toString();
        var args = java.util.List.of(executable, "-cp", classes.toString(), ArgumentFixture.class.getName(),
                "path with spaces", "value&literal", "Aa_Zz !?");
        var output = new java.util.concurrent.CopyOnWriteArrayList<String>();
        Process child = ProcessUtils.start(ProcessUtils.shellCommand(
                DevMonitoring.launchCommand(args, ProcessUtils.isWindows()), "monitoring-test"),
                project, java.util.Map.of(), output::add);
        try {
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), output.toString());
            assertEquals(0, child.exitValue(), output.toString());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (output.size() < 3 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(args.subList(4, args.size()), output);
        } finally { ProcessUtils.forceStopTree(child); }
    }
    public static class ArgumentFixture {
        public static void main(String[] args) { for (String arg : args) System.out.println(arg); }
    }
    @Test void rejectsCorruptedDownload() throws Exception {
        Path archive = project.resolve("archive"); Files.writeString(archive, "broken");
        assertThrows(java.io.IOException.class, () -> VictoriaLogsArtifact.verify(archive, "invalid"));
    }
}
