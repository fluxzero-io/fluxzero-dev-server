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

import io.fluxzero.devserver.fixture.BffConfigurationMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ManagedBffLauncherTest {
    @TempDir Path project;

    @Test void retainsExplicitApplicationPropertyAndDoesNotGenerateMaterial() throws Exception {
        Path ownKey = project.resolve("own-session.jwks");
        Files.writeString(ownKey, "existing application key");
        Path classes = classes("fluxzero.auth.session-key-file=" + propertyPath(ownKey));
        assertTrue(launch(classes, Map.of(), IdpMode.MANAGED).contains("session.file=" + ownKey));
        assertEquals("existing application key", Files.readString(ownKey));
        assertFalse(Files.exists(keyDirectory()));
    }

    @Test void environmentPropertiesAndSubstitutionTakePrecedenceOverBaseProperties() throws Exception {
        Path classes = classes("fluxzero.auth.session-key-file=base-key");
        Files.writeString(classes.resolve("application-local.properties"),
                          "fluxzero.auth.session-key-file=${LOCAL_KEY_PATH}\n");
        assertTrue(launch(classes, Map.of("LOCAL_KEY_PATH", "local-key"), IdpMode.MANAGED)
                           .contains("session.file=local-key"));
        assertFalse(Files.exists(keyDirectory()));
    }

    @Test void additionalConfigurationAndEnvironmentOverrideAreResolvedByAppSdk() throws Exception {
        Path classes = classes("fluxzero.auth.session-key-file=base-key");
        Path extra = project.resolve("extra.properties");
        Files.writeString(extra, "fluxzero.auth.session-key-file=additional-key\n");
        assertTrue(launch(classes, Map.of("FLUXZERO_CONFIG_LOCATIONS", extra.toString()), IdpMode.MANAGED)
                           .contains("session.file=additional-key"));
        assertTrue(launch(classes, Map.of("FLUXZERO_CONFIG_LOCATIONS", extra.toString(),
                                         "FLUXZERO_AUTH_SESSION_KEY_FILE", "environment-key"), IdpMode.MANAGED)
                           .contains("session.file=environment-key"));
        assertFalse(Files.exists(keyDirectory()));
    }

    @Test void propertyOptOutCreatesNoKeyAndEnvironmentCanReenableDefault() throws Exception {
        Path classes = classes("fluxzero.auth.oidc.disabled=true");
        var output = launch(classes, Map.of(), IdpMode.MANAGED);
        assertTrue(output.contains("oidc.disabled=true"));
        assertTrue(output.contains("session.file=null"));
        assertFalse(Files.exists(keyDirectory()));
        output = launch(classes, Map.of("FLUXZERO_AUTH_OIDC_DISABLED", "false"), IdpMode.MANAGED);
        assertTrue(output.contains("session.file=" + managedKey()));
        assertTrue(Files.isRegularFile(managedKey()));
    }

    @Test void environmentOptOutCreatesNoKey() throws Exception {
        assertTrue(launch(classes(""), Map.of("FLUXZERO_AUTH_OIDC_DISABLED", "true"), IdpMode.MANAGED)
                           .contains("session.file=null"));
        assertFalse(Files.exists(keyDirectory()));
    }

    @Test void absentConfigurationCreatesKeyOnlyInChildAndReusesItOnRestart() throws Exception {
        Path classes = classes("");
        assertFalse(Files.exists(keyDirectory()));
        var first = launch(classes, Map.of(), IdpMode.MANAGED);
        assertTrue(first.contains("session.file=" + managedKey()));
        assertTrue(first.contains("args=--probe|argument with spaces"));
        byte[] original = Files.readAllBytes(managedKey());
        assertTrue(launch(classes, Map.of(), IdpMode.MANAGED).contains("session.file=" + managedKey()));
        assertArrayEquals(original, Files.readAllBytes(managedKey()));
    }

    @Test void concurrentChildStartsShareOneKey() throws Exception {
        Path classes = classes("");
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var starts = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                assertTrue(launch(classes, Map.of(), IdpMode.MANAGED).contains("session.file=" + managedKey()));
                return Files.readAllBytes(managedKey());
            })).toList();
            byte[] first = starts.getFirst().get();
            for (var start : starts) assertArrayEquals(first, start.get());
        }
    }

    @Test void externalIdpDoesNotInstallBootstrapOrGenerateKey() throws Exception {
        assertTrue(launch(classes(""), Map.of(), IdpMode.EXTERNAL).contains("session.file=null"));
        assertFalse(Files.exists(keyDirectory()));
        assertFalse(Files.exists(project.resolve(DevSessionStore.DEV_DIRECTORY).resolve("launchers")));
    }

    private Path classes(String properties) throws Exception {
        Path classes = project.resolve("classes");
        String main = BffConfigurationMain.class.getName().replace('.', '/') + ".class";
        Files.createDirectories(classes.resolve(main).getParent());
        try (var input = BffConfigurationMain.class.getResourceAsStream("/" + main)) {
            Files.copy(input, classes.resolve(main));
        }
        Path marker = classes.resolve("io/fluxzero/idp/bff/OidcIntegration.class");
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[0]);
        Files.writeString(classes.resolve("application.properties"), properties + "\n");
        return classes;
    }

    private List<String> launch(Path classes, Map<String, String> environment, IdpMode mode) throws Exception {
        // No devserver classes/resources in the child: only the app fixture, its dependencies, and the tiny launcher.
        List<Path> dependencies = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).filter(Files::isRegularFile).toList();
        var application = new ApplicationBuild("orders", ".", BffConfigurationMain.class.getName(),
                                               List.of(classes), dependencies, true, "orders", environment, Map.of());
        var config = new DevServerConfig(project, null, "orders", null, false, false, false,
                                         Duration.ofSeconds(2), Duration.ofSeconds(2), DevServerConfig.DEFAULT_DEBOUNCE,
                                         FrontendConfig.none(), List.of("--probe", "argument with spaces"), false,
                                         "local", List.of(), 0, mode);
        List<String> output = new CopyOnWriteArrayList<>();
        var runner = new AppProcessRunner(config, "ws://localhost:1234", "http://localhost:5678", "session",
                                         (name, instance, stream, line) -> output.add(line));
        var snapshot = new BuildSnapshot(1, project.resolve("build"), classes, dependencies, Instant.now());
        var app = runner.start(snapshot, application);
        try {
            assertTrue(app.process().waitFor(10, TimeUnit.SECONDS), "Child did not terminate: " + output);
            assertEquals(0, app.process().exitValue(), () -> String.join("\n", output));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!output.contains("BFF_CONFIGURATION_READY") && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(output.contains("BFF_CONFIGURATION_READY"), () -> String.join("\n", output));
            return output;
        } finally { app.stop(Duration.ofSeconds(2)); }
    }

    private Path keyDirectory() { return project.resolve(DevSessionStore.DEV_DIRECTORY).resolve("oidc"); }

    private Path managedKey() {
        return ManagedBffSupport.sessionKeyPath(project, AppProcessRunner.applicationId("orders", Map.of(), System.getenv()));
    }

    private static String propertyPath(Path path) { return path.toString().replace("\\", "\\\\"); }
}
