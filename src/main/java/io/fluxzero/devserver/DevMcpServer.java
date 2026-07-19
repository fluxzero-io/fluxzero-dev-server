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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Embedded read-only MCP endpoint for one Fluxzero dev environment. */
final class DevMcpServer implements AutoCloseable {
    static final String ENDPOINT = "/mcp";
    static final String DIAGNOSTICS_RESOURCE = "fluxzero://environment/current/diagnostics";
    static final String TOKEN_FILE = "mcp-token";

    private final Server server;
    private final McpSyncServer mcpServer;
    private final AutoCloseable diagnosticsRegistration;
    private final Path tokenFile;
    private final String token;

    static DevMcpServer start(Path projectDirectory, AgentQueryService queryService, DevLogStore logStore) {
        Path tokenFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(TOKEN_FILE);
        McpSyncServer mcpServer = null;
        Server server = null;
        try {
            String token = createToken(tokenFile);
            ObjectMapper objectMapper = new ObjectMapper();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
            HttpServletStreamableServerTransportProvider transport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(jsonMapper)
                            .mcpEndpoint(ENDPOINT)
                            .keepAliveInterval(Duration.ofSeconds(15))
                            .securityValidator(headers -> validate(headers, token))
                            .build();

            mcpServer = McpServer.sync(transport)
                    .serverInfo("fluxzero-dev", DevServerVersion.current())
                    .instructions("Read-only access to the active Fluxzero development environment.")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                                          .tools(false)
                                          .resources(true, false)
                                          .build())
                    .tools(tools(queryService, objectMapper))
                    .resources(diagnosticsResource(queryService, objectMapper))
                    .build();

            server = new Server();
            server.setStopAtShutdown(false);
            server.setStopTimeout(0);
            ServerConnector connector = new ServerConnector(server);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.addServlet(new ServletHolder(transport), ENDPOINT);
            server.setHandler(context);
            server.start();

            McpSyncServer startedMcpServer = mcpServer;
            AutoCloseable registration = logStore.onDiagnosticsChanged(() -> startedMcpServer.notifyResourcesUpdated(
                    new McpSchema.ResourcesUpdatedNotification(DIAGNOSTICS_RESOURCE)));
            return new DevMcpServer(server, mcpServer, registration, tokenFile, token);
        } catch (Exception e) {
            stopAfterFailedStart(server, mcpServer, tokenFile);
            throw new IllegalStateException("Failed to start embedded MCP server", e);
        }
    }

    private static void stopAfterFailedStart(Server server, McpSyncServer mcpServer, Path tokenFile) {
        if (mcpServer != null) {
            mcpServer.closeGracefully();
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
                // Preserve the startup failure as the primary exception.
            }
        }
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
            // A later session will replace a stale token before exposing its endpoint.
        }
    }

    private DevMcpServer(Server server, McpSyncServer mcpServer, AutoCloseable diagnosticsRegistration,
                         Path tokenFile, String token) {
        this.server = server;
        this.mcpServer = mcpServer;
        this.diagnosticsRegistration = diagnosticsRegistration;
        this.tokenFile = tokenFile;
        this.token = token;
    }

    String url() {
        return "http://127.0.0.1:" + port() + ENDPOINT;
    }

    int port() {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    Path tokenFile() {
        return tokenFile;
    }

    String token() {
        return token;
    }

    private static List<McpServerFeatures.SyncToolSpecification> tools(AgentQueryService queryService,
                                                                        ObjectMapper objectMapper) {
        return List.of(
                tool("get_status", "Return compact status for the active Fluxzero dev environment.", Map.of(),
                     arguments -> queryService.getStatus(), objectMapper),
                tool("get_active_problems", "Return bounded unresolved problems, optionally filtered per app.",
                     selectorProperties(true), arguments -> queryService.getActiveProblems(
                             selector(arguments), intValue(arguments, "limit", AgentQueryService.DEFAULT_LIMIT)),
                     objectMapper),
                tool("get_logs", "Return a bounded structured log delta after a session-aware cursor.",
                     logProperties(false), arguments -> queryService.getLogs(cursor(arguments, queryService),
                                                                            selector(arguments),
                                                                            intValue(arguments, "limit",
                                                                                     AgentQueryService.DEFAULT_LIMIT)),
                     objectMapper),
                tool("get_test_status", "Return background test and startup-command status.", Map.of(),
                     arguments -> queryService.getTestStatus(), objectMapper),
                tool("wait_for_change", "Wait for a matching event and return a compact structured delta.",
                     logProperties(true), arguments -> queryService.waitForChange(
                             cursor(arguments, queryService), selector(arguments),
                             Duration.ofMillis(longValue(arguments, "timeoutMs", 30_000)),
                             intValue(arguments, "limit", AgentQueryService.DEFAULT_LIMIT)), objectMapper));
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name, String description, Map<String, Object> properties,
            java.util.function.Function<Map<String, Object>, Object> handler, ObjectMapper objectMapper) {
        McpSchema.Tool tool = McpSchema.Tool.builder(
                        name, Map.of("type", "object", "properties", properties, "additionalProperties", false))
                .description(description)
                .annotations(McpSchema.ToolAnnotations.builder()
                                     .readOnlyHint(true)
                                     .destructiveHint(false)
                                     .idempotentHint(true)
                                     .openWorldHint(false)
                                     .build())
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Object result = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return result(result, objectMapper);
                    } catch (RuntimeException e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    private static McpServerFeatures.SyncResourceSpecification diagnosticsResource(
            AgentQueryService queryService, ObjectMapper objectMapper) {
        McpSchema.Resource resource = McpSchema.Resource.builder(DIAGNOSTICS_RESOURCE, "active-diagnostics")
                .title("Fluxzero active diagnostics")
                .description("Current unresolved problems for all services and app instances in the environment.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            String json = json(queryService.getActiveProblems(AgentSelector.all(), AgentQueryService.MAX_LIMIT),
                               objectMapper);
            return McpSchema.ReadResourceResult.builder(List.of(
                    McpSchema.TextResourceContents.builder(DIAGNOSTICS_RESOURCE, json)
                            .mimeType("application/json")
                            .build())).build();
        });
    }

    private static McpSchema.CallToolResult result(Object result, ObjectMapper objectMapper) {
        Object structured = objectMapper.convertValue(result, Object.class);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json(result, objectMapper))
                .structuredContent(structured)
                .build();
    }

    private static String json(Object value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MCP response", e);
        }
    }

    private static AgentCursor cursor(Map<String, Object> arguments, AgentQueryService queryService) {
        String sessionId = stringValue(arguments.get("sessionId"));
        Object sequence = arguments.get("afterSequence");
        if (sessionId == null && sequence == null) {
            return null;
        }
        AgentCursor current = queryService.getStatus().cursor();
        return new AgentCursor(sessionId == null ? current.sessionId() : sessionId,
                               longValue(arguments, "afterSequence", 0));
    }

    private static AgentSelector selector(Map<String, Object> arguments) {
        String level = stringValue(arguments.get("minimumLevel"));
        DevLogEvent.Level minimumLevel = level == null ? null : DevLogEvent.Level.valueOf(level.toUpperCase());
        return new AgentSelector(strings(arguments.get("serviceIds")), strings(arguments.get("instanceIds")),
                                 strings(arguments.get("sources")), minimumLevel);
    }

    private static Map<String, Object> selectorProperties(boolean includeLimit) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("serviceIds", stringArray("Filter by application or infrastructure service id."));
        properties.put("instanceIds", stringArray("Filter by concrete application instance id."));
        properties.put("sources", stringArray("Filter by subsystem, for example app, compile, test, or runtime."));
        properties.put("minimumLevel", Map.of("type", "string", "enum", List.of("TRACE", "DEBUG", "INFO",
                                                                                  "WARN", "ERROR")));
        if (includeLimit) {
            properties.put("limit", integer("Maximum results, capped at " + AgentQueryService.MAX_LIMIT + "."));
        }
        return properties;
    }

    private static Map<String, Object> logProperties(boolean includeTimeout) {
        Map<String, Object> properties = new LinkedHashMap<>(selectorProperties(true));
        properties.put("sessionId", Map.of("type", "string", "description", "Cursor session id."));
        properties.put("afterSequence", integer("Return events after this sequence."));
        if (includeTimeout) {
            properties.put("timeoutMs", integer("Wait timeout in milliseconds, capped at 30000."));
        }
        return properties;
    }

    private static Map<String, Object> stringArray(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "minimum", 0, "description", description);
    }

    private static Set<String> strings(Object value) {
        if (value == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> result.add(String.valueOf(item)));
        } else {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private static int intValue(Map<String, Object> arguments, String name, int defaultValue) {
        return Math.toIntExact(longValue(arguments, name, defaultValue));
    }

    private static long longValue(Map<String, Object> arguments, String name, long defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String createToken(Path tokenFile) throws IOException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                          StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(tokenFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms still rely on the project-local file and loopback binding.
        }
        return token;
    }

    private static void validate(Map<String, List<String>> headers, String token)
            throws ServerTransportSecurityException {
        String host = header(headers, "Host");
        if (host == null || !(host.equals("127.0.0.1") || host.startsWith("127.0.0.1:")
                             || host.equals("localhost") || host.startsWith("localhost:"))) {
            throw new ServerTransportSecurityException(421, "MCP endpoint only accepts loopback hosts");
        }
        String origin = header(headers, "Origin");
        if (origin != null && !loopbackOrigin(origin)) {
            throw new ServerTransportSecurityException(403, "MCP endpoint rejected non-loopback origin");
        }
        String authorization = header(headers, "Authorization");
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ServerTransportSecurityException(401, "Missing or invalid MCP bearer token");
        }
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
    }

    private static boolean loopbackOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            diagnosticsRegistration.close();
        } catch (Exception ignored) {
            // The MCP server is already being shut down.
        }
        mcpServer.close();
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stop embedded MCP server", e);
        } finally {
            try {
                Files.deleteIfExists(tokenFile);
            } catch (IOException ignored) {
                // A stale token is harmless because the next session replaces it.
            }
        }
    }
}
