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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

record TestInputSnapshot(int version, Map<String, String> files) {
    private static final int CURRENT_VERSION = 1;

    TestInputSnapshot {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    static TestInputSnapshot capture(Path projectDirectory) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        Map<String, String> hashes = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return !directory.equals(root) && ignored(root.relativize(directory))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path relative = root.relativize(file);
                    if (attributes.isRegularFile() && relevant(relative)) {
                        String hash = hash(file);
                        if (hash != null) {
                            hashes.put(normalize(relative), hash);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to capture test inputs in " + root, e);
        }
        return new TestInputSnapshot(CURRENT_VERSION, hashes);
    }

    boolean compatible() {
        return version == CURRENT_VERSION;
    }

    Set<Path> changesSince(TestInputSnapshot previous, Path projectDirectory) {
        if (!previous.compatible()) {
            return Set.of(projectDirectory.resolve("pom.xml"));
        }
        Set<String> paths = new LinkedHashSet<>(previous.files.keySet());
        paths.addAll(files.keySet());
        Set<Path> changes = new LinkedHashSet<>();
        paths.stream().sorted()
                .filter(path -> !java.util.Objects.equals(previous.files.get(path), files.get(path)))
                .map(projectDirectory::resolve)
                .forEach(changes::add);
        return Set.copyOf(changes);
    }

    private static boolean relevant(Path relative) {
        String path = normalize(relative);
        if (path.isBlank() || ignored(relative)) {
            return false;
        }
        String name = relative.getFileName().toString();
        return path.equals(DevProjectConfig.FILE.toString().replace('\\', '/'))
               || name.equals("pom.xml")
               || name.equals("build.gradle")
               || name.equals("build.gradle.kts")
               || name.equals("settings.gradle")
               || name.equals("settings.gradle.kts")
               || name.equals("gradle.properties")
               || path.endsWith("gradle/libs.versions.toml")
               || path.startsWith("src/main/")
               || path.startsWith("src/test/")
               || path.contains("/src/main/")
               || path.contains("/src/test/");
    }

    private static boolean ignored(Path relative) {
        String surrounded = "/" + normalize(relative) + "/";
        return surrounded.contains("/target/")
               || surrounded.contains("/.git/")
               || surrounded.contains("/.fluxzero/dev/")
               || surrounded.contains("/.angular/")
               || surrounded.contains("/node_modules/")
               || surrounded.contains("/dist/")
               || surrounded.contains("/build/");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String hash(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash test input " + path, e);
        }
    }
}
