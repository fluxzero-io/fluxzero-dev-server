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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Extracts the release's pinned Auditlog backend and UI; no user checkout or runtime build is needed. */
final class BundledMonitoringArtifacts {
    static final String CACHE_PROPERTY = "fluxzero.dev.monitoring.cache";
    private static final String RESOURCE = "/dev-monitoring/auditlog.zip";
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Artifacts(Path jar, Path ui) {}
    record Manifest(String sourceCommit, String sha256, Map<String, String> files) {}

    private BundledMonitoringArtifacts() {}

    static Artifacts resolve() {
        Path cache = Path.of(System.getProperty(CACHE_PROPERTY,
                Path.of(System.getProperty("user.home"), ".fluxzero", "cache", "dev-monitoring").toString()));
        try (InputStream manifest = resource("/dev-monitoring/manifest.json")) {
            return extract(cache, MAPPER.readValue(manifest, Manifest.class), () -> resource(RESOURCE));
        } catch (Exception e) {
            throw new DevServerStartupException("Cannot prepare bundled Auditlog: " + e.getMessage()
                    + ". Install a complete Dev Server distribution or set monitoring.enabled: false to opt out.", e);
        }
    }

    // The JVM monitor complements the file lock: FileChannel otherwise rejects overlapping locks in one JVM.
    static synchronized Artifacts extract(Path cache, Manifest manifest, ArchiveSource archive) throws IOException {
        validate(manifest);
        cache = cache.toAbsolutePath().normalize();
        Files.createDirectories(cache);
        Path destination = cache.resolve(manifest.sha256());
        try (var channel = FileChannel.open(cache.resolve(manifest.sha256() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            if (!valid(destination, manifest)) {
                Path zip = Files.createTempFile(cache, "auditlog-", ".zip");
                Path staging = Files.createTempDirectory(cache, "unpack-");
                try {
                    try (InputStream input = archive.open(); var output = Files.newOutputStream(zip)) {
                        copyBounded(input, output, MAX_BYTES);
                    }
                    if (!digest(zip).equals(manifest.sha256())) throw new IOException("Auditlog bundle checksum mismatch");
                    unpack(zip, staging, manifest);
                    if (!valid(staging, manifest)) throw new IOException("Auditlog bundle is incomplete or corrupt");
                    remove(destination);
                    try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(staging, destination); }
                } finally {
                    Files.deleteIfExists(zip);
                    remove(staging);
                }
            }
        }
        return new Artifacts(destination.resolve("auditlog.jar"), destination.resolve("ui"));
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = BundledMonitoringArtifacts.class.getResourceAsStream(name);
        if (input == null) throw new IOException("Missing distribution resource " + name);
        return input;
    }

    private static void validate(Manifest manifest) throws IOException {
        if (manifest == null || manifest.sha256() == null || !manifest.sha256().matches("[a-f0-9]{64}")
                || manifest.sourceCommit() == null || !manifest.sourceCommit().matches("[a-f0-9]{40}")
                || manifest.files() == null || manifest.files().size() > 10000
                || !manifest.files().containsKey("auditlog.jar") || !manifest.files().containsKey("ui/index.html")) {
            throw new IOException("Invalid Auditlog bundle manifest");
        }
        for (var file : manifest.files().entrySet()) {
            safePath(Path.of("bundle"), file.getKey());
            if (file.getValue() == null || !file.getValue().matches("[a-f0-9]{64}")) {
                throw new IOException("Invalid Auditlog file checksum");
            }
        }
    }

    private static void unpack(Path zip, Path root, Manifest manifest) throws IOException {
        long remaining = MAX_BYTES;
        var seen = new java.util.HashSet<String>();
        int entries = 0;
        try (var input = new ZipInputStream(Files.newInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (++entries > 20000) throw new IOException("Too many Auditlog bundle entries");
                Path output = safePath(root, entry.getName());
                if (entry.isDirectory()) continue;
                if (!manifest.files().containsKey(entry.getName()) || !seen.add(entry.getName())) {
                    throw new IOException("Unexpected or duplicate Auditlog entry: " + entry.getName());
                }
                Files.createDirectories(output.getParent());
                try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    remaining -= copyBounded(input, stream, remaining);
                }
            }
        }
    }

    private static Path safePath(Path root, String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")) {
            throw new IOException("Invalid Auditlog bundle path");
        }
        Path output = root.resolve(name).normalize();
        if (!output.startsWith(root) || output.equals(root)) throw new IOException("Auditlog path escapes its bundle");
        return output;
    }

    private static boolean valid(Path root, Manifest manifest) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
        for (var file : manifest.files().entrySet()) {
            Path path = safePath(root, file.getKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !digest(path).equals(file.getValue())) return false;
        }
        return true;
    }

    private static String digest(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static long copyBounded(InputStream input, java.io.OutputStream output, long limit) throws IOException {
        long size = 0;
        byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) != -1;) {
            size += count;
            if (size > limit) throw new IOException("Auditlog bundle exceeds its size limit");
            output.write(buffer, 0, count);
        }
        return size;
    }

    private static void remove(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @FunctionalInterface interface ArchiveSource { InputStream open() throws IOException; }
}
