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

import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DevRuntimeArtifactResolverTest {
    @TempDir
    Path directory;

    @Test
    void resolvesFluxzeroFromPackagesAndTransitiveLibrariesFromCentralThenReusesCache() throws Exception {
        Path packages = directory.resolve("packages");
        Path central = directory.resolve("central");
        Path local = directory.resolve("local");
        String dependency = """
                <dependencies><dependency>
                  <groupId>org.example</groupId><artifactId>support</artifactId><version>1.0</version>
                </dependency></dependencies>
                """;
        publish(packages, "io.fluxzero", "test-server", "1.999.0", dependency);
        publish(packages, "io.fluxzero", "proxy", "1.999.0", "");
        publish(central, "org.example", "support", "1.0", "");
        publish(central, "ch.qos.logback", "logback-classic", DevServerVersion.logbackVersion(), "");
        var repositories = List.of(repository("fluxzero", packages), repository("central", central));
        var resolver = new DevRuntimeArtifactResolver(directory.resolve("cache"), local, repositories);

        var first = resolver.resolve("1.999.0");

        assertFalse(first.cached());
        assertEquals(4, first.classpath().size());
        assertTrue(first.classpath().stream().allMatch(Files::isRegularFile));
        assertTrue(Files.readString(local.resolve("io/fluxzero/test-server/1.999.0/_remote.repositories"))
                           .contains("test-server-1.999.0.jar>fluxzero="));
        assertTrue(Files.readString(local.resolve("org/example/support/1.0/_remote.repositories"))
                           .contains("support-1.0.jar>central="));

        // Both remotes are now unavailable. Cached starts must still work.
        Files.move(packages, directory.resolve("packages-offline"));
        Files.move(central, directory.resolve("central-offline"));
        var second = resolver.resolve("1.999.0");
        assertTrue(second.cached());
        assertEquals(first.classpath(), second.classpath());
    }

    private static RemoteRepository repository(String id, Path path) {
        return new RemoteRepository.Builder(id, "default", path.toUri().toString()).build();
    }

    private static void publish(Path repository, String group, String artifact, String version,
                                String dependencies) throws Exception {
        Path target = repository.resolve(group.replace('.', '/') + "/" + artifact + "/" + version);
        Files.createDirectories(target);
        Path pom = target.resolve(artifact + "-" + version + ".pom");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>
                """.formatted(group, artifact, version, dependencies));
        Path jar = target.resolve(artifact + "-" + version + ".jar");
        try (var ignored = new JarOutputStream(Files.newOutputStream(jar))) { }
        for (Path file : List.of(pom, jar)) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
            Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), hash);
        }
    }
}
