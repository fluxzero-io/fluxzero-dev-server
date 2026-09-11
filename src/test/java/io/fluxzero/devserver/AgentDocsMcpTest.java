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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.devserver.AgentDocsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDocsMcpTest {
    @Test
    void concurrentResponsesDoNotBreakTheStdioSession(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("docs.zip");
        Files.write(archive, archive("sdk", "1.2.3"));
        String fragment = "# LocalOnly\nHandle locally 🦊.\n";
        var process = new ProcessBuilder(javaExecutable(),
                "-Dfluxzero.dev.docs.sdk.archive=" + archive,
                "-Dfluxzero.dev.docs.cacheDirectory=" + directory.resolve("cache"),
                "-cp", System.getProperty("java.class.path"), DevMcpStdioMain.class.getName(),
                "--project-dir", directory.toString()).redirectError(directory.resolve("stderr.log").toFile()).start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var reader = process.inputReader(StandardCharsets.UTF_8);
            var writer = process.outputWriter(StandardCharsets.UTF_8);
            writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 0, "method", "initialize", "params",
                    Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "concurrency-test", "version", "1")))) + "\n");
            writer.flush();
            assertTrue(executor.submit(reader::readLine).get(5, TimeUnit.SECONDS).contains("result"));
            writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n");
            // Concurrent response emission used to stop the stdio reader and strand later documentation calls.
            for (int round = 0; round < 5; round++) {
                int base = round * 64;
                var replies = executor.submit(() -> {
                    var ids = new HashSet<Integer>();
                    while (ids.size() < 64) {
                        var line = reader.readLine();
                        assertNotNull(line, "the bridge closed stdout before answering all concurrent calls");
                        var response = JSON.readTree(line);
                        assertTrue(response.has("result"), response::toString);
                        assertTrue(response.path("id").isIntegralNumber(), response::toString);
                        int id = response.path("id").asInt();
                        assertTrue(id > base && id <= base + 64, response::toString);
                        assertTrue(ids.add(id), "duplicate response id");
                        if (id % 4 == 0) {
                            assertTrue(response.at("/result/isError").asBoolean(), response::toString);
                            assertEquals("Unknown documentation article: /docs/missing-" + id,
                                    response.at("/result/content/0/text").asText());
                        } else {
                            assertFalse(response.at("/result/isError").asBoolean(), response::toString);
                            var result = JSON.readTree(response.at("/result/content/0/text").asText());
                            assertEquals("/docs/local", result.path("path").asText());
                            assertEquals(fragment.repeat(id), result.path("content").asText(), "response id " + id);
                            assertEquals(fragment.length() * id, result.path("nextOffset").asInt());
                        }
                    }
                    return ids;
                });
                for (int index = 1; index <= 64; index++) {
                    int id = base + index;
                    // Every success and tool error has a distinct payload, so swapped response IDs are detected.
                    writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                            "method", "tools/call", "params", Map.of("name", "docs_read", "arguments",
                                    Map.of("version", "1.2.3", "path", id % 4 == 0 ? "/docs/missing-" + id : "/docs/local",
                                            "maxChars", fragment.length() * id)))) + "\n");
                }
                writer.flush();
                var expected = new HashSet<Integer>();
                for (int index = 1; index <= 64; index++) expected.add(base + index);
                assertEquals(expected, replies.get(10, TimeUnit.SECONDS));
            }
            writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 321, "method", "tools/call",
                    "params", Map.of("name", "docs_read", "arguments",
                            Map.of("version", "1.2.3", "path", "/docs")))) + "\n");
            writer.flush();
            var response = JSON.readTree(executor.submit(reader::readLine).get(5, TimeUnit.SECONDS));
            assertEquals(321, response.path("id").asInt());
            assertTrue(response.has("result"), response::toString);
            assertFalse(response.at("/result/isError").asBoolean(), response::toString);
            var article = JSON.readTree(response.at("/result/content/0/text").asText());
            assertEquals("# Start\nUse selective documentation retrieval.\n", article.path("content").asText());
            assertFalse(Files.exists(directory.resolve(".fluxzero")), "documentation must not start a dev server");
            writer.close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "the busy bridge must still exit on EOF");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
            executor.close();
        }
    }

    @Test
    void servesDocsWithoutAProjectThenConnectsAndReconnectsWithoutChangingTools(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Files.writeString(root.resolve("brief.md"), "A nonempty workspace without a build");
        Path archive = directory.resolve("docs.zip");
        Files.write(archive, archive("sdk", "1.2.3"));
        var changed = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(1));
        try (var client = stdioClient(root, directory.resolve("cache"), () -> changed.get().countDown(),
                "-Dfluxzero.dev.docs.sdk.archive=" + archive)) {
            client.initialize();
            var tools = client.listTools().tools();
            assertEquals(11, tools.size());
            assertFalse(tools.stream().filter(t -> t.name().equals("start_dev")).findFirst().orElseThrow().annotations().readOnlyHint());
            assertTrue(DevMcpStdioMain.INSTRUCTIONS.length() <= 512);
            var status = call(client, "get_status", Map.of());
            assertEquals("dev-server-not-running", status.path("status").asText());
            assertEquals(root.toString(), status.path("projectDirectory").asText());
            assertEquals("start_dev", status.at("/start/tool").asText());
            assertTrue(client.callTool(new McpSchema.CallToolRequest("get_logs", Map.of())).isError());
            assertTrue(client.callTool(new McpSchema.CallToolRequest("start_dev", Map.of("projectDirectory", "/"))).isError());
            var start = call(client, "docs_start", Map.of("version", "1.2.3"));
            assertEquals("dev-server-not-running", start.at("/development/status").asText());
            assertEquals("ready", start.path("status").asText());
            assertFalse(Files.exists(root.resolve(".fluxzero")), "documentation must not create a project session");
            pom(root, "1.2.3");
            assertEquals("project-sdk", call(client, "docs_start", Map.of()).path("selection").asText());
            assertEquals("/docs/local", call(client, "docs_search", Map.of("query", "local")).at("/results/0/path").asText());
            assertEquals("1.2.3", call(client, "docs_lookup_symbol", Map.of("symbol", "@LocalOnly")).at("/results/0/version").asText());
            assertEquals(100, call(client, "docs_read", Map.of("path", "/docs/local", "maxChars", 100)).path("content").asText().length());
            assertEquals("sdk", call(client, "docs_links", Map.of("path", "/docs")).at("/links/0/namespace").asText());
            String previous = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                var session = DevSession.empty(DevServerConfig.defaults(root));
                try (var logs = new DevLogStore(root, session.sessionId(), "orders");
                     var server = DevMcpServer.start(root, new AgentQueryService(() -> session, logs), logs)) {
                    new DevSessionStore(root).writeSession(session.withStatus("running").withMcp(
                            DevSession.ServiceStatus.running("mcp", server.url(), server.port(), null, "test")));
                    assertTrue(call(client, "get_status", Map.of()).toString().contains(session.sessionId()));
                    assertTrue(call(client, "start_dev", Map.of()).toString().contains(session.sessionId()));
                    if (previous != null) {
                        assertTrue(call(client, "wait_for_change", Map.of("sessionId", previous, "afterSequence", 0,
                                "timeoutMs", 0)).path("sessionChanged").asBoolean());
                    }
                    changed.set(new CountDownLatch(1));
                    client.subscribeResource(new McpSchema.SubscribeRequest(DevMcpServer.DIAGNOSTICS_RESOURCE));
                    logs.process("app", "application", "orders", "orders-1", "stderr", "ERROR intentional failure");
                    assertTrue(changed.get().await(3, TimeUnit.SECONDS));
                    assertTrue(call(client, "get_active_problems", Map.of()).toString().contains("intentional failure"));
                    assertEquals(tools, client.listTools().tools());
                }
                new DevSessionStore(root).writeSession(session.withStatus("stopped"));
                assertEquals("dev-server-not-running", call(client, "get_status", Map.of()).path("status").asText());
                assertEquals("ready", call(client, "docs_start", Map.of()).path("status").asText());
                previous = session.sessionId();
            }
        }
    }

    @Test
    void correlatesForwardedResponsesWhenCompletionOrderChanges(@TempDir Path root) throws Exception {
        var session = DevSession.empty(DevServerConfig.defaults(root));
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (var logs = new DevLogStore(root, session.sessionId(), "orders");
             var server = DevMcpServer.start(root, new AgentQueryService(() -> {
                 if (requests.incrementAndGet() != 1) return session.withStatus("later-response");
                 firstStarted.countDown();
                 try { assertTrue(releaseFirst.await(10, TimeUnit.SECONDS)); }
                 catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                 return session.withStatus("first-response");
             }, logs), logs)) {
            new DevSessionStore(root).writeSession(session.withStatus("running").withMcp(
                    DevSession.ServiceStatus.running("mcp", server.url(), server.port(), null, "test")));
            try (var client = stdioClient(root, root.resolve("cache"), changed);
                 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                client.initialize();
                client.subscribeResource(new McpSchema.SubscribeRequest(DevMcpServer.DIAGNOSTICS_RESOURCE));
                try {
                    var first = executor.submit(() -> call(client, "get_status", Map.of()));
                    assertTrue(firstStarted.await(4, TimeUnit.SECONDS));
                    logs.process("app", "application", "orders", "orders-1", "stderr", "ERROR correlation notification");
                    assertTrue(changed.await(3, TimeUnit.SECONDS), "notifications must pass while a request is pending");
                    var later = call(client, "get_status", Map.of());
                    assertEquals("later-response", later.at("/session/status").asText());
                    assertEquals(session.sessionId(), later.at("/session/sessionId").asText());
                    assertFalse(first.isDone(), "the first forwarded request must still be blocked");
                    releaseFirst.countDown();
                    assertEquals("first-response", first.get(4, TimeUnit.SECONDS).at("/session/status").asText());
                    assertEquals(2, requests.get());
                } finally { releaseFirst.countDown(); }
            }
        } finally { releaseFirst.countDown(); }
    }

    @Test
    void latestDownloadDoesNotBlockStatusAndCanBeReadOffline(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        byte[] archive = archive("sdk", "1.2.3"), checksum = hash(archive).getBytes();
        byte[] otherArchive = archive("sdk", "1.2.4"), otherChecksum = hash(otherArchive).getBytes();
        CountDownLatch downloading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(httpExecutor);
        http.createContext("/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                byte[] bytes;
                if (path.endsWith("maven-metadata.xml")) {
                    bytes = "<metadata><versioning><release>1.2.3</release></versioning></metadata>".getBytes();
                } else if (path.contains("/1.2.4/")) {
                    bytes = path.endsWith(".sha256") ? otherChecksum : otherArchive;
                } else {
                    downloading.countDown();
                    try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    bytes = path.endsWith(".sha256") ? checksum : archive;
                }
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        http.start();
        String repository = "-Dfluxzero.dev.docs.repository=http://127.0.0.1:" + http.getAddress().getPort() + "/maven/";
        try (var client = stdioClient(root, directory.resolve("cache"), new CountDownLatch(1), repository);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            client.initialize();
            var docs = executor.submit(() -> call(client, "docs_start", Map.of()));
            assertTrue(downloading.await(4, TimeUnit.SECONDS));
            assertEquals("dev-server-not-running", call(client, "get_status", Map.of()).path("status").asText());
            // The second docs_start must complete before the first, with its own version and selection.
            var otherDocs = call(client, "docs_start", Map.of("version", "1.2.4"));
            assertEquals("1.2.4", otherDocs.path("version").asText());
            assertEquals("explicit-version", otherDocs.path("selection").asText());
            assertFalse(docs.isDone(), "the first download must still be blocked");
            release.countDown();
            var firstDocs = docs.get(4, TimeUnit.SECONDS);
            assertEquals("1.2.3", firstDocs.path("version").asText());
            assertEquals("latest-release", firstDocs.path("selection").asText());
            assertEquals(5, calls.get());
        } finally { release.countDown(); http.stop(0); httpExecutor.close(); }
        try (var client = stdioClient(root, directory.resolve("cache"), new CountDownLatch(1), repository)) {
            client.initialize();
            assertEquals("cache", call(client, "docs_start", Map.of()).path("source").asText());
        }
    }

    @Test
    void exitsOnStdinEofWithoutCreatingProjectState(@TempDir Path directory) throws Exception {
        var command = new ArrayList<>(List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                DevMcpStdioMain.class.getName(), "--project-dir", directory.toString()));
        Process process = new ProcessBuilder(command).redirectError(directory.resolve("stderr.log").toFile())
                .redirectOutput(directory.resolve("stdout.log").toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "MCP process must exit when its agent closes stdin");
            assertEquals(0, process.exitValue());
            assertFalse(Files.exists(directory.resolve(".fluxzero")));
        } finally { process.destroyForcibly(); }
    }

    @Test
    void eofCancelsAnActiveDocumentationDownload(@TempDir Path directory) throws Exception {
        CountDownLatch downloading = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                downloading.countDown();
                try { release.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        http.start();
        var process = new ProcessBuilder(javaExecutable(),
                "-Dfluxzero.dev.docs.repository=http://127.0.0.1:" + http.getAddress().getPort() + "/",
                "-Dfluxzero.dev.docs.cacheDirectory=" + directory.resolve("cache"),
                "-cp", System.getProperty("java.class.path"), DevMcpStdioMain.class.getName(),
                "--project-dir", directory.toString()).redirectError(directory.resolve("stderr.log").toFile()).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = process.inputReader(StandardCharsets.UTF_8);
            var writer = process.outputWriter(StandardCharsets.UTF_8);
            writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                    Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                           "clientInfo", Map.of("name", "eof-test", "version", "1")))) + "\n");
            writer.flush();
            assertTrue(executor.submit(reader::readLine).get(5, TimeUnit.SECONDS).contains("result"));
            writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n");
            writer.write(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params",
                    Map.of("name", "docs_start", "arguments", Map.of("version", "1.2.3")))) + "\n");
            writer.flush();
            assertTrue(downloading.await(5, TimeUnit.SECONDS));
            writer.close();
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), "EOF must cancel the pending download and stop the JVM");
            assertEquals(0, process.exitValue());
        } finally { release.countDown(); process.destroyForcibly(); http.stop(0); }
    }

    static McpSyncClient stdioClient(Path root, Path cache, CountDownLatch changed, String... properties) {
        return stdioClient(root, cache, changed::countDown, properties);
    }

    static McpSyncClient stdioClient(Path root, Path cache, Runnable changed, String... properties) {
        var args = new ArrayList<>(List.of(properties));
        args.add("-Dfluxzero.dev.docs.cacheDirectory=" + cache);
        args.addAll(List.of("-cp", System.getProperty("java.class.path"), DevMcpStdioMain.class.getName(),
                           "--project-dir", root.toString()));
        var parameters = ServerParameters.builder(javaExecutable()).args(args).build();
        var transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JSON));
        transport.setStdErrorHandler(line -> System.err.println("stdio: " + line));
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(6)).initializationTimeout(Duration.ofSeconds(10))
                .resourcesUpdateConsumer(contents -> changed.run()).build();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java").toString();
    }

    static com.fasterxml.jackson.databind.JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> String.valueOf(result.content()));
        return JSON.valueToTree(result.structuredContent());
    }
}
