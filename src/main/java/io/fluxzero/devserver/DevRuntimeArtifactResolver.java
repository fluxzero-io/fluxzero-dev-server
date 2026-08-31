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

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DevRuntimeArtifactResolver {
    static final String CACHE_FORMAT = "# fluxzero-dev-runtime-classpath-v1";
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final List<String> RUNTIME_ARTIFACTS = List.of("test-server", "proxy");
    private static final ConcurrentHashMap<Path, Object> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    private final Path cacheRoot;
    private final Path localRepository;

    DevRuntimeArtifactResolver() {
        this(defaultCacheRoot(), defaultLocalRepository());
    }

    DevRuntimeArtifactResolver(Path cacheRoot, Path localRepository) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.localRepository = localRepository.toAbsolutePath().normalize();
    }

    ResolvedRuntime resolve(String version) {
        Path versionDirectory = cacheRoot.resolve(safeVersion(version));
        Path classpathFile = versionDirectory.resolve("classpath.txt");
        Path lockFile = versionDirectory.resolve("resolve.lock");
        try {
            Files.createDirectories(versionDirectory);
            synchronized (IN_PROCESS_LOCKS.computeIfAbsent(lockFile, ignored -> new Object())) {
                try (FileChannel channel = FileChannel.open(
                        lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    List<Path> cached = readValidClasspath(classpathFile);
                    if (!cached.isEmpty()) {
                        return new ResolvedRuntime(version, cached, true, classpathFile);
                    }
                    List<Path> resolved = resolveArtifacts(version);
                    writeClasspath(classpathFile, resolved);
                    return new ResolvedRuntime(version, resolved, false, classpathFile);
                }
            }
        } catch (Exception e) {
            throw new DevServerStartupException(
                    "Could not resolve Fluxzero TestServer and Proxy " + version + ": " + oneLine(e.getMessage()), e);
        }
    }

    private List<Path> resolveArtifacts(String version) throws Exception {
        RepositorySystem system = new RepositorySystemSupplier().get();
        try (RepositorySystemSession.CloseableSession session = new SessionBuilderSupplier(system).get()
                .setIgnoreArtifactDescriptorRepositories(true)
                .withLocalRepositories(new LocalRepository(localRepository)).build()) {
            RemoteRepository central = new RemoteRepository.Builder("central", "default", CENTRAL).build();
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(List.of(central));
            RUNTIME_ARTIFACTS.forEach(artifactId -> collectRequest.addDependency(new Dependency(
                    new DefaultArtifact("io.fluxzero:" + artifactId + ":" + version), "runtime")));
            collectRequest.addDependency(new Dependency(new DefaultArtifact(
                    "ch.qos.logback:logback-classic:" + DevServerVersion.logbackVersion()), "runtime"));
            DependencyNode root = system.collectDependencies(session, collectRequest).getRoot();
            system.resolveDependencies(session, new DependencyRequest(root, null));
            Set<Path> classpath = new LinkedHashSet<>();
            collectClasspath(root, classpath);
            if (classpath.stream().noneMatch(path -> path.getFileName().toString().equals(
                    "test-server-" + version + ".jar"))
                || classpath.stream().noneMatch(path -> path.getFileName().toString().equals(
                    "proxy-" + version + ".jar"))) {
                throw new IllegalStateException("resolved classpath is missing the requested runtime artifacts");
            }
            return List.copyOf(classpath);
        } finally {
            system.shutdown();
        }
    }

    private static void collectClasspath(DependencyNode node, Set<Path> classpath) {
        if (node.getArtifact() != null && node.getArtifact().getPath() != null) {
            classpath.add(node.getArtifact().getPath().toAbsolutePath().normalize());
        }
        node.getChildren().forEach(child -> collectClasspath(child, classpath));
    }

    private static List<Path> readValidClasspath(Path classpathFile) {
        if (!Files.isRegularFile(classpathFile)) {
            return List.of();
        }
        try {
            List<Path> entries = new ArrayList<>();
            List<String> lines = Files.readAllLines(classpathFile, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !CACHE_FORMAT.equals(lines.getFirst())) {
                return List.of();
            }
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    Path path = Path.of(line).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(path)) {
                        return List.of();
                    }
                    entries.add(path);
                }
            }
            return List.copyOf(entries);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static void writeClasspath(Path classpathFile, List<Path> classpath) throws IOException {
        Path temporary = classpathFile.resolveSibling(
                classpathFile.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        Files.writeString(temporary, CACHE_FORMAT + System.lineSeparator() + String.join(
                                  System.lineSeparator(), classpath.stream().map(Path::toString).toList())
                                  + System.lineSeparator(),
                          StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            AtomicFileUtils.replace(temporary, classpathFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path defaultCacheRoot() {
        String configured = System.getProperty("fluxzero.dev.runtime.cache");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".fluxzero", "cache", "dev-runtime")
                : Path.of(configured);
    }

    private static Path defaultLocalRepository() {
        String configured = System.getProperty("maven.repo.local");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".m2", "repository") : Path.of(configured);
    }

    private static String safeVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9._+-]+")) {
            throw new IllegalArgumentException("Invalid Fluxzero SDK version: " + version);
        }
        return version;
    }

    private static String oneLine(String value) {
        return value == null || value.isBlank() ? "unknown artifact resolution failure"
                : value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    record ResolvedRuntime(String version, List<Path> classpath, boolean cached, Path classpathFile) {
        ResolvedRuntime {
            classpath = List.copyOf(classpath);
        }
    }
}
