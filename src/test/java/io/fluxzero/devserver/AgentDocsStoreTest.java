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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.devserver.AgentDocsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDocsStoreTest {
    static final Map<String, String> COORDINATES = Map.of("sdk", "{version}/docs.zip", "cli", "cli/{version}/docs.zip");

    static AgentDocsStore store(Path cache, Map<String, Path> local, AgentDocsStore.Fetcher fetcher) {
        return new AgentDocsStore(cache, URI.create("https://example.invalid/"), local, COORDINATES, fetcher);
    }

    static AgentDocsStore.Fetcher offline() {
        return (uri, limit) -> { throw new IOException("offline"); };
    }

    static AgentDocsStore.Fetcher source(byte[] bytes, AtomicInteger calls) throws Exception {
        byte[] checksum = hash(bytes).getBytes(StandardCharsets.US_ASCII);
        return (uri, limit) -> {
            calls.incrementAndGet();
            return uri.toString().endsWith(".sha256") ? checksum : bytes;
        };
    }

    @Test
    void reusesSharedCacheOfflineAndRepairsCorruption(@TempDir Path cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] bytes = archive("sdk", "1.2.3");
        try (var store = store(cache, Map.of(), source(bytes, calls))) {
            assertEquals("downloaded", store.load(SDK).source());
            assertEquals("cache", store.load(SDK).source());
            assertEquals(2, calls.get());
        }
        try (var store = store(cache, Map.of(), offline())) {
            assertEquals("cache", store.load(SDK).source());
            assertThrows(IllegalStateException.class, () -> store.load(new AgentDocsGraph.Identity("sdk", "1.2.4")));
        }
        Files.writeString(cache.resolve("sdk/1.2.3/agent-docs.zip"), "corrupt");
        try (var store = store(cache, Map.of(), source(bytes, calls))) {
            assertEquals("downloaded", store.load(SDK).source());
            assertArrayEquals(bytes, Files.readAllBytes(cache.resolve("sdk/1.2.3/agent-docs.zip")));
            assertEquals(4, calls.get());
        }
    }

    @Test
    void neverCachesMismatchedChecksumOrVersion(@TempDir Path cache) throws Exception {
        byte[] bytes = archive("sdk", "1.2.3");
        try (var store = store(cache, Map.of(), (uri, limit) -> uri.toString().endsWith(".sha256")
                ? "0".repeat(64).getBytes() : bytes)) {
            assertThrows(IllegalStateException.class, () -> store.load(SDK));
        }
        assertFalse(Files.exists(cache.resolve("sdk/1.2.3/agent-docs.zip")));
        try (var store = store(cache, Map.of(), source(bytes, new AtomicInteger()))) {
            assertThrows(IllegalArgumentException.class, () -> store.load(new AgentDocsGraph.Identity("sdk", "1.2.4")));
        }
        assertFalse(Files.exists(cache.resolve("sdk/1.2.4/agent-docs.zip")));
    }

    @Test
    void coalescesDownloadsAcrossStoreInstances(@TempDir Path cache) throws Exception {
        CountDownLatch downloading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var source = source(archive("sdk", "1.2.3"), calls);
        AgentDocsStore.Fetcher gated = (uri, limit) -> {
            downloading.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) { throw new IOException("test did not release download"); }
            return source.read(uri, limit);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var first = store(cache, Map.of(), gated); var second = store(cache, Map.of(), gated)) {
            var a = executor.submit(() -> first.load(SDK));
            assertTrue(downloading.await(3, TimeUnit.SECONDS));
            var b = executor.submit(() -> second.load(SDK));
            release.countDown();
            assertEquals(a.get(3, TimeUnit.SECONDS).graph().contentHash(), b.get(3, TimeUnit.SECONDS).graph().contentHash());
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void isolatesNamespacesAndRefreshesExplicitLocalSnapshots(@TempDir Path directory) throws Exception {
        Path sdk = directory.resolve("sdk.zip"), cli = directory.resolve("cli.zip");
        Files.write(sdk, archive("sdk", "0-SNAPSHOT"));
        Files.write(cli, archive("cli", "0-SNAPSHOT"));
        var snapshot = new AgentDocsGraph.Identity("sdk", "0-SNAPSHOT");
        Path cache = directory.resolve("cache");
        try (var store = store(cache, Map.of("sdk", sdk, "cli", cli), offline())) {
            assertEquals("sdk", store.load(snapshot).graph().identity().namespace());
            assertEquals("cli", store.load(new AgentDocsGraph.Identity("cli", "0-SNAPSHOT")).graph().identity().namespace());
            var files = files("sdk", "0-SNAPSHOT");
            var release = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(files.get("release.json"));
            release.put("sourceCommit", "1".repeat(40));
            files.put("release.json", JSON.writeValueAsBytes(release));
            Files.write(sdk, zip(files));
            assertEquals("1".repeat(40), store.load(snapshot).graph().sourceCommit());
            assertThrows(IllegalArgumentException.class, () -> store.load(SDK));
        }
        assertTrue(Files.isRegularFile(cache.resolve("sdk/0-SNAPSHOT/agent-docs.zip")));
        assertTrue(Files.isRegularFile(cache.resolve("cli/0-SNAPSHOT/agent-docs.zip")));
    }
}
