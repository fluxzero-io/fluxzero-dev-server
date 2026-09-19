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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringStorageTest {
    @Test void onlyClearsOwnedStorage(@TempDir Path project) throws Exception {
        Path data = project.resolve(".fluxzero/dev/monitoring/victorialogs/partitions/day");
        Files.createDirectories(data); Files.writeString(data.resolve("records"), "audit data");
        Files.writeString(project.resolve("application-data"), "keep");
        MonitoringStorage.clear(project);
        assertFalse(Files.exists(data));
        assertEquals("keep", Files.readString(project.resolve("application-data")));
        MonitoringStorage.clear(project);
    }
    @Test @DisabledOnOs(OS.WINDOWS) void refusesLinkedStorageParentsAndDoesNotFollowNestedLinks(@TempDir Path project) throws Exception {
        Path external = Files.createDirectory(project.resolve("external"));
        Files.writeString(external.resolve("keep"), "keep");
        Files.createSymbolicLink(project.resolve(".fluxzero"), external);
        assertThrows(IOException.class, () -> MonitoringStorage.clear(project));
        Files.delete(project.resolve(".fluxzero"));
        Path data = Files.createDirectories(project.resolve(".fluxzero/dev/monitoring/victorialogs"));
        Files.createSymbolicLink(data.resolve("link"), external);
        MonitoringStorage.clear(project);
        assertEquals("keep", Files.readString(external.resolve("keep")));
    }
}
