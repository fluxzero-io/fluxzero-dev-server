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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Lazily connects project requests; only start_dev explicitly requests CLI bootstrap. */
final class DevMcpProjectClient implements AutoCloseable {
    private final Path directory;
    private final DevSessionStore sessions;
    private final ObjectMapper mapper;
    private final DevMcpDevStarter starter;
    private volatile Consumer<String> resourceChange = ignored -> {};
    private Connection connection;
    private boolean closed;

    DevMcpProjectClient(Path directory, ObjectMapper mapper) {
        this.directory = directory.toAbsolutePath().normalize();
        this.sessions = new DevSessionStore(this.directory);
        this.mapper = mapper;
        this.starter = new DevMcpDevStarter(this.directory);
    }

    void onResourceChange(Consumer<String> listener) { resourceChange = listener; }

    Map<String, Object> availability() {
        try {
            return activeSession() == null ? unavailable(false) : Map.of("status", "dev-server-running",
                                                                       "projectDirectory", directory.toString());
        } catch (RuntimeException e) {
            return unavailable(true);
        }
    }

    McpSchema.CallToolResult start(McpSchema.CallToolRequest request) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("start_dev accepts no arguments; it uses this MCP connection's project directory.").build();
        }
        try {
            if (activeSession() == null) starter.start();
            Connection selected = connect();
            if (selected != null) return selected.client().callTool(
                    new McpSchema.CallToolRequest("get_status", Map.of())).block(Duration.ofSeconds(6));
            return unavailableResult(false, false);
        } catch (RuntimeException e) {
            return unavailableResult(true, true);
        }
    }

    McpSchema.CallToolResult call(McpSchema.CallToolRequest request) {
        Connection selected = null;
        try {
            selected = connect();
            if (selected != null) return selected.client().callTool(request).block(Duration.ofSeconds(36));
            return unavailableResult(false, !request.name().equals("get_status"));
        } catch (RuntimeException e) {
            invalidate(selected);
            return unavailableResult(true, !request.name().equals("get_status"));
        }
    }

    McpSchema.ReadResourceResult read(McpSchema.ReadResourceRequest request) {
        Connection selected = null;
        Map<String, Object> status;
        try {
            selected = connect();
            if (selected != null) return selected.client().readResource(request).block(Duration.ofSeconds(6));
            status = unavailable(false);
        } catch (RuntimeException e) {
            invalidate(selected);
            status = unavailable(true);
        }
        return McpSchema.ReadResourceResult.builder(List.of(McpSchema.TextResourceContents.builder(request.uri(),
                DevMcpTools.json(status, mapper)).mimeType("application/json").build())).build();
    }

    private McpSchema.CallToolResult unavailableResult(boolean failed, boolean error) {
        var status = unavailable(failed);
        if ("dev-server-start-failed".equals(status.get("status"))) error = true;
        return McpSchema.CallToolResult.builder().structuredContent(status)
                .addTextContent(DevMcpTools.json(status, mapper)).isError(error).build();
    }

    private Map<String, Object> unavailable(boolean failed) {
        var startup = starter.status();
        if (!startup.isEmpty()) return startup;
        return Map.of("status", failed ? "dev-server-unavailable" : "dev-server-not-running",
                "projectDirectory", directory.toString(), "documentationAvailable", true,
                "message", failed ? "The project dev server could not be reached. Documentation remains available."
                                  : "No active project dev server. Call start_dev when development is needed.",
                "start", Map.of("tool", "start_dev", "arguments", Map.of()));
    }

    private DevSession activeSession() {
        return sessions.readSession().filter(session -> "running".equals(session.status()))
                .filter(session -> "running".equals(session.mcp().state()))
                .filter(session -> ProcessUtils.isAlive(session.pid(), session.startedAt())).orElse(null);
    }

    private synchronized Connection connect() {
        if (closed) throw new IllegalStateException("MCP connection is closed");
        DevSession session = activeSession();
        if (session == null) { invalidate(connection); return null; }
        URI endpoint = URI.create(session.mcp().url());
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
            || endpoint.getUserInfo() != null) {
            throw new IllegalStateException("Project MCP endpoint must use local loopback HTTP");
        }
        String configured = session.mcp().metadata().get("tokenFile");
        Path tokenFile = configured == null || configured.isBlank()
                ? directory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevMcpServer.TOKEN_FILE) : Path.of(configured);
        String token;
        try { token = Files.readString(tokenFile).strip(); }
        catch (Exception e) { throw new IllegalStateException("Project MCP token is unavailable"); }
        if (connection != null && connection.sessionId().equals(session.sessionId())
            && connection.endpoint().equals(endpoint) && connection.token().equals(token)) return connection;
        invalidate(connection);
        var transport = HttpClientStreamableHttpTransport.builder("http://" + endpoint.getAuthority())
                .endpoint(endpoint.getPath())
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header("Authorization", "Bearer " + token))
                .build();
        McpAsyncClient client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(35))
                .initializationTimeout(Duration.ofSeconds(5))
                .resourcesUpdateConsumer(contents -> {
                    contents.stream().map(McpSchema.ResourceContents::uri).distinct().forEach(uri -> resourceChange.accept(uri));
                    return reactor.core.publisher.Mono.empty();
                }).build();
        try {
            client.initialize().block(Duration.ofSeconds(5));
            client.subscribeResource(McpSchema.SubscribeRequest.builder(
                    DevMcpServer.DIAGNOSTICS_RESOURCE).build()).block(Duration.ofSeconds(3));
            connection = new Connection(session.sessionId(), endpoint, token, client);
            starter.observedReady();
            return connection;
        } catch (RuntimeException e) {
            closeClient(client);
            throw e;
        }
    }

    private synchronized void invalidate(Connection selected) {
        if (selected != null && selected == connection) {
            connection = null;
            closeClient(selected.client());
        }
    }

    private static void closeClient(McpAsyncClient client) {
        try { client.closeGracefully().block(Duration.ofSeconds(2)); }
        catch (RuntimeException ignored) { /* A stopped backend may no longer accept transport cleanup. */ }
    }

    @Override
    public void close() {
        starter.close();
        synchronized (this) { closed = true; invalidate(connection); }
    }

    private record Connection(String sessionId, URI endpoint, String token, McpAsyncClient client) {}
}
