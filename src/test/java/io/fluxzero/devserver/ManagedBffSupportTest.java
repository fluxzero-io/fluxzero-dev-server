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

import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ManagedBffSupportTest {
    @TempDir Path project;

    @Test void retainsKeysAcrossReloadsAndSeparatesApplicationsAndProjects() throws Exception {
        Path first = sessionKey(project, "orders");
        byte[] bytes = Files.readAllBytes(first);
        assertArrayEquals(bytes, Files.readAllBytes(sessionKey(project, "orders")));
        var ring = JsonUtils.readTree(Files.readString(first));
        assertEquals(32, Base64.getUrlDecoder().decode(ring.path("keys").get(0).path("k").asText()).length);
        assertNotEquals(Files.readString(first), Files.readString(sessionKey(project, "billing")));
        assertNotEquals(Files.readString(first), Files.readString(sessionKey(project.resolve("other"), "orders")));
        if (Files.getFileStore(first).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(first));
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> Files.readAllBytes(sessionKey(project, "orders")))).toList();
            for (var task : tasks) assertArrayEquals(bytes, task.get());
        }
    }

    @Test void bffUsesApplicationIdentityAndLibraryCallbackButRetainsOtherDefaults() throws Exception {
        var app = application(true);
        var properties = ManagedBffSupport.properties(app, true, defaults(), Map.of("FLUXZERO_APPLICATION_ID", "explicit"), Map.of());
        assertEquals("explicit", properties.get("fluxzero.auth.oidc.client-id"));
        assertFalse(properties.containsKey("fluxzero.auth.oidc.redirect-uri"));
        assertEquals("none", properties.get("fluxzero.auth.oidc.token-endpoint-auth-method"));
        assertFalse(properties.containsKey("fluxzero.auth.session-key-file"));
        assertFalse(Files.exists(project.resolve(".fluxzero")));
        assertFalse(properties.values().stream().anyMatch(value -> value.contains("activeKid")));
    }

    @Test void existingConsumersKeepTheirOriginalDefaultsWithoutCreatingKeyFiles() throws Exception {
        assertEquals(defaults(), ManagedBffSupport.properties(application(false), false, defaults(), Map.of(), Map.of()));
        assertFalse(Files.exists(project.resolve(".fluxzero")));
    }

    @Test void explicitKeyAndOptOutDoNotCreateManagedMaterial() throws Exception {
        var app = application(true);
        for (var options : List.of(Map.of("FLUXZERO_AUTH_SESSION_KEY_FILE", "own-key"),
                                   Map.of("FLUXZERO_AUTH_OIDC_DISABLED", "true"))) {
            assertFalse(ManagedBffSupport.properties(app, true, defaults(), options, Map.of())
                                .containsKey("fluxzero.auth.session-key-file"));
            assertFalse(Files.exists(project.resolve(".fluxzero")));
        }
        assertFalse(ManagedBffSupport.properties(app, true, defaults(), Map.of(),
                Map.of("FLUXZERO_AUTH_SESSION_KEY_FILE", "inherited-key")).containsKey("fluxzero.auth.session-key-file"));
    }

    @Test void retainsDamagedExistingMaterialInsteadOfSilentlyReplacingIt() throws Exception {
        Path file = sessionKey(project, "orders");
        Files.writeString(file, "broken");
        assertEquals("broken", Files.readString(sessionKey(project, "orders")));
    }

    private static Path sessionKey(Path project, String id) throws Exception {
        return ManagedBffLauncher.sessionKey(ManagedBffSupport.sessionKeyPath(project, id));
    }

    @Test void bootstrapJarContainsOnlyLauncherClasses() throws Exception {
        Path jar = ManagedBffSupport.launcherJar(project);
        assertEquals(jar, ManagedBffSupport.launcherJar(project));
        try (var content = new java.util.jar.JarFile(jar.toFile())) {
            assertEquals(java.util.Set.of("io/fluxzero/devserver/ManagedBffLauncher.class",
                                         "io/fluxzero/devserver/TestApplicationLauncher.class"),
                         content.stream().map(java.util.jar.JarEntry::getName).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void detectsBffInClassesAndDependencyJars() throws Exception {
        assertTrue(ManagedBffSupport.containsBff(application(true)));
        assertFalse(ManagedBffSupport.containsBff(application(false)));
        Path jar = project.resolve("client-bff.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("io/fluxzero/idp/bff/OidcIntegration.class"));
            output.closeEntry();
        }
        assertTrue(ManagedBffSupport.containsBff(new ApplicationBuild(
                "orders", ".", "Main", List.of(), List.of(jar))));
    }

    private ApplicationBuild application(boolean bff) throws Exception {
        Path classes = project.resolve(bff ? "bff-classes" : "classes");
        Files.createDirectories(classes);
        if (bff) {
            Path marker = classes.resolve("io/fluxzero/idp/bff/OidcIntegration.class");
            Files.createDirectories(marker.getParent()); Files.write(marker, new byte[0]);
        }
        return new ApplicationBuild("orders", ".", "Main", List.of(classes), List.of());
    }

    private static Map<String, String> defaults() {
        return ManagedIdpService.devProperties("http://localhost:8080/_fluxzero", "http://localhost:8080");
    }
}
