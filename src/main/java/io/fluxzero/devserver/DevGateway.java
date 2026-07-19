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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Exposes the internal Fluxzero proxy and frontend development server as one browser origin.
 */
public final class DevGateway implements AutoCloseable {
    static final String BACKEND_PREFIX = "/_fluxzero";
    private static final Duration PORT_RELEASE_GRACE = Duration.ofSeconds(2);
    private static final Duration PORT_RETRY_INTERVAL = Duration.ofMillis(50);
    private static final Duration FRONTEND_RELOAD_GRACE = Duration.ofSeconds(30);
    private static final Duration FRONTEND_RETRY_INTERVAL = Duration.ofMillis(50);
    private static final byte[] FRONTEND_UNAVAILABLE =
            "Frontend dev server is not ready yet".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BACKEND_UNAVAILABLE =
            "Fluxzero backend is not ready yet".getBytes(StandardCharsets.UTF_8);

    private final Server server;
    private final int port;

    private DevGateway(Server server, int port) {
        this.server = server;
        this.port = port;
    }

    static void requireAvailablePort(int port) {
        requireAvailablePort(port, PORT_RELEASE_GRACE);
    }

    static void requireAvailablePort(int port, Duration releaseGrace) {
        if (port == 0) {
            return;
        }
        if (releaseGrace.isNegative()) {
            throw new IllegalArgumentException("releaseGrace must not be negative");
        }
        long deadline = System.nanoTime() + releaseGrace.toNanos();
        BindException lastFailure;
        do {
            try (ServerSocket socket = new ServerSocket()) {
                // Match Jetty's bind behavior and allow an immediately restarted gateway to reclaim its own port.
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", port));
                return;
            } catch (BindException e) {
                lastFailure = e;
            } catch (Exception e) {
                throw new DevServerStartupException("Could not validate public gateway port " + port, e);
            }
            if (System.nanoTime() >= deadline) {
                throw DevServerStartupException.portInUse(port, lastFailure);
            }
            try {
                Thread.sleep(PORT_RETRY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DevServerStartupException("Interrupted while waiting for public gateway port " + port, e);
            }
        } while (true);
    }

    static DevGateway start(String fluxzeroProxyUrl, String frontendUrl, BooleanSupplier frontendReady) {
        return start(fluxzeroProxyUrl, frontendUrl, frontendReady, FrontendConfig.DEFAULT_BACKEND_PATHS);
    }

    static DevGateway start(String fluxzeroProxyUrl, String frontendUrl, BooleanSupplier frontendReady,
                            List<String> backendPaths) {
        return start(fluxzeroProxyUrl, frontendUrl, frontendReady, backendPaths, 0);
    }

    static DevGateway start(String fluxzeroProxyUrl, String frontendUrl, BooleanSupplier frontendReady,
                            List<String> backendPaths, int port) {
        return start(fluxzeroProxyUrl, frontendUrl, frontendReady, () -> true, backendPaths, port);
    }

    static DevGateway start(String fluxzeroProxyUrl, String frontendUrl, BooleanSupplier frontendReady,
                            BooleanSupplier backendReady, List<String> backendPaths, int port) {
        Objects.requireNonNull(fluxzeroProxyUrl, "fluxzeroProxyUrl must not be null");
        Objects.requireNonNull(frontendUrl, "frontendUrl must not be null");
        Objects.requireNonNull(frontendReady, "frontendReady must not be null");
        Objects.requireNonNull(backendReady, "backendReady must not be null");
        backendPaths = List.copyOf(Objects.requireNonNull(backendPaths, "backendPaths must not be null"));
        try {
            URI fluxzeroTarget = URI.create(withoutTrailingSlash(fluxzeroProxyUrl));
            URI frontendTarget = URI.create(withoutTrailingSlash(frontendUrl));
            List<String> configuredBackendPaths = backendPaths;
            Server server = new Server();
            server.setStopAtShutdown(false);
            // This is a local supervisor: on shutdown, active browser and HMR connections must not delay exit.
            server.setStopTimeout(0);
            ServerConnector connector = new ServerConnector(server);
            connector.setHost("127.0.0.1");
            connector.setPort(port);
            if (port != 0) {
                connector.setReuseAddress(true);
                connector.setReusePort(false);
            }
            server.addConnector(connector);

            ProxyHandler.Reverse reverseProxy = new ProxyHandler.Reverse(
                    request -> target(request, fluxzeroTarget, frontendTarget, configuredBackendPaths)) {
                @Override
                protected HttpField filterServerToProxyResponseField(HttpField field) {
                    HttpField filtered = super.filterServerToProxyResponseField(field);
                    if (filtered == null || filtered.getHeader() != HttpHeader.LOCATION) {
                        return filtered;
                    }
                    return new HttpField(HttpHeader.LOCATION,
                                         publicLocation(filtered.getValue(), connector.getLocalPort(),
                                                        fluxzeroTarget, frontendTarget, configuredBackendPaths));
                }
            };
            AtomicBoolean frontendWasReady = new AtomicBoolean();
            Handler httpHandler = new Handler.Wrapper(reverseProxy) {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception {
                    Route route = route(request, configuredBackendPaths);
                    if (route == Route.PASSTHROUGH_BACKEND && !backendReady.getAsBoolean()) {
                        unavailable(response, callback, BACKEND_UNAVAILABLE);
                        return true;
                    }
                    if (route != Route.FRONTEND) {
                        return super.handle(request, response, callback);
                    }
                    if (frontendReady.getAsBoolean()) {
                        frontendWasReady.set(true);
                        return super.handle(request, response, callback);
                    }
                    if (!frontendWasReady.get()) {
                        unavailable(response, callback, FRONTEND_UNAVAILABLE);
                        return true;
                    }
                    waitForFrontend(server, frontendReady, frontendWasReady, reverseProxy,
                                    request, response, callback);
                    return true;
                }
            };

            ContextHandler context = new ContextHandler("/");
            WebSocketUpgradeHandler websocketHandler = WebSocketUpgradeHandler.from(server, context, container -> {
                container.setMaxBinaryMessageSize(0);
                container.setMaxTextMessageSize(0);
                container.setMaxFrameSize(0);
                container.addMapping("/*", (request, response, callback) -> {
                    Route route = route(request, configuredBackendPaths);
                    if (route == Route.PASSTHROUGH_BACKEND && !backendReady.getAsBoolean()) {
                        unavailable(response, callback, BACKEND_UNAVAILABLE);
                        return null;
                    }
                    boolean backend = route != Route.FRONTEND;
                    if (!backend && !frontendReady.getAsBoolean()) {
                        unavailable(response, callback, FRONTEND_UNAVAILABLE);
                        return null;
                    }
                    List<String> protocols = request.getSubProtocols();
                    if (!protocols.isEmpty()) {
                        response.setAcceptedSubProtocol(protocols.getFirst());
                    }
                    URI target = target(request, fluxzeroTarget, frontendTarget, configuredBackendPaths).toURI();
                    String upstreamOrigin = backend
                            ? request.getHeaders().get(HttpHeader.ORIGIN)
                            : origin(frontendTarget);
                    return new GatewayWebSocketBridge(target, protocols,
                                                      request.getHeaders().get(HttpHeader.COOKIE),
                                                      request.getHeaders().get(HttpHeader.AUTHORIZATION),
                                                      upstreamOrigin);
                });
            });
            websocketHandler.setHandler(httpHandler);
            context.setHandler(websocketHandler);
            server.setHandler(context);
            server.start();
            return new DevGateway(server, connector.getLocalPort());
        } catch (Exception e) {
            if (port != 0 && causedByBindFailure(e)) {
                throw DevServerStartupException.portInUse(port, e);
            }
            String portDetail = port == 0 ? "a dynamic port" : "port " + port;
            throw new IllegalStateException("Failed to start frontend dev gateway on " + portDetail, e);
        }
    }

    private static boolean causedByBindFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof BindException) {
                return true;
            }
        }
        return false;
    }

    String url() {
        return "http://localhost:" + port;
    }

    int port() {
        return port;
    }

    String backendUrl() {
        return url() + BACKEND_PREFIX;
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stop frontend dev gateway", e);
        }
    }

    private static HttpURI target(Request request, URI fluxzeroTarget, URI frontendTarget,
                                  List<String> backendPaths) {
        Route route = route(request, backendPaths);
        boolean backend = route != Route.FRONTEND;
        String path = request.getHttpURI().getPath();
        String routedPath = route == Route.PREFIXED_BACKEND ? stripBackendPrefix(path) : path;
        String query = request.getHttpURI().getQuery();
        String pathQuery = query == null ? routedPath : routedPath + "?" + query;
        return HttpURI.build((backend ? fluxzeroTarget : frontendTarget).toString() + pathQuery);
    }

    private static boolean backendRequest(Request request, List<String> backendPaths) {
        return route(request, backendPaths) != Route.FRONTEND;
    }

    private static Route route(Request request, List<String> backendPaths) {
        String path = request.getHttpURI().getPath();
        if (matchesPath(path, BACKEND_PREFIX)) {
            return Route.PREFIXED_BACKEND;
        }
        return backendPaths.stream().anyMatch(candidate -> matchesPath(path, candidate))
                ? Route.PASSTHROUGH_BACKEND : Route.FRONTEND;
    }

    private static String stripBackendPrefix(String path) {
        String stripped = path.substring(BACKEND_PREFIX.length());
        return stripped.isEmpty() ? "/" : stripped;
    }

    private static void unavailable(Response response, Callback callback, byte[] message) {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
        response.write(true, ByteBuffer.wrap(message), callback);
    }

    private static void waitForFrontend(Server server, BooleanSupplier frontendReady,
                                        AtomicBoolean frontendWasReady, Handler handler,
                                        Request request, Response response, Callback callback) {
        Thread.ofVirtual().name("fluxzero-dev-frontend-reload").start(() -> {
            long deadline = System.nanoTime() + FRONTEND_RELOAD_GRACE.toNanos();
            try {
                while (server.isRunning() && System.nanoTime() < deadline) {
                    if (frontendReady.getAsBoolean()) {
                        frontendWasReady.set(true);
                        if (!handler.handle(request, response, callback)) {
                            callback.failed(new IllegalStateException("Frontend proxy did not handle the request"));
                        }
                        return;
                    }
                    Thread.sleep(FRONTEND_RETRY_INTERVAL.toMillis());
                }
                if (server.isRunning()) {
                    unavailable(response, callback, FRONTEND_UNAVAILABLE);
                } else {
                    callback.succeeded();
                }
            } catch (Throwable e) {
                if (server.isRunning()) {
                    callback.failed(e);
                } else {
                    callback.succeeded();
                }
            }
        });
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String origin(URI target) {
        return target.getScheme() + "://" + target.getAuthority();
    }

    private static String publicLocation(String location, int publicPort, URI fluxzeroTarget, URI frontendTarget,
                                         List<String> backendPaths) {
        String publicUrl = "http://localhost:" + publicPort;
        if (matchesTarget(location, fluxzeroTarget)) {
            String targetPath = location.substring(fluxzeroTarget.toString().length());
            boolean passthrough = backendPaths.stream().anyMatch(path -> matchesPath(targetPath, path));
            return publicUrl + (passthrough ? "" : BACKEND_PREFIX) + targetPath;
        }
        if (matchesTarget(location, frontendTarget)) {
            return publicUrl + location.substring(frontendTarget.toString().length());
        }
        return location;
    }

    private static boolean matchesTarget(String location, URI target) {
        String base = target.toString();
        return location.equals(base) || location.startsWith(base + "/") || location.startsWith(base + "?");
    }

    private static boolean matchesPath(String requestedPath, String configuredPath) {
        int query = requestedPath.indexOf('?');
        int fragment = requestedPath.indexOf('#');
        int end = query < 0 ? requestedPath.length() : query;
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        String path = requestedPath.substring(0, end);
        return configuredPath.equals(path) || path.startsWith(configuredPath + "/");
    }

    private enum Route {
        PREFIXED_BACKEND, PASSTHROUGH_BACKEND, FRONTEND
    }

    public static final class GatewayWebSocketBridge extends Session.Listener.AbstractAutoDemanding
            implements WebSocket.Listener {
        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

        private final URI target;
        private final List<String> protocols;
        private final String cookie;
        private final String authorization;
        private final String origin;
        private final CompletableFuture<WebSocket> upstream = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Session downstream;

        private GatewayWebSocketBridge(URI target, List<String> protocols, String cookie, String authorization,
                                       String origin) {
            this.target = websocketUri(target);
            this.protocols = protocols;
            this.cookie = cookie;
            this.authorization = authorization;
            this.origin = origin;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            downstream = session;
            var builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build().newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT);
            if (!protocols.isEmpty()) {
                builder.subprotocols(protocols.getFirst(), protocols.stream().skip(1).toArray(String[]::new));
            }
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            if (origin != null) {
                builder.header("Origin", origin);
            }
            builder.buildAsync(target, this).whenComplete((socket, error) -> {
                if (error == null) {
                    upstream.complete(socket);
                } else {
                    upstream.completeExceptionally(error);
                    closeDownstream(1011, "Could not connect to frontend websocket");
                }
            });
        }

        @Override
        public void onWebSocketText(String message) {
            upstream.thenCompose(socket -> socket.sendText(message, true));
        }

        @Override
        public void onWebSocketBinary(ByteBuffer message, org.eclipse.jetty.websocket.api.Callback callback) {
            ByteBuffer copy = copy(message);
            upstream.thenCompose(socket -> socket.sendBinary(copy, true)).whenComplete((ignored, error) -> {
                if (error == null) {
                    callback.succeed();
                } else {
                    callback.fail(error);
                }
            });
        }

        @Override
        public void onWebSocketPing(ByteBuffer message) {
            ByteBuffer copy = copy(message);
            upstream.thenCompose(socket -> socket.sendPing(copy));
        }

        @Override
        public void onWebSocketPong(ByteBuffer message) {
            ByteBuffer copy = copy(message);
            upstream.thenCompose(socket -> socket.sendPong(copy));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason,
                                     org.eclipse.jetty.websocket.api.Callback callback) {
            closeUpstream(statusCode, reason);
            callback.succeed();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            closeUpstream(1011, cause.getMessage());
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            CompletableFuture<Void> sent = new CompletableFuture<>();
            Session session = downstream;
            if (session == null || !session.isOpen()) {
                sent.completeExceptionally(new IllegalStateException("Gateway websocket is closed"));
            } else {
                session.sendPartialText(data.toString(), last,
                                        org.eclipse.jetty.websocket.api.Callback.from(
                                                () -> sent.complete(null), sent::completeExceptionally));
            }
            return sent.whenComplete((ignored, error) -> webSocket.request(1));
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            CompletableFuture<Void> sent = new CompletableFuture<>();
            Session session = downstream;
            if (session == null || !session.isOpen()) {
                sent.completeExceptionally(new IllegalStateException("Gateway websocket is closed"));
            } else {
                session.sendPartialBinary(copy(data), last,
                                          org.eclipse.jetty.websocket.api.Callback.from(
                                                  () -> sent.complete(null), sent::completeExceptionally));
            }
            return sent.whenComplete((ignored, error) -> webSocket.request(1));
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            Session session = downstream;
            if (session != null && session.isOpen()) {
                session.sendPing(copy(message), org.eclipse.jetty.websocket.api.Callback.NOOP);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            Session session = downstream;
            if (session != null && session.isOpen()) {
                session.sendPong(copy(message), org.eclipse.jetty.websocket.api.Callback.NOOP);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeDownstream(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeDownstream(1011, error.getMessage());
        }

        private void closeUpstream(int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                upstream.thenCompose(socket -> socket.sendClose(statusCode, safeReason(reason)));
            }
        }

        private void closeDownstream(int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                Session session = downstream;
                if (session != null && session.isOpen()) {
                    session.close(statusCode, safeReason(reason), org.eclipse.jetty.websocket.api.Callback.NOOP);
                }
            }
        }

        private static URI websocketUri(URI uri) {
            String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
            return URI.create(scheme + "://" + uri.getRawAuthority() + uri.getRawPath()
                              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }

        private static ByteBuffer copy(ByteBuffer source) {
            ByteBuffer copy = ByteBuffer.allocate(source.remaining());
            copy.put(source.slice()).flip();
            return copy;
        }

        private static String safeReason(String reason) {
            return reason == null ? "" : reason.substring(0, Math.min(reason.length(), 120));
        }
    }
}
