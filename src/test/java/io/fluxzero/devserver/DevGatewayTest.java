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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DevGatewayTest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void usesExplicitPublicPortAndFailsWhenItIsOccupied() throws Exception {
        int requestedPort;
        try (ServerSocket available = new ServerSocket(0)) {
            requestedPort = available.getLocalPort();
        }
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend");
             DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), () -> true,
                                                   FrontendConfig.DEFAULT_BACKEND_PATHS, requestedPort)) {
            assertEquals(requestedPort, gateway.port());
        }

        try (ServerSocket occupied = new ServerSocket();
             TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend")) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            DevServerStartupException exception = assertThrows(
                    DevServerStartupException.class,
                    () -> DevGateway.start(backend.url(), frontend.url(), () -> true,
                                           FrontendConfig.DEFAULT_BACKEND_PATHS, occupied.getLocalPort()));
            assertTrue(exception.getMessage().contains("Port " + occupied.getLocalPort()));
        }
    }

    @Test
    void waitsForARecentlyStoppedGatewayToReleaseItsPort() throws Exception {
        ServerSocket previousGateway = new ServerSocket();
        previousGateway.setReuseAddress(false);
        previousGateway.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = previousGateway.getLocalPort();
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
                previousGateway.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        DevGateway.requireAvailablePort(port, Duration.ofSeconds(2));

        release.get(2, TimeUnit.SECONDS);
    }

    @Test
    void immediatelyReclaimsThePublicPortAfterGatewayShutdown() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend")) {
            DevGateway first = DevGateway.start(backend.url(), frontend.url(), () -> true,
                                                FrontendConfig.DEFAULT_BACKEND_PATHS, port);
            first.close();

            try (DevGateway restarted = DevGateway.start(backend.url(), frontend.url(), () -> true,
                                                         FrontendConfig.DEFAULT_BACKEND_PATHS, port)) {
                assertEquals(port, restarted.port());
            }
        }
    }

    @Test
    void rejectsAPortThatRemainsOccupiedAfterTheReleaseGrace() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));

            DevServerStartupException exception = assertThrows(
                    DevServerStartupException.class,
                    () -> DevGateway.requireAvailablePort(occupied.getLocalPort(), Duration.ofMillis(100)));

            assertTrue(exception.getMessage().contains("Port " + occupied.getLocalPort()));
        }
    }

    @Test
    void exposesOneOriginAndRoutesFrontendAndFluxzeroRequests() throws Exception {
        AtomicBoolean frontendReady = new AtomicBoolean();
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend");
             DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), frontendReady::get)) {
            HttpResponse<String> unavailable = get(gateway.url() + "/dashboard");
            assertEquals(503, unavailable.statusCode());
            assertTrue(unavailable.body().contains("not ready"));

            HttpResponse<String> backendResponse = post(
                    gateway.url() + DevGateway.BACKEND_PREFIX + "/orders?limit=2", "payload");
            assertEquals(200, backendResponse.statusCode());
            assertEquals("backend POST /orders?limit=2 payload", backendResponse.body());
            assertEquals("session=backend; Path=/", backendResponse.headers().firstValue("set-cookie").orElseThrow());
            assertEquals(gateway.backendUrl() + "/destination",
                         get(gateway.backendUrl() + "/redirect").headers().firstValue("location").orElseThrow());
            HttpResponse<String> apiResponse = post(gateway.url() + "/api/orders?limit=3", "api-payload");
            assertEquals(200, apiResponse.statusCode());
            assertEquals("backend POST /api/orders?limit=3 api-payload", apiResponse.body());
            assertEquals(gateway.url() + "/api/destination",
                         get(gateway.url() + "/api/redirect").headers().firstValue("location").orElseThrow());

            frontendReady.set(true);
            HttpResponse<String> frontendResponse = get(gateway.url() + "/assets/main.js?v=1");
            assertEquals(200, frontendResponse.statusCode());
            assertEquals("frontend GET /assets/main.js?v=1 ", frontendResponse.body());
            assertEquals(gateway.url() + "/destination",
                         get(gateway.url() + "/redirect").headers().firstValue("location").orElseThrow());
            assertEquals("frontend GET /apiary ", get(gateway.url() + "/apiary").body());

            frontendReady.set(false);
            CompletableFuture<HttpResponse<String>> reload = CompletableFuture.supplyAsync(() -> {
                try {
                    return get(gateway.url() + "/dashboard");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(100);
            assertTrue(!reload.isDone(), "reload should wait for the known frontend to recover");
            frontendReady.set(true);
            HttpResponse<String> recovered = reload.get(2, TimeUnit.SECONDS);
            assertEquals(200, recovered.statusCode());
            assertEquals("frontend GET /dashboard ", recovered.body());
        }
    }

    @Test
    void rejectsApplicationTrafficUntilBackendIsReady() throws Exception {
        AtomicBoolean backendReady = new AtomicBoolean();
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend");
             DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), () -> true,
                                                   backendReady::get, FrontendConfig.DEFAULT_BACKEND_PATHS, 0)) {
            HttpResponse<String> unavailable = post(gateway.url() + "/api/orders", "payload");
            assertEquals(503, unavailable.statusCode());
            assertTrue(unavailable.body().contains("backend is not ready"));
            assertEquals("1", unavailable.headers().firstValue("retry-after").orElseThrow());

            // Fluxzero infrastructure remains reachable for managed IDP and health endpoints.
            assertEquals(200, get(gateway.backendUrl() + "/proxy/health").statusCode());
            assertEquals(200, get(gateway.url() + "/").statusCode());

            CompletionException websocketFailure = assertThrows(CompletionException.class, () ->
                    HTTP_CLIENT.newWebSocketBuilder()
                            .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + "/api/updates"),
                                        new TextListener(new CompletableFuture<>()))
                            .orTimeout(2, TimeUnit.SECONDS)
                            .join());
            WebSocketHandshakeException handshake = assertInstanceOf(
                    WebSocketHandshakeException.class, websocketFailure.getCause());
            assertEquals(503, handshake.getResponse().statusCode());

            backendReady.set(true);
            assertEquals("backend POST /api/orders payload",
                         post(gateway.url() + "/api/orders", "payload").body());

            backendReady.set(false);
            assertEquals(503, post(gateway.url() + "/api/orders", "payload").statusCode());
        }
    }

    @Test
    void bridgesFrontendHmrWebsocketIncludingSubprotocol() throws Exception {
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend");
             DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), () -> true)) {
            CompletableFuture<String> response = new CompletableFuture<>();
            WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                    .subprotocols("vite-hmr")
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + "/hmr"),
                                new TextListener(response))
                    .get(5, TimeUnit.SECONDS);

            assertEquals("vite-hmr", socket.getSubprotocol());
            socket.sendText("ping", true).get(5, TimeUnit.SECONDS);
            assertEquals("frontend:/hmr:ping", response.get(5, TimeUnit.SECONDS));
            assertEquals(frontend.url(), frontend.lastWebsocketOrigin());
            assertEquals("127.0.0.1:" + frontend.port(), frontend.lastWebsocketHost());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

            CompletableFuture<String> backendResponse = new CompletableFuture<>();
            WebSocket backendSocket = HTTP_CLIENT.newWebSocketBuilder()
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://")
                                           + DevGateway.BACKEND_PREFIX + "/socket"),
                                new TextListener(backendResponse))
                    .get(5, TimeUnit.SECONDS);
            backendSocket.sendText("backend-ping", true).get(5, TimeUnit.SECONDS);
            assertEquals("backend:/socket:backend-ping", backendResponse.get(5, TimeUnit.SECONDS));
            backendSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

            CompletableFuture<String> apiResponse = new CompletableFuture<>();
            WebSocket apiSocket = HTTP_CLIENT.newWebSocketBuilder()
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + "/api/socket"),
                                new TextListener(apiResponse))
                    .get(5, TimeUnit.SECONDS);
            apiSocket.sendText("api-ping", true).get(5, TimeUnit.SECONDS);
            assertEquals("backend:/api/socket:api-ping", apiResponse.get(5, TimeUnit.SECONDS));
            apiSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopsImmediatelyWhileFrontendWebsocketIsStillOpen() throws Exception {
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend")) {
            DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), () -> true);
            CompletableFuture<Integer> closed = new CompletableFuture<>();
            HTTP_CLIENT.newWebSocketBuilder()
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + "/hmr"),
                                new WebSocket.Listener() {
                                    @Override
                                    public void onOpen(WebSocket webSocket) {
                                        webSocket.request(1);
                                    }

                                    @Override
                                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode,
                                                                      String reason) {
                                        closed.complete(statusCode);
                                        return CompletableFuture.completedFuture(null);
                                    }
                                })
                    .get(5, TimeUnit.SECONDS);

            long started = System.nanoTime();
            gateway.close();

            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
            closed.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopsImmediatelyWhileFrontendReloadRequestIsWaiting() throws Exception {
        AtomicBoolean frontendReady = new AtomicBoolean(true);
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend")) {
            DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), frontendReady::get);
            assertEquals(200, get(gateway.url() + "/").statusCode());
            frontendReady.set(false);
            CompletableFuture<HttpResponse<String>> reload = HTTP_CLIENT.sendAsync(
                    HttpRequest.newBuilder(URI.create(gateway.url() + "/reload")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(100);
            assertTrue(!reload.isDone());

            long started = System.nanoTime();
            gateway.close();

            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
            reload.cancel(true);
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                                HttpResponse.BodyHandlers.ofString());
    }

    private record TextListener(CompletableFuture<String> response) implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            response.complete(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class TestUpstream implements AutoCloseable {
        private final String name;
        private final Server server;
        private final int port;
        private final java.util.concurrent.atomic.AtomicReference<String> lastWebsocketOrigin;
        private final java.util.concurrent.atomic.AtomicReference<String> lastWebsocketHost;

        private TestUpstream(String name, Server server, int port,
                             java.util.concurrent.atomic.AtomicReference<String> lastWebsocketOrigin,
                             java.util.concurrent.atomic.AtomicReference<String> lastWebsocketHost) {
            this.name = name;
            this.server = server;
            this.port = port;
            this.lastWebsocketOrigin = lastWebsocketOrigin;
            this.lastWebsocketHost = lastWebsocketHost;
        }

        static TestUpstream start(String name) throws Exception {
            Server server = new Server();
            java.util.concurrent.atomic.AtomicReference<String> websocketOrigin =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> websocketHost =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ServerConnector connector = new ServerConnector(server);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            ContextHandler context = new ContextHandler("/");
            WebSocketUpgradeHandler websocket = WebSocketUpgradeHandler.from(server, context, container ->
                    container.addMapping("/*", (request, response, callback) -> {
                        websocketOrigin.set(request.getHeaders().get("Origin"));
                        websocketHost.set(request.getHeaders().get("Host"));
                        if (!request.getSubProtocols().isEmpty()) {
                            response.setAcceptedSubProtocol(request.getSubProtocols().getFirst());
                        }
                        return new EchoSocket(name, request.getHttpURI().getPath());
                    }));
            websocket.setHandler(new Handler.Abstract() {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception {
                    if (request.getHttpURI().getPath().endsWith("/redirect")) {
                        response.setStatus(302);
                        String destination = request.getHttpURI().getPath()
                                .substring(0, request.getHttpURI().getPath().length() - "/redirect".length())
                                             + "/destination";
                        response.getHeaders().put(HttpHeader.LOCATION,
                                                  "http://127.0.0.1:" + connector.getLocalPort() + destination);
                        callback.succeeded();
                        return true;
                    }
                    String body = name + " " + request.getMethod() + " " + request.getHttpURI().getPathQuery()
                                  + " " + new String(Request.asInputStream(request).readAllBytes(),
                                                     StandardCharsets.UTF_8);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                    response.getHeaders().add(HttpHeader.SET_COOKIE, "session=" + name + "; Path=/");
                    response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
                    return true;
                }
            });
            context.setHandler(websocket);
            server.setHandler(context);
            server.start();
            return new TestUpstream(name, server, connector.getLocalPort(), websocketOrigin, websocketHost);
        }

        String url() {
            return "http://127.0.0.1:" + port;
        }

        int port() {
            return port;
        }

        String lastWebsocketOrigin() {
            return lastWebsocketOrigin.get();
        }

        String lastWebsocketHost() {
            return lastWebsocketHost.get();
        }

        @Override
        public void close() throws Exception {
            server.stop();
        }
    }

    public static final class EchoSocket extends Session.Listener.AbstractAutoDemanding {
        private final String name;
        private final String path;
        private volatile Session session;

        private EchoSocket(String name, String path) {
            this.name = name;
            this.path = path;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            this.session = session;
        }

        @Override
        public void onWebSocketText(String message) {
            session.sendText(name + ":" + path + ":" + message,
                             org.eclipse.jetty.websocket.api.Callback.NOOP);
        }
    }
}
