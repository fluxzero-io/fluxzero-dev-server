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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TestServerArtifactResolverTest {
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
        var resolver = new TestServerArtifactResolver(directory.resolve("cache"), local, repositories);

        var first = resolver.resolve("1.999.0");

        assertFalse(first.cached());
        assertEquals(4, first.classpath().size());
        assertTrue(first.classpath().stream().allMatch(Files::isRegularFile));
        // Each repository publishes distinct bytes only for its intended inputs.
        // Verify the resolved content, not Maven's private tracking-file format.
        for (Path published : List.of(
                packages.resolve("io/fluxzero/test-server/1.999.0/test-server-1.999.0.jar"),
                packages.resolve("io/fluxzero/proxy/1.999.0/proxy-1.999.0.jar"),
                central.resolve("org/example/support/1.0/support-1.0.jar"),
                central.resolve("ch/qos/logback/logback-classic/" + DevServerVersion.logbackVersion()
                                + "/logback-classic-" + DevServerVersion.logbackVersion() + ".jar"))) {
            Path cached = first.classpath().stream()
                    .filter(path -> path.getFileName().equals(published.getFileName())).findFirst().orElseThrow();
            assertEquals(-1L, Files.mismatch(published, cached), published.getFileName().toString());
        }

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
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("test-origin.txt"));
            output.write((repository.getFileName() + ":" + group + ":" + artifact + ":" + version)
                                 .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        for (Path file : List.of(pom, jar)) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
            Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), hash);
        }
    }
}
