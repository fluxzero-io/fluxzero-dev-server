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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.devserver.AgentDocsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDocsHttpTest {
    @Test
    void downloadsExactCoordinatesThenWorksWithRepositoryStopped(@TempDir Path cache) throws Exception {
        byte[] bytes = archive("sdk", "1.2.3"), checksum = hash(bytes).getBytes();
        AtomicInteger calls = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String artifact = "/maven/io/fluxzero/fluxzero-sdk-java/1.2.3/fluxzero-sdk-java-1.2.3-agent-docs.zip";
        http.createContext("/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.equals(artifact + ".sha256") ? checksum : bytes;
            exchange.sendResponseHeaders(path.equals(artifact) || path.equals(artifact + ".sha256") ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        URI repository = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/maven/");
        try (var store = new AgentDocsStore(cache, repository, Map.of())) {
            assertEquals("downloaded", store.load(SDK).source());
            assertEquals(2, calls.get());
        } finally { http.stop(0); }
        try (var store = new AgentDocsStore(cache, repository, Map.of())) {
            assertEquals("cache", store.load(SDK).source());
            assertEquals(2, store.load(SDK).graph().size());
        }
    }

    @Test
    void rejectsOversizedHttpResponseAndCancelsStalledBodyOnClose(@TempDir Path cache) throws Exception {
        CountDownLatch streaming = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            http.setExecutor(executor);
            http.createContext("/oversized/", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(new byte[1024]);
                }
            });
            http.createContext("/stalled/", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('a');
                    exchange.getResponseBody().flush();
                    streaming.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            http.start();
            String root = "http://127.0.0.1:" + http.getAddress().getPort();
            try (var store = new AgentDocsStore(cache, URI.create(root + "/oversized/"), Map.of())) {
                assertThrows(IllegalStateException.class, () -> store.load(SDK));
            }
            try (var store = new AgentDocsStore(cache, URI.create(root + "/stalled/"), Map.of())) {
                var request = executor.submit(() -> store.load(SDK));
                assertTrue(streaming.await(3, TimeUnit.SECONDS));
                store.close();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                http.stop(0);
            }
        } finally { release.countDown(); http.stop(0); }
    }
}
