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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Agent-owned MCP server: local documentation and an optional connection to the project environment. */
public final class DevMcpStdioMain {
    static final String INSTRUCTIONS = "Use docs_start, then search/read relevant articles; preserve namespace/version. "
            + "Call get_status for project readiness. If dev-server-not-running, follow start: run "
            + "fz mcp --ensure-dev with closed stdin for the same directory when development is needed. Docs work without it. "
            + "After edits use wait_for_change with sessionId/afterSequence; drain hasMore. Apply problemChanges by id; "
            + "get_active_problems on activeProblemCount mismatch or sessionChanged.";

    private DevMcpStdioMain() {}

    public static void main(String[] args) throws Exception {
        OutputStream protocolOutput = System.out;
        System.setOut(System.err);
        try (StdioBridge bridge = StdioBridge.start(projectDirectory(args), System.in, protocolOutput)) {
            Thread shutdown = new Thread(bridge::close, "fluxzero-mcp-stdio-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                bridge.disconnected.await();
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* JVM shutdown already owns cleanup. */ }
            }
        }
    }

    static Path projectDirectory(String[] args) {
        for (int index = 0; index < args.length; index++) {
            if (args[index].startsWith("--project-dir=")) {
                return Path.of(args[index].substring("--project-dir=".length())).toAbsolutePath().normalize();
            }
            if ("--project-dir".equals(args[index]) && index + 1 < args.length) {
                return Path.of(args[index + 1]).toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    static final class StdioBridge implements AutoCloseable {
        private final McpSyncServer localServer;
        private final DevMcpProjectClient project;
        private final AgentDocsService docs;
        private final CountDownLatch disconnected;
        private final AtomicBoolean closed = new AtomicBoolean();

        static StdioBridge start(Path directory, InputStream input, OutputStream output) {
            Path root = directory.toAbsolutePath().normalize();
            return start(root, input, output, new AgentDocsService(
                    () -> DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString()}), new AgentDocsStore()));
        }

        static StdioBridge start(Path directory, InputStream input, OutputStream output, AgentDocsService docs) {
            if (!Files.isDirectory(directory)) {
                docs.close();
                throw new IllegalArgumentException("MCP project directory must exist: " + directory);
            }
            CountDownLatch disconnected = new CountDownLatch(1);
            ObjectMapper mapper = new ObjectMapper();
            var project = new DevMcpProjectClient(directory, mapper);
            try {
                var tools = new ArrayList<>(DevMcpTools.tools(project::call));
                for (var specification : AgentDocsTools.tools(docs, mapper)) {
                    if (specification.tool().name().equals("docs_start")) {
                        tools.add(DevMcpTools.tool("docs_start", specification.tool().description(),
                                AgentDocsTools.properties(), arguments -> {
                                    @SuppressWarnings("unchecked")
                                    var response = new LinkedHashMap<>((Map<String, Object>) docs.start(arguments));
                                    response.put("development", project.availability());
                                    return response;
                                }, mapper, true));
                    } else {
                        tools.add(specification);
                    }
                }
                InputStream observed = new FilterInputStream(input) {
                    @Override
                    public int read() throws IOException {
                        try { int value = in.read(); if (value < 0) disconnected.countDown(); return value; }
                        catch (IOException e) { disconnected.countDown(); throw e; }
                    }
                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        try { int count = in.read(bytes, offset, length); if (count < 0) disconnected.countDown(); return count; }
                        catch (IOException e) { disconnected.countDown(); throw e; }
                    }
                };
                var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper), observed, output);
                var server = McpServer.sync(transport)
                        .serverInfo("fluxzero-dev-stdio", DevServerVersion.current())
                        .instructions(INSTRUCTIONS)
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).resources(true, false).build())
                        .tools(tools)
                        .resources(new McpServerFeatures.SyncResourceSpecification(DevMcpTools.diagnosticsResource(),
                                (exchange, request) -> project.read(request)))
                        .build();
                project.onResourceChange(uri -> server.getAsyncServer().notifyResourcesUpdated(
                        new McpSchema.ResourcesUpdatedNotification(uri)).onErrorComplete().subscribe());
                return new StdioBridge(server, project, docs, disconnected);
            } catch (RuntimeException e) {
                docs.close();
                project.close();
                throw e;
            }
        }

        private StdioBridge(McpSyncServer localServer, DevMcpProjectClient project, AgentDocsService docs,
                            CountDownLatch disconnected) {
            this.localServer = localServer;
            this.project = project;
            this.docs = docs;
            this.disconnected = disconnected;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            disconnected.countDown();
            docs.close();
            project.close();
            try { localServer.getAsyncServer().closeGracefully().block(Duration.ofSeconds(3)); }
            catch (RuntimeException ignored) { /* Peer may already have closed stdin. */ }
        }
    }
}
