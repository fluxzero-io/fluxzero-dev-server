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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevMcpServerTest {

    @Test
    void servesReadOnlyToolsAndDiagnosticsNotifications(@TempDir Path projectDirectory) throws Exception {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        CountDownLatch update = new CountDownLatch(1);
        Path tokenFile;
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders")) {
            AgentQueryService queries = new AgentQueryService(() -> session, store);
            try (DevMcpServer server = DevMcpServer.start(projectDirectory, queries, store);
                 McpSyncClient client = client(server, update)) {
                tokenFile = server.tokenFile();
                assertTrue(Files.isRegularFile(tokenFile));
                client.initialize();

                Set<String> tools = client.listTools().tools().stream().map(McpSchema.Tool::name).collect(
                        java.util.stream.Collectors.toSet());
                assertEquals(Set.of("get_status", "get_active_problems", "get_logs", "get_test_status",
                                    "wait_for_change"), tools);

                McpSchema.CallToolResult status = client.callTool(
                        McpSchema.CallToolRequest.builder("get_status").arguments(Map.of()).build());
                assertFalse(Boolean.TRUE.equals(status.isError()));
                assertTrue(String.valueOf(status.structuredContent()).contains(session.sessionId()));

                McpSchema.ReadResourceResult diagnostics = client.readResource(
                        McpSchema.ReadResourceRequest.builder(DevMcpServer.DIAGNOSTICS_RESOURCE).build());
                McpSchema.TextResourceContents contents = (McpSchema.TextResourceContents) diagnostics.contents()
                        .getFirst();
                assertTrue(contents.text().contains("\"problems\":[]"));

                client.subscribeResource(McpSchema.SubscribeRequest.builder(DevMcpServer.DIAGNOSTICS_RESOURCE)
                                                 .build());
                store.process("app", "application", "orders", "orders-1", "stderr", "ERROR startup failed");

                assertTrue(update.await(2, TimeUnit.SECONDS), "diagnostics resource update was not delivered");

                McpSchema.CallToolResult problems = client.callTool(
                        McpSchema.CallToolRequest.builder("get_active_problems")
                                .arguments(Map.of("serviceIds", java.util.List.of("orders"), "limit", 10))
                                .build());
                assertFalse(Boolean.TRUE.equals(problems.isError()));
                assertTrue(String.valueOf(problems.structuredContent()).contains("orders-1"));
            }
        }
        assertFalse(Files.exists(tokenFile), "shutdown should remove the session bearer token");
    }

    @Test
    void rejectsMissingTokenAndNonLoopbackOrigin(@TempDir Path projectDirectory) throws Exception {
        DevSession session = DevSession.empty(DevServerConfig.defaults(projectDirectory));
        try (DevLogStore store = new DevLogStore(projectDirectory, session.sessionId(), "orders");
             DevMcpServer server = DevMcpServer.start(projectDirectory,
                                                      new AgentQueryService(() -> session, store), store)) {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest missingToken = HttpRequest.newBuilder(URI.create(server.url()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpRequest foreignOrigin = HttpRequest.newBuilder(URI.create(server.url()))
                    .header("Authorization", "Bearer " + server.token())
                    .header("Origin", "https://example.com")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            assertEquals(401, httpClient.send(missingToken, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403, httpClient.send(foreignOrigin, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test
    void stdioLauncherDiscoversAndForwardsToActiveProjectEnvironment(@TempDir Path projectDirectory) {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        try (DevServer devServer = new DevServer(config).start()) {
            ServerParameters parameters = ServerParameters.builder(javaExecutable())
                    .args("-cp", System.getProperty("java.class.path"), DevMcpStdioMain.class.getName(),
                          "--project-dir", projectDirectory.toString())
                    .build();
            StdioClientTransport transport = new StdioClientTransport(
                    parameters, new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
            try (McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(5))
                    .initializationTimeout(Duration.ofSeconds(5))
                    .build()) {
                client.initialize();

                assertEquals("fluxzero-dev-stdio", client.getServerInfo().name());
                assertEquals(5, client.listTools().tools().size());
                assertEquals(DevMcpServer.DIAGNOSTICS_RESOURCE,
                             client.listResources().resources().getFirst().uri());
                McpSchema.CallToolResult result = client.callTool(
                        McpSchema.CallToolRequest.builder("get_status").arguments(Map.of()).build());
                assertTrue(String.valueOf(result.structuredContent()).contains(devServer.session().sessionId()));
            }
        }
    }

    private static McpSyncClient client(DevMcpServer server, CountDownLatch update) {
        URI endpoint = URI.create(server.url());
        String baseUrl = endpoint.getScheme() + "://" + endpoint.getAuthority();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint.getPath())
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header(
                        "Authorization", "Bearer " + server.token()))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .resourcesUpdateConsumer(contents -> update.countDown())
                .build();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                       System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }
}
