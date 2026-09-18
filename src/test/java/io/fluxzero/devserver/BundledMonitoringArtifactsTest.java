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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BundledMonitoringArtifactsTest {
    @TempDir Path cache;
    private final Map<String, String> files = Map.of("auditlog.jar", "backend", "ui/index.html", "dashboard");

    @Test void extractsWithoutCheckoutReusesCacheAndRepairsCorruption() throws Exception {
        byte[] zip = zip(files);
        var manifest = manifest(zip, files);
        AtomicInteger reads = new AtomicInteger();
        BundledMonitoringArtifacts.ArchiveSource source = () -> {
            reads.incrementAndGet();
            return new ByteArrayInputStream(zip);
        };
        var artifacts = BundledMonitoringArtifacts.extract(cache, manifest, source);
        assertEquals("backend", Files.readString(artifacts.jar()));
        assertEquals("dashboard", Files.readString(artifacts.ui().resolve("index.html")));
        assertEquals(artifacts, BundledMonitoringArtifacts.extract(cache, manifest, source));
        assertEquals(1, reads.get());
        Files.writeString(artifacts.jar(), "corrupt");
        BundledMonitoringArtifacts.extract(cache, manifest, source);
        assertEquals(2, reads.get());
        assertEquals("backend", Files.readString(artifacts.jar()));
    }

    @Test void refusesIncorrectArchiveChecksumAndCleansStaging() throws Exception {
        byte[] zip = zip(files);
        assertThrows(IOException.class, () -> BundledMonitoringArtifacts.extract(cache, manifest(zip, files),
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3})));
        try (var remaining = Files.list(cache)) {
            assertTrue(remaining.allMatch(path -> path.toString().endsWith(".lock")));
        }
    }

    @Test void refusesMissingFilesAndIncorrectFileChecksums() throws Exception {
        for (var contents : java.util.List.of(Map.of("auditlog.jar", "backend"),
                Map.of("auditlog.jar", "altered", "ui/index.html", "dashboard"))) {
            byte[] zip = zip(contents);
            assertThrows(IOException.class, () -> BundledMonitoringArtifacts.extract(cache, manifest(zip, files),
                    () -> new ByteArrayInputStream(zip)));
        }
    }

    @Test void refusesTraversalEvenInAnOtherwiseChecksummedArchive() throws Exception {
        byte[] zip = zip(Map.of("../escaped", "escape", "auditlog.jar", "backend", "ui/index.html", "dashboard"));
        assertThrows(IOException.class, () -> BundledMonitoringArtifacts.extract(cache, manifest(zip, files),
                () -> new ByteArrayInputStream(zip)));
        assertFalse(Files.exists(cache.resolve("escaped")));
    }

    @Test void optOutAndExplicitOverridesNeverResolveTheBundle() {
        var disabled = new DevMonitoringConfig(null, null, null, null, null, null, null, null, null, false);
        assertSame(disabled, disabled.withBundledArtifacts());
        var custom = new DevMonitoringConfig("a.jar", "ui", null, null, null, null, null, null, null);
        assertSame(custom, custom.withBundledArtifacts());
    }

    @Test void distributionContainsCompatibleBackendAndUi() throws Exception {
        String previous = System.getProperty(BundledMonitoringArtifacts.CACHE_PROPERTY);
        try {
            System.setProperty(BundledMonitoringArtifacts.CACHE_PROPERTY, cache.toString());
            var config = DevMonitoringConfig.defaults().withBundledArtifacts();
            assertTrue(Files.size(Path.of(config.auditlogJar())) > 1_000_000);
            assertTrue(Files.readString(Path.of(config.uiDirectory(), "index.html")).contains("<html"));
        } finally {
            if (previous == null) System.clearProperty(BundledMonitoringArtifacts.CACHE_PROPERTY);
            else System.setProperty(BundledMonitoringArtifacts.CACHE_PROPERTY, previous);
        }
    }

    private static BundledMonitoringArtifacts.Manifest manifest(byte[] zip, Map<String, String> expected) throws Exception {
        var checksums = new java.util.HashMap<String, String>();
        for (var file : expected.entrySet()) checksums.put(file.getKey(), sha(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new BundledMonitoringArtifacts.Manifest("a".repeat(40), sha(zip), checksums);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
