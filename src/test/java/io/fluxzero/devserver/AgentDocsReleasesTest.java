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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.devserver.AgentDocsFixture.*;
import static io.fluxzero.devserver.AgentDocsStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDocsReleasesTest {
    @Test
    void selectsStableVersionsNumericallyAndRejectsExternalEntities() throws Exception {
        assertEquals("1.10.0", AgentDocsReleases.newest(("<metadata><versioning><versions>"
                + "<version>1.9.9</version><version>2.0.0-SNAPSHOT</version><version>2.0.0-rc1</version>"
                + "<version>1.10.0</version></versions></versioning></metadata>").getBytes()));
        assertThrows(IOException.class, () -> AgentDocsReleases.newest(
                "<!DOCTYPE metadata [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><metadata>&e;</metadata>".getBytes()));
        assertThrows(IOException.class, () -> AgentDocsReleases.newest("<metadata><release>latest</release></metadata>".getBytes()));
    }

    @Test
    void cachesResolutionRefreshesAndReportsOfflineMetadata(@TempDir Path directory) throws Exception {
        URI metadata = URI.create("https://example.invalid/sdk/maven-metadata.xml");
        AtomicInteger calls = new AtomicInteger();
        AgentDocsStore.Fetcher fetcher = (uri, limit) -> {
            assertEquals(metadata, uri);
            calls.incrementAndGet();
            return "<metadata><versioning><release>1.2.3</release></versioning></metadata>".getBytes();
        };
        assertEquals("repository", AgentDocsReleases.resolve(directory, metadata, fetcher).source());
        assertEquals("cache", AgentDocsReleases.resolve(directory, metadata, fetcher).source());
        assertEquals(1, calls.get());
        Path file = directory.resolve("latest.json");
        var cached = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(file.toFile());
        cached.put("checkedAt", Instant.now().minusSeconds(7200).toString());
        Files.write(file, JSON.writeValueAsBytes(cached));
        var offline = AgentDocsReleases.resolve(directory, metadata, offline());
        assertEquals("offline", offline.source());
        assertEquals("1.2.3", offline.version());
        assertEquals("repository", AgentDocsReleases.resolve(directory, metadata, fetcher).source());
        assertEquals(2, calls.get());
        assertThrows(IllegalStateException.class, () -> AgentDocsReleases.resolve(directory,
                URI.create("https://other.invalid/maven-metadata.xml"), offline()));
    }

    @Test
    void latestIsOnlyUsedWithoutKnownProjectVersion(@TempDir Path directory) throws Exception {
        byte[] archive = archive("sdk", "1.2.3");
        var source = source(archive, new AtomicInteger());
        AtomicInteger metadata = new AtomicInteger();
        try (var service = new AgentDocsService(() -> DevServerConfig.defaults(directory),
                store(directory.resolve("cache"), Map.of(), (uri, limit) -> {
                    if (uri.toString().endsWith("maven-metadata.xml")) {
                        metadata.incrementAndGet();
                        return "<metadata><release>1.2.3</release></metadata>".getBytes();
                    }
                    return source.read(uri, limit);
                }))) {
            var start = JSON.valueToTree(service.start(Map.of()));
            assertEquals("latest-release", start.path("selection").asText());
            assertEquals("1.2.3", start.at("/root/version").asText());
            pom(directory, "1.2.4");
            assertThrows(IllegalArgumentException.class, () -> service.start(Map.of()));
            assertEquals(1, metadata.get());
            assertEquals("explicit-version", JSON.valueToTree(service.start(Map.of("version", "1.2.3")))
                    .path("selection").asText());
        }
    }
}
