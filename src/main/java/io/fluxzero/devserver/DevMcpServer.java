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
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Embedded read-only MCP endpoint for one Fluxzero dev environment. */
final class DevMcpServer implements AutoCloseable {
    static final String ENDPOINT = "/mcp";
    static final String DIAGNOSTICS_RESOURCE = "fluxzero://environment/current/diagnostics";
    static final String TOKEN_FILE = "mcp-token";
    static final String INSTRUCTIONS = "Call get_status; retain cursor. After edits use wait_for_change with sessionId/"
                                       + "afterSequence; drain hasMore until relevant work is terminal. Apply problemChanges "
                                       + "by id; get_active_problems on activeProblemCount mismatch, sessionChanged or lost "
                                       + "state. If waiting-for-project, generate in session.projectDirectory without "
                                       + "replacing MCP.";

    private final Server server;
    private final McpSyncServer mcpServer;
    private final AutoCloseable diagnosticsRegistration;
    private final Path tokenFile;
    private final String token;
    private final HttpLifecycle httpLifecycle;

    static DevMcpServer start(Path projectDirectory, AgentQueryService queryService, DevLogStore logStore) {
        return start(projectDirectory, queryService, logStore, ignored -> {
        });
    }

    static DevMcpServer start(Path projectDirectory, AgentQueryService queryService, DevLogStore logStore,
                              Consumer<Throwable> unexpectedFailure) {
        Path tokenFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(TOKEN_FILE);
        McpSyncServer mcpServer = null;
        Server server = null;
        HttpLifecycle httpLifecycle = new HttpLifecycle(unexpectedFailure);
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
                    .instructions(INSTRUCTIONS)
                    .capabilities(McpSchema.ServerCapabilities.builder()
                                          .tools(false)
                                          .resources(true, false)
                                          .build())
                    .tools(DevMcpTools.tools(queryService, objectMapper))
                    .resources(DevMcpTools.diagnosticsResource(queryService, objectMapper))
                    .build();

            server = new Server();
            server.setStopAtShutdown(false);
            server.setStopTimeout(0);
            server.addEventListener(httpLifecycle);
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
            AutoCloseable registration = logStore.onDiagnosticsChanged(() -> startedMcpServer.getAsyncServer()
                    .notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(DIAGNOSTICS_RESOURCE))
                    .onErrorComplete()
                    .subscribe());
            return new DevMcpServer(server, mcpServer, registration, tokenFile, token, httpLifecycle);
        } catch (Exception e) {
            httpLifecycle.beginManagedShutdown();
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
                         Path tokenFile, String token, HttpLifecycle httpLifecycle) {
        this.server = server;
        this.mcpServer = mcpServer;
        this.diagnosticsRegistration = diagnosticsRegistration;
        this.tokenFile = tokenFile;
        this.token = token;
        this.httpLifecycle = httpLifecycle;
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
        httpLifecycle.beginManagedShutdown();
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

    static final class HttpLifecycle implements LifeCycle.Listener {
        private final Consumer<Throwable> unexpectedFailure;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean managedShutdown = new AtomicBoolean();
        private final AtomicBoolean failureReported = new AtomicBoolean();

        HttpLifecycle(Consumer<Throwable> unexpectedFailure) {
            this.unexpectedFailure = java.util.Objects.requireNonNull(unexpectedFailure);
        }

        @Override
        public void lifeCycleStarted(LifeCycle event) {
            started.set(true);
        }

        @Override
        public void lifeCycleFailure(LifeCycle event, Throwable cause) {
            report(cause);
        }

        @Override
        public void lifeCycleStopped(LifeCycle event) {
            report(new IllegalStateException("Embedded MCP HTTP server stopped unexpectedly"));
        }

        void beginManagedShutdown() {
            managedShutdown.set(true);
        }

        private void report(Throwable failure) {
            if (started.get() && !managedShutdown.get() && failureReported.compareAndSet(false, true)) {
                unexpectedFailure.accept(failure);
            }
        }
    }
}
