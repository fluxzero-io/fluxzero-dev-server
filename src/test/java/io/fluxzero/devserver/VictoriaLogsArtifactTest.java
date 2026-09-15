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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class VictoriaLogsArtifactTest {
    @TempDir Path directory;

    @Test void selectsWindowsZipAndUnixTarArchive() {
        assertEquals("windows-amd64", VictoriaLogsArtifact.platform("Windows 11", "AMD64"));
        assertEquals("victoria-logs-windows-amd64-v1.52.0.zip", VictoriaLogsArtifact.archiveName("windows-amd64"));
        assertEquals("victoria-logs-linux-arm64-v1.52.0.tar.gz", VictoriaLogsArtifact.archiveName("linux-arm64"));
    }

    @Test void extractsOnlyTheExpectedExecutableIntoAPathWithSpaces() throws Exception {
        Path archive = directory.resolve("download.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../unexpected.exe"));
            zip.write(new byte[]{1}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("victoria-logs-windows-amd64-prod.exe"));
            zip.write(new byte[]{'M', 'Z', 1, 2}); zip.closeEntry();
        }
        Path destination = Files.createDirectories(directory.resolve("Program Files")).resolve("victoria-logs-prod.exe");
        VictoriaLogsArtifact.extract(archive, destination, "windows-amd64");
        assertArrayEquals(new byte[]{'M', 'Z', 1, 2}, Files.readAllBytes(destination));
        assertFalse(Files.exists(directory.resolve("unexpected.exe")));
    }

    @Test void rejectsAnArchiveWithoutTheExpectedWindowsExecutable() throws Exception {
        Path archive = directory.resolve("download.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("victoria-logs-prod"));
            zip.write(new byte[]{1}); zip.closeEntry();
        }
        Path destination = directory.resolve("victoria-logs-prod.exe");
        assertThrows(IOException.class, () -> VictoriaLogsArtifact.extract(archive, destination, "windows-amd64"));
        assertFalse(Files.exists(destination));
    }
}
