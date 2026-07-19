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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class DevServerStartupOutputTest {

    @Test
    void namesSmallTestSelectionsAndCountsLargeOnes() {
        assertEquals("MolliePaymentTest, MollieTest", DevServer.testScope(TestStatus.running(List.of(
                "host.flux.service.mollie.MolliePaymentTest",
                "host.flux.service.mollie.MollieTest"), "changed test class")));
        assertEquals("GreetingHandlersTest#createsBaseGreeting", DevServer.testScope(TestStatus.running(List.of(
                "com.example.app.GreetingHandlersTest#createsBaseGreeting"), "test impact index")));
        assertEquals("5 selected tests", DevServer.testScope(TestStatus.running(
                List.of("example.OneTest", "example.TwoTest", "example.ThreeTest", "example.FourTest",
                        "example.FiveTest"),
                "changed test class")));
    }

    @Test
    void keepsPackagesWhenCompactTestNamesWouldBeAmbiguous() {
        assertEquals("sales.OrderTest, billing.OrderTest", DevServer.testScope(TestStatus.running(List.of(
                "sales.OrderTest", "billing.OrderTest"), "changed test class")));
    }

    @Test
    void doesNotPublishBrowserUrlWhenOnlyTheFrontendIsReady(@TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        DevServerConfig config = config(projectDirectory, FrontendConfig.command(command));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (DevServer server = server(config, output).start()) {
            assertTrue(await(() -> "running".equals(server.session().frontend().state())));
            String terminal = output.toString(StandardCharsets.UTF_8);
            assertFalse(terminal.contains("Fluxzero dev server ready"), terminal);
            assertFalse(terminal.contains("Open in browser"), terminal);
            assertFalse(terminal.contains(server.session().gateway().url()), terminal);
        }
    }

    @Test
    void reportsCompileFailureWithoutPublishingAUrl(@TempDir Path projectDirectory) throws Exception {
        installFailingMaven(projectDirectory);
        DevServerConfig config = config(projectDirectory, FrontendConfig.none());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (DevServer server = server(config, output).start()) {
            server.requestCompile(Set.of(projectDirectory.resolve("pom.xml")));
            assertTrue(await(() -> "failed".equals(server.session().compile().state())));
            assertTrue(await(() -> output.toString(StandardCharsets.UTF_8)
                    .contains("Fluxzero dev could not start")));

            String terminal = output.toString(StandardCharsets.UTF_8);
            assertTrue(terminal.contains("Fluxzero dev could not start"), terminal);
            assertTrue(terminal.contains("Compile       failed after"), terminal);
            assertTrue(terminal.contains("simulated startup compile failure"), terminal);
            assertTrue(terminal.contains("Problems      "), terminal);
            assertTrue(terminal.contains("Log           "), terminal);
            assertTrue(terminal.contains("Watching for changes."), terminal);
            assertFalse(terminal.contains("Open in browser"), terminal);
            assertFalse(terminal.contains("Backend       "), terminal);
            assertFalse(terminal.contains(server.session().proxy().url()), terminal);
        }
    }

    private static DevServer server(DevServerConfig config, ByteArrayOutputStream output) {
        return new DevServer(config, ignored -> false,
                             new TerminalProgress(false, new PrintStream(output, true, StandardCharsets.UTF_8)));
    }

    private static DevServerConfig config(Path projectDirectory, FrontendConfig frontend) {
        return new DevServerConfig(
                projectDirectory, null, "startup-output-test", null,
                true, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                frontend, List.of());
    }

    private static void installFailingMaven(Path projectDirectory) throws Exception {
        Path mvnw = projectDirectory.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                echo "simulated startup compile failure"
                exit 7
                """);
        assertTrue(mvnw.toFile().setExecutable(true));
    }

    private static boolean await(CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
