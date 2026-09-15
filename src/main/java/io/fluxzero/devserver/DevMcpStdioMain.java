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
            + "Use select_project to inspect/change the app directory. Call get_status for readiness; call start_dev "
            + "when development is needed; poll get_status while starting. Docs work without it. "
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
        private final DevMcpWorkspace workspace;
        private final CountDownLatch disconnected;
        private final AtomicBoolean closed = new AtomicBoolean();

        static StdioBridge start(Path directory, InputStream input, OutputStream output) {
            Path root = directory.toAbsolutePath().normalize();
            Path selected = DevMcpWorkspace.initialDirectory(root);
            return start(root, selected, input, output, DevMcpWorkspace.docs(selected));
        }

        static StdioBridge start(Path directory, InputStream input, OutputStream output, AgentDocsService docs) {
            return start(directory, directory, input, output, docs);
        }

        private static StdioBridge start(Path directory, Path selected, InputStream input, OutputStream output, AgentDocsService docs) {
            if (!Files.isDirectory(directory)) {
                docs.close();
                throw new IllegalArgumentException("MCP project directory must exist: " + directory);
            }
            CountDownLatch disconnected = new CountDownLatch(1);
            ObjectMapper mapper = new ObjectMapper();
            var workspace = new DevMcpWorkspace(directory, selected, docs, mapper);
            try {
                var tools = new ArrayList<>(DevMcpTools.tools(request -> workspace.current().project().call(request)));
                tools.add(new McpServerFeatures.SyncToolSpecification(
                        McpSchema.Tool.builder("start_dev", Map.of("type", "object", "properties", Map.of(),
                                "additionalProperties", false))
                                .description("Start or reuse development in this connection's project directory. "
                                        + "Returns status and a cursor when ready; poll get_status while starting. "
                                        + "May launch background processes, builds and configured startup commands.")
                                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false)
                                        .destructiveHint(false).idempotentHint(true).openWorldHint(true).build()).build(),
                        (exchange, request) -> workspace.current().project().start(request)));
                tools.add(DevMcpTools.tool("select_project",
                        "Inspect or select the directory used by documentation and dev tools. Omit projectDirectory to list candidates. "
                                + "Does not start or stop any environment.",
                        Map.of("projectDirectory", Map.of("type", "string", "description",
                                "Existing directory inside the workspace; relative paths such as app are accepted.")),
                        workspace::select, mapper, false));
                for (var specification : AgentDocsTools.tools(docs, mapper)) {
                    String name = specification.tool().name();
                    tools.add(new McpServerFeatures.SyncToolSpecification(specification.tool(), (exchange, request) -> {
                        try {
                            var context = workspace.current();
                            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                            Object response = switch (name) {
                                case "docs_start" -> {
                                    @SuppressWarnings("unchecked")
                                    var start = new LinkedHashMap<>((Map<String, Object>) context.docs().start(arguments));
                                    start.put("development", context.project().availability());
                                    yield start;
                                }
                                case "docs_search" -> context.docs().search(arguments, false);
                                case "docs_lookup_symbol" -> context.docs().search(arguments, true);
                                case "docs_read" -> context.docs().read(arguments, false);
                                case "docs_links" -> context.docs().read(arguments, true);
                                default -> throw new IllegalArgumentException("Unknown documentation tool: " + name);
                            };
                            return DevMcpTools.result(response, mapper);
                        } catch (RuntimeException e) {
                            return McpSchema.CallToolResult.builder().isError(true).addTextContent(
                                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).build();
                        }
                    }));
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
                var transport = new SerialStdioServerTransportProvider(new JacksonMcpJsonMapper(mapper), observed, output);
                var server = McpServer.sync(transport)
                        .serverInfo("fluxzero-dev-stdio", DevServerVersion.current())
                        .instructions(INSTRUCTIONS)
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).resources(true, false).build())
                        .tools(tools)
                        .resources(new McpServerFeatures.SyncResourceSpecification(DevMcpTools.diagnosticsResource(),
                                (exchange, request) -> workspace.current().project().read(request)))
                        .build();
                workspace.onResourceChange(uri -> server.getAsyncServer().notifyResourcesUpdated(
                        new McpSchema.ResourcesUpdatedNotification(uri)).onErrorComplete().subscribe());
                return new StdioBridge(server, workspace, disconnected);
            } catch (RuntimeException e) {
                workspace.close();
                throw e;
            }
        }

        private StdioBridge(McpSyncServer localServer, DevMcpWorkspace workspace, CountDownLatch disconnected) {
            this.localServer = localServer;
            this.workspace = workspace;
            this.disconnected = disconnected;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            disconnected.countDown();
            workspace.close();
            try { localServer.getAsyncServer().closeGracefully().block(Duration.ofSeconds(3)); }
            catch (RuntimeException ignored) { /* Peer may already have closed stdin. */ }
        }
    }
}
