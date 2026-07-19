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
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stdio MCP adapter that discovers and forwards to the active dev environment for a project.
 */
public final class DevMcpStdioMain {
    private DevMcpStdioMain() {
    }

    public static void main(String[] args) throws Exception {
        OutputStream protocolOutput = System.out;
        System.setOut(System.err);
        Path projectDirectory = projectDirectory(args);
        StdioBridge bridge = StdioBridge.start(projectDirectory, System.in, protocolOutput);
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::close, "fluxzero-mcp-stdio-shutdown"));
        new CountDownLatch(1).await();
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
        private final McpSyncClient remoteClient;

        static StdioBridge start(Path projectDirectory, InputStream input, OutputStream output) {
            DevSession session = new DevSessionStore(projectDirectory).reconcileUnexpectedStop()
                    .filter(candidate -> "running".equals(candidate.status()))
                    .filter(candidate -> "running".equals(candidate.mcp().state()))
                    .orElseThrow(() -> new IllegalStateException(
                            "No active Fluxzero dev environment found in " + projectDirectory));
            if (!ProcessUtils.isAlive(session.pid())) {
                throw new IllegalStateException("Fluxzero dev environment process " + session.pid()
                                                + " is no longer running");
            }
            URI endpoint = URI.create(session.mcp().url());
            Path tokenFile = tokenFile(projectDirectory, session);
            String token;
            try {
                token = Files.readString(tokenFile).trim();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read MCP token " + tokenFile, e);
            }

            AtomicReference<McpSyncServer> localServer = new AtomicReference<>();
            McpSyncClient remoteClient = remoteClient(endpoint, token, localServer);
            try {
                remoteClient.initialize();
                List<McpServerFeatures.SyncToolSpecification> tools = remoteClient.listTools().tools().stream()
                        .map(tool -> new McpServerFeatures.SyncToolSpecification(
                                tool, (exchange, request) -> remoteClient.callTool(request)))
                        .toList();
                List<McpServerFeatures.SyncResourceSpecification> resources = remoteClient.listResources()
                        .resources().stream()
                        .map(resource -> new McpServerFeatures.SyncResourceSpecification(
                                resource, (exchange, request) -> remoteClient.readResource(request)))
                        .toList();

                JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
                StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, input, output);
                McpSyncServer server = McpServer.sync(transport)
                        .serverInfo("fluxzero-dev-stdio", "0-SNAPSHOT")
                        .instructions("Read-only stdio adapter for the active Fluxzero development environment.")
                        .capabilities(McpSchema.ServerCapabilities.builder()
                                              .tools(false)
                                              .resources(true, false)
                                              .build())
                        .tools(tools)
                        .resources(resources)
                        .build();
                localServer.set(server);
                for (McpSchema.Resource resource : remoteClient.listResources().resources()) {
                    remoteClient.subscribeResource(McpSchema.SubscribeRequest.builder(resource.uri()).build());
                }
                return new StdioBridge(server, remoteClient);
            } catch (RuntimeException e) {
                remoteClient.closeGracefully();
                throw e;
            }
        }

        private static McpSyncClient remoteClient(URI endpoint, String token,
                                                  AtomicReference<McpSyncServer> localServer) {
            String baseUrl = endpoint.getScheme() + "://" + endpoint.getAuthority();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                    .endpoint(endpoint.getPath())
                    .httpRequestCustomizer((request, method, uri, body, context) -> request.header(
                            "Authorization", "Bearer " + token))
                    .build();
            return McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(35))
                    .initializationTimeout(Duration.ofSeconds(5))
                    .resourcesUpdateConsumer(contents -> {
                        McpSyncServer server = localServer.get();
                        if (server != null) {
                            contents.stream().map(McpSchema.ResourceContents::uri).distinct()
                                    .forEach(uri -> server.notifyResourcesUpdated(
                                            new McpSchema.ResourcesUpdatedNotification(uri)));
                        }
                    })
                    .build();
        }

        private static Path tokenFile(Path projectDirectory, DevSession session) {
            String configured = session.mcp().metadata().get("tokenFile");
            return configured == null || configured.isBlank()
                    ? projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(DevMcpServer.TOKEN_FILE)
                    : Path.of(configured);
        }

        private StdioBridge(McpSyncServer localServer, McpSyncClient remoteClient) {
            this.localServer = localServer;
            this.remoteClient = remoteClient;
        }

        @Override
        public void close() {
            localServer.closeGracefully();
            remoteClient.closeGracefully();
        }
    }
}
