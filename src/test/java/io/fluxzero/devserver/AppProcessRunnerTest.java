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

import io.fluxzero.devserver.fixture.FixtureAppMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppProcessRunnerTest {

    @Test
    void startsAppProcessWithFluxzeroEnvironment(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, FixtureAppMain.class.getName(), "orders-app", "local",
                false, false, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of("--probe"));
        List<String> output = new CopyOnWriteArrayList<>();
        AppProcessRunner runner = new AppProcessRunner(config, "ws://localhost:1234", "http://localhost:5678",
                                                       "session-1",
                                                       output::add);
        BuildSnapshot snapshot = new BuildSnapshot(1, projectDirectory.resolve("build"), testClassesDirectory(),
                                                   List.of(), Instant.now());

        AppInstance app = runner.start(snapshot);
        try {
            assertTrue(await(output, "runtime=ws://localhost:1234"));
            assertTrue(await(output, "runtime.port=1234"));
            assertTrue(await(output, "runtime.system.port=1234"));
            assertTrue(await(output, "proxy=http://localhost:5678"));
            assertTrue(await(output, "proxy.port=5678"));
            assertTrue(await(output, "proxy.system.port=5678"));
            assertTrue(await(output, "application=orders-app"));
            assertTrue(await(output, "namespace=local"));
            assertTrue(await(output, "environment=local"));
            assertTrue(await(output, "session=session-1"));
            assertTrue(await(output, "auth.issuer=http://localhost:5678"));
            assertTrue(await(output, "auth.method=none"));
            assertTrue(await(output, "args=--probe"));
            assertTrue(await(output, "[stderr] fixture-stderr=available"));
            assertTrue(app.alive());
        } finally {
            app.stop(Duration.ofSeconds(2));
        }
        assertFalse(app.alive());
    }

    @Test
    void leavesAuthenticationPropertiesToAppForExternalIdp(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, FixtureAppMain.class.getName(), "orders-app", null,
                false, false, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of(), false,
                "local", List.of(), 0, IdpMode.EXTERNAL);
        List<String> output = new CopyOnWriteArrayList<>();
        AppProcessRunner runner = new AppProcessRunner(config, "ws://localhost:1234", "http://localhost:5678",
                                                       "session-external", output::add);
        BuildSnapshot snapshot = new BuildSnapshot(1, projectDirectory.resolve("build"), testClassesDirectory(),
                                                   List.of(), Instant.now());

        AppInstance app = runner.start(snapshot);
        try {
            assertTrue(await(output, "auth.issuer=null"));
            assertTrue(await(output, "auth.method=null"));
        } finally {
            app.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void distinguishesPublicFluxzeroUrlFromLegacyInternalProxyPort(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, FixtureAppMain.class.getName(), "orders-app", null,
                false, false, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of());
        List<String> output = new CopyOnWriteArrayList<>();
        AppProcessRunner runner = new AppProcessRunner(
                config, "ws://localhost:1234", "http://localhost:4200/_fluxzero", "http://localhost:5678",
                "session-gateway", (applicationName, instanceId, stream, line) -> output.add(line));
        BuildSnapshot snapshot = new BuildSnapshot(1, projectDirectory.resolve("build"), testClassesDirectory(),
                                                   List.of(), Instant.now());

        AppInstance app = runner.start(snapshot);
        try {
            assertTrue(await(output, "proxy=http://localhost:4200/_fluxzero"));
            assertTrue(await(output, "proxy.port=5678"));
            assertTrue(await(output, "proxy.system.port=5678"));
        } finally {
            app.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void launchesExplicitTestApplicationWithoutFrameworkSpecificConfiguration(@TempDir Path projectDirectory)
            throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "ignored", null,
                false, false, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of());
        List<String> output = new CopyOnWriteArrayList<>();
        AppProcessRunner runner = new AppProcessRunner(
                config, "ws://localhost:1234", "http://localhost:5678", "session-test-app", output::add);
        Path classes = testClassesDirectory();
        ApplicationBuild testApplication = new ApplicationBuild(
                "Rebound", ".", FixtureAppMain.class.getName(), List.of(classes), List.of(), true);
        BuildSnapshot snapshot = new BuildSnapshot(
                1, projectDirectory.resolve("build"), classes, List.of(), Instant.now(), CompileTiming.unknown(),
                List.of(testApplication));

        AppInstance app = runner.start(snapshot);
        try {
            assertTrue(await(output, "spring.profile=null"));
            assertTrue(await(output, "application=Rebound"));
        } finally {
            app.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void resolvesConfiguredSecretsOnlyInsideFakeOnePasswordChild(@TempDir Path projectDirectory) throws Exception {
        Path fakeOp = projectDirectory.resolve("fake-op");
        Path capture = projectDirectory.resolve("captured-reference.env");
        Files.writeString(fakeOp, """
                #!/bin/sh
                if [ "$1" != "run" ]; then exit 41; fi
                env_file="${2#--env-file=}"
                cp "$env_file" "$FAKE_OP_CAPTURE"
                export ENCRYPTION_KEY="resolved-only-in-child"
                shift 2
                if [ "$1" != "--" ]; then exit 42; fi
                shift
                exec "$@"
                """);
        fakeOp.toFile().setExecutable(true);
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "ignored", null,
                false, false, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of(), false,
                "local", List.of("rebound-encrypted"), 0, IdpMode.EXTERNAL,
                Map.of("rebound-encrypted", new DevApplicationConfig(
                        "rebound", null, Map.of("FEATURE_MODE", "encrypted"),
                        Map.of("ENCRYPTION_KEY", "op://Shared Vault/rebound/local encryption-key"))));
        List<String> output = new CopyOnWriteArrayList<>();
        AppProcessRunner runner = new AppProcessRunner(
                config, "ws://localhost:1234", "http://localhost:5678", "http://localhost:5678",
                "session-secret", (applicationName, instanceId, stream, line) -> output.add(line),
                new OnePasswordEnvironment(projectDirectory, fakeOp.toString()));
        Path classes = testClassesDirectory();
        ApplicationBuild application = new ApplicationBuild(
                "Rebound", ".", FixtureAppMain.class.getName(), List.of(classes), List.of(), false,
                "rebound-encrypted", Map.of("FEATURE_MODE", "encrypted",
                                             "FAKE_OP_CAPTURE", capture.toString()),
                Map.of("ENCRYPTION_KEY", "op://Shared Vault/rebound/local encryption-key"));
        BuildSnapshot snapshot = new BuildSnapshot(
                1, projectDirectory.resolve("build"), classes, List.of(), Instant.now(), CompileTiming.unknown(),
                List.of(application));

        AppInstance app = runner.start(snapshot, application);
        try {
            app.process().info();
            assertTrue(await(output, "feature.mode=encrypted"));
            assertTrue(await(output, "encryption.present=true"));
            assertEquals("rebound-encrypted", app.launchId());
            assertTrue(Files.readString(capture).contains(
                    "ENCRYPTION_KEY=\"op://Shared Vault/rebound/local encryption-key\""));
            assertFalse(String.join("\n", output).contains("op://Shared Vault"));
            assertFalse(application.toString().contains("op://Shared Vault"));
        } finally {
            app.stop(Duration.ofSeconds(2));
        }
        Path secretDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("secrets");
        try (var files = Files.list(secretDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void reportsMissingOnePasswordCliWithoutRetainingReferenceFile(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, FixtureAppMain.class.getName(), "orders-app", null,
                false, false, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of());
        AppProcessRunner runner = new AppProcessRunner(
                config, "ws://localhost:1234", "http://localhost:5678", "http://localhost:5678",
                "session-missing-op", (applicationName, instanceId, stream, line) -> {
                }, new OnePasswordEnvironment(projectDirectory, projectDirectory.resolve("missing-op").toString()));
        Path classes = testClassesDirectory();
        ApplicationBuild application = new ApplicationBuild(
                "orders-app", ".", FixtureAppMain.class.getName(), List.of(classes), List.of(), false,
                "orders-secure", Map.of(), Map.of("API_TOKEN", "op://Shared/orders/token"));
        BuildSnapshot snapshot = new BuildSnapshot(
                1, projectDirectory.resolve("build"), classes, List.of(), Instant.now(), CompileTiming.unknown(),
                List.of(application));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> runner.start(snapshot, application));
        assertTrue(exception.getMessage().contains("configuration orders-secure"));
        assertTrue(exception.getMessage().contains("1Password CLI"));
        assertFalse(exception.getMessage().contains("op://"));
        Path directory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("secrets");
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        }
    }

    private static Path testClassesDirectory() throws Exception {
        return Path.of(AppProcessRunnerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static boolean await(List<String> output, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (output.stream().anyMatch(line -> line.contains(expected))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
