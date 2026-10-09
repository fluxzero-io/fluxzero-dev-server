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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DevGatewayTest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void servesConsoleBeforeAppsAreReadyAndIsolatesMonitoringApi() throws Exception {
        java.nio.file.Path assets = java.nio.file.Files.createTempDirectory("auditlog-ui-test");
        java.nio.file.Files.writeString(assets.resolve("index.html"), "<html><head><base href=\"/\"></head>Auditlog</html>");
        var registry = new DevEnvironmentRegistry(assets.resolve("registry"));
        var project = assets.resolve("example");
        var session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                .withGateway(DevSession.ServiceStatus.running("gateway", "http://localhost:4200", 4200, null, "public")
                        .withMetadata(java.util.Map.of(DevConsole.CAPABILITY, "1")));
        new DevSessionStore(project).writeSession(session);
        registry.register(session);
        var console = new DevConsole(() -> java.util.Map.of("project", "example", "monitoring", java.util.Map.of("enabled", true)), assets, registry);
        try (TestUpstream backend = TestUpstream.start("backend");
             DevGateway gateway = DevGateway.start(backend.url(), List.of(new DevGateway.FrontendRoute(
                     "application", "/", backend.url(), () -> false)), () -> false, List.of("/api"), 0, () -> { }, true, console)) {
            var root = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(gateway.url() + DevConsole.ROOT)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("<dev-root>"));
            var known = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(gateway.url() + DevConsole.ROOT + "environments.json")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, known.statusCode());
            assertTrue(known.body().contains("http://localhost:4200/_fluxzero/dev/"));
            assertFalse(known.body().contains("mcp"));
            var script = java.util.regex.Pattern.compile("src=\"(main-[^\"]+\\.js)\"").matcher(root.body());
            assertTrue(script.find(), "Angular entrypoint must be packaged in the standalone console");
            var bundle = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(gateway.url() + DevConsole.ROOT + script.group(1))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, bundle.statusCode());
            assertTrue(bundle.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
            var ui = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(gateway.url() + DevConsole.MONITORING + "messages")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ui.statusCode());
            assertTrue(ui.body().contains("apiBase:'" + DevConsole.API));
            var api = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(gateway.url() + DevConsole.API + "/logs/search"))
                    .header(DevNamespaceHeader.NAME, "application-namespace").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, api.statusCode());
            assertEquals(DevConsole.NAMESPACE, api.headers().firstValue("X-Received-Namespace").orElseThrow());
            assertTrue(api.body().contains("/logs/search"));
            CompletableFuture<String> echoed = new CompletableFuture<>();
            WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                    .header(DevNamespaceHeader.NAME, "application-namespace")
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + DevConsole.API + "/socket"),
                            new TextListener(echoed)).get(5, TimeUnit.SECONDS);
            socket.sendText("monitoring-ping", true).get(5, TimeUnit.SECONDS);
            assertEquals("backend:/socket:monitoring-ping", echoed.get(5, TimeUnit.SECONDS));
            assertEquals(DevConsole.NAMESPACE, backend.lastWebsocketNamespace());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            var missing = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(gateway.url() + DevConsole.MONITORING + "missing.js")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            HttpResponse<String> unavailable = request(gateway.url() + "/app-page", "text/html", null);
            assertEquals(503, unavailable.statusCode());
            assertTrue(unavailable.body().contains("Fluxzero Dev Server"));
            assertTrue(unavailable.body().contains("href=\"" + DevConsole.ROOT + "#monitoring/issues\""));
            assertEquals("1", unavailable.headers().firstValue("retry-after").orElseThrow());
            HttpResponse<String> backendUnavailable = request(gateway.url() + "/api/orders", "text/html", null);
            assertEquals(503, backendUnavailable.statusCode());
            assertEquals("Fluxzero backend is not ready yet", backendUnavailable.body());
            assertEquals("text/plain; charset=utf-8", backendUnavailable.headers().firstValue("content-type").orElseThrow());
            assertEquals("Fluxzero backend is not ready yet",
                    request(gateway.url() + "/api/orders", "application/json", null).body());
            assertEquals(404, request(gateway.url() + DevConsole.MONITORING + "missing.js", "text/html", null).statusCode());
            HttpResponse<String> missingPage = request(gateway.url() + DevConsole.ROOT + "unknown", "text/html", null);
            assertEquals(404, missingPage.statusCode());
            assertTrue(missingPage.body().contains("Fluxzero Dev Server"));
        }
    }

    @Test
    void projectActionsRequireSameOriginAndOnlyAffectKnownStoppedProjects(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var project = directory.resolve("project with spaces");
        var session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running");
        var store = new DevSessionStore(project);
        store.writeSession(session);
        registry.register(session);
        var opened = new AtomicReference<java.nio.file.Path>();
        var console = new DevConsole(() -> java.util.Map.of("projectDirectory", project.toString()), null, registry, opened::set);
        try (TestUpstream backend = TestUpstream.start("backend");
             DevGateway gateway = DevGateway.start(backend.url(), List.of(new DevGateway.FrontendRoute("application", "/", backend.url(), () -> false)), () -> false, List.of("/api"), 0, () -> {}, true, console)) {
            String base = gateway.url();
            String projectUrl = base + DevConsole.ROOT + "projects/" + registry.listKnown().getFirst().id();
            assertEquals(405, HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(projectUrl + "/open-folder")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, projectPost(projectUrl + "/open-folder", null, true).statusCode());
            assertEquals(403, projectPost(projectUrl + "/open-folder", "https://example.com", true).statusCode());
            assertEquals(403, projectPost(projectUrl + "/open-folder", base, false).statusCode());
            assertEquals(null, opened.get());
            assertEquals(404, projectPost(base + DevConsole.ROOT + "projects/" + "0".repeat(64) + "/open-folder", base, true).statusCode());
            assertEquals(204, projectPost(projectUrl + "/open-folder", base, true).statusCode());
            assertEquals(project.toRealPath(), opened.get());
            assertEquals(409, projectPost(projectUrl + "/forget", base, true).statusCode());
            assertEquals(403, projectPost(projectUrl + "/stop", "https://example.com", true).statusCode());
            assertEquals(409, projectPost(projectUrl + "/stop", base, true).statusCode());
            assertEquals(404, projectPost(base + DevConsole.ROOT + "projects/" + "0".repeat(64) + "/stop", base, true).statusCode());
            store.writeSession(session.withStatus("stopped"));
            assertEquals(204, projectPost(projectUrl + "/forget", base, true).statusCode());
            assertTrue(registry.listKnown().isEmpty());
            assertTrue(java.nio.file.Files.isDirectory(project));
            assertEquals("stopped", store.readSession().orElseThrow().status());
        }
    }

    @Test
    void projectFolderDiscoveryAndImportRequireLocalOrigin(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var project = java.nio.file.Files.createDirectory(directory.resolve("app"));
        java.nio.file.Files.writeString(project.resolve("pom.xml"), "original");
        var console = new DevConsole(java.util.Map::of, null, registry);
        try (TestUpstream backend = TestUpstream.start("backend");
             DevGateway gateway = DevGateway.start(backend.url(), List.of(new DevGateway.FrontendRoute("application", "/", backend.url(), () -> false)), () -> false, List.of("/api"), 0, () -> {}, true, console)) {
            String url = gateway.url() + DevConsole.ROOT + "projects/";
            for (String operation : List.of("folders", "open", "create")) {
                assertEquals(403, projectPost(url + operation, "https://example.com", true).statusCode());
                assertEquals(403, projectPost(url + operation, gateway.url(), false).statusCode());
            }
            String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("path", project.toString()));
            var result = HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url + "open"))
                    .header("Origin", gateway.url()).header("X-Fluxzero-Console", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode());
            assertEquals(1, registry.listKnown().size());
            assertEquals("original", java.nio.file.Files.readString(project.resolve("pom.xml")));
        }
    }

    @Test
    void renamesKnownProjectsThroughProtectedBoundedJsonEndpoint(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var project = directory.resolve("orders");
        registry.register(DevSession.empty(DevServerConfig.defaults(project)).withStatus("stopped"));
        var known = registry.listKnown().getFirst();
        var console = new DevConsole(java.util.Map::of, null, registry);
        try (TestUpstream backend = TestUpstream.start("backend");
             DevGateway gateway = DevGateway.start(backend.url(), List.of(new DevGateway.FrontendRoute("application", "/", backend.url(), () -> false)), () -> false, List.of("/api"), 0, () -> {}, true, console)) {
            String url = gateway.url() + DevConsole.ROOT + "projects/" + known.id() + "/rename";
            assertEquals(405, HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, projectPost(url, "https://example.com", true).statusCode());
            assertEquals(403, projectPost(url, gateway.url(), false).statusCode());
            assertEquals(400, projectPost(url, gateway.url(), true).statusCode());
            for (String invalid : List.of("{", "{}", "{\"name\":123}", "{\"name\":\"" + "x".repeat(101) + "\"}")) {
                assertEquals(400, renamePost(url, gateway.url(), invalid).statusCode());
            }
            assertEquals(413, renamePost(url, gateway.url(), " ".repeat(4097)).statusCode());
            var response = renamePost(url, gateway.url(), "{\"name\":\"Orders preview\"}");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Orders preview"));
            assertEquals("Orders preview", registry.findKnown(known.id()).orElseThrow().projectName());
            assertEquals(200, renamePost(url, gateway.url(), "{\"name\":\"\"}").statusCode());
            assertEquals("orders", registry.findKnown(known.id()).orElseThrow().projectName());
            assertEquals(403, projectPost(url.replace("/rename", "/start"), "https://example.com", true).statusCode());
            assertEquals(404, projectPost(url.replace("/rename", "/start"), gateway.url(), true).statusCode(), "Cannot start a missing folder");
        }
    }

    private static HttpResponse<String> renamePost(String url, String origin, String body) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url)).header("Origin", origin)
                .header("X-Fluxzero-Console", "1").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void maintenanceRequiresSameOriginPostAndReturnsConflictForBusyAction() throws Exception {
        var called = new AtomicReference<String>();
        var console = new DevConsole(java.util.Map::of, null).withMaintenance(action -> {
            if (called.get() != null) throw new IllegalStateException("Maintenance is already running.");
            called.set(action);
        });
        try (TestUpstream backend = TestUpstream.start("backend");
             DevGateway gateway = DevGateway.start(backend.url(), List.of(new DevGateway.FrontendRoute("application", "/", backend.url(), () -> false)), () -> false, List.of("/api"), 0, () -> {}, true, console)) {
            String base = gateway.url(), url = base + DevConsole.ROOT + "actions/truncate-testserver-data";
            assertEquals(405, HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, projectPost(url, null, true).statusCode());
            assertEquals(403, projectPost(url, "https://example.com", true).statusCode());
            assertEquals(403, projectPost(url, base, false).statusCode());
            assertEquals(null, called.get());
            assertEquals(404, projectPost(base + DevConsole.ROOT + "actions/unknown", base, true).statusCode());
            assertEquals(202, projectPost(url, base, true).statusCode());
            assertEquals("truncate-testserver-data", called.get());
            assertEquals(409, projectPost(url, base, true).statusCode());
            called.set(null);
            String update = base + DevConsole.ROOT + "actions/update-devserver";
            assertEquals(403, projectPost(update, "https://example.com", true).statusCode());
            for (String body : List.of("{}", "{", "{\"version\":\"../../server.jar\"}")) {
                assertEquals(400, renamePost(update, base, body).statusCode());
                assertEquals(null, called.get());
            }
            assertEquals(413, renamePost(update, base, " ".repeat(1025)).statusCode());
            assertEquals(202, renamePost(update, base, "{\"version\":\"1.99.0\"}").statusCode());
            assertEquals("update-devserver:1.99.0", called.get());
            for (String action : List.of("pause-tests", "resume-tests", "run-startup-commands")) {
                called.set(null);
                String testAction = base + DevConsole.ROOT + "actions/" + action;
                assertEquals(403, projectPost(testAction, "https://example.com", true).statusCode());
                assertEquals(202, projectPost(testAction, base, true).statusCode());
                assertEquals(action, called.get());
            }
        }
    }

    private static HttpResponse<String> projectPost(String url, String origin, boolean consoleHeader) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody());
        if (origin != null) request.header("Origin", origin);
        if (consoleHeader) request.header("X-Fluxzero-Console", "1");
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void failsWhenExplicitPublicPortIsOccupied() throws Exception {
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
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend")) {
            DevGateway first = DevGateway.start(backend.url(), frontend.url(), () -> true,
                                                FrontendConfig.DEFAULT_BACKEND_PATHS, 0);
            int port = first.port();
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
    void rewritesPrivateLoopbackAliasesToIngressWithoutChangingExternalRedirects() throws Exception {
        var location = new AtomicReference<String>();
        var upstream = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", location.get());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        upstream.start();
        String target = "http://127.0.0.1:" + upstream.getAddress().getPort();
        String publicOrigin = "https://application.local:8443";
        try (var endpoint = DevGateway.reserve(0, "127.0.0.1");
             var gateway = DevGateway.start(null,
                     List.of(new DevGateway.FrontendRoute("root", "/", target, () -> true)), () -> false,
                     List.of(), endpoint, () -> {}, false, null, publicOrigin)) {
            for (String host : List.of("127.0.0.1", "localhost", "[::1]")) {
                for (String scheme : List.of("http:", "")) {
                    location.set(scheme + "//" + host + ":" + upstream.getAddress().getPort() + "/inbox?q=1#message");
                    assertEquals(publicOrigin + "/inbox?q=1#message",
                            get(gateway.url()).headers().firstValue("location").orElseThrow());
                }
            }
            for (String external : List.of("https://login.example/", "http://localhost:1/", "/inbox")) {
                location.set(external);
                assertEquals(external, get(gateway.url()).headers().firstValue("location").orElseThrow());
            }
        } finally { upstream.stop(0); }
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
    void rendersSafeBrowserPageForLocalGatewayException() throws Exception {
        String secret = "private-error-marker";
        try (TestUpstream frontend = TestUpstream.start("frontend")) {
            for (String applicationOrigin : List.of("", "https://application.local")) {
                try (var endpoint = DevGateway.reserve(0, "127.0.0.1");
                     DevGateway gateway = DevGateway.start(null,
                             List.of(new DevGateway.FrontendRoute("root", "/", frontend.url(), () -> true)),
                             () -> false, List.of(), endpoint,
                             () -> { throw new IllegalStateException(secret); }, false, null,
                             applicationOrigin.isEmpty() ? null : applicationOrigin)) {
                    HttpResponse<String> browser = request(gateway.url() + "/orders?token=private-query-marker",
                            "text/html,application/xhtml+xml;q=0.9", "private-header-marker");
                    assertEquals(500, browser.statusCode());
                    assertEquals("no-store", browser.headers().firstValue("cache-control").orElseThrow());
                    assertTrue(browser.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
                    assertTrue(browser.body().contains("<main>"));
                    assertTrue(browser.body().contains("Fluxzero Dev Server"));
                    assertTrue(browser.body().contains("HTTP 500"));
                    assertTrue(browser.body().contains("href=\"\">Retry"));
                    assertTrue(browser.body().contains("prefers-color-scheme:dark"));
                    assertTrue(browser.body().contains("focus-visible"));
                    for (String sensitive : List.of(secret, "private-query-marker", "private-header-marker",
                                                    frontend.url(), "Powered by Jetty", "stacktrace")) {
                        assertFalse(browser.body().contains(sensitive), sensitive);
                    }
                    HttpResponse<String> api = request(gateway.url() + "/orders?token=private-query-marker",
                            "application/json", "private-header-marker");
                    assertEquals(500, api.statusCode());
                    assertEquals("application/json; charset=utf-8", api.headers().firstValue("content-type").orElseThrow());
                    assertEquals("{\"status\":500,\"error\":\"Internal Server Error\"}", api.body());
                }
            }
        }
    }

    @Test
    void rendersUnreachableUpstreamWithoutExposingItsOriginAndKeepsAssetResponsePlain() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) { unavailablePort = socket.getLocalPort(); }
        String privateOrigin = "http://127.0.0.1:" + unavailablePort;
        for (String applicationOrigin : List.of("", "https://application.local")) {
            try (var endpoint = DevGateway.reserve(0, "127.0.0.1");
                 DevGateway gateway = DevGateway.start(null,
                         List.of(new DevGateway.FrontendRoute("root", "/", privateOrigin, () -> true)),
                         () -> false, List.of(), endpoint, () -> { }, false, null,
                         applicationOrigin.isEmpty() ? null : applicationOrigin)) {
                HttpResponse<String> browser = request(gateway.url() + "/missing?token=private-query-marker",
                        "text/html", "private-header-marker");
                assertTrue(browser.statusCode() >= 500);
                assertTrue(browser.body().contains("Fluxzero Dev Server"));
                assertTrue(browser.body().contains("HTTP " + browser.statusCode()));
                assertFalse(browser.body().contains(privateOrigin));
                assertFalse(browser.body().contains("private-query-marker"));
                assertFalse(browser.body().contains("Powered by Jetty"));

                HttpResponse<String> asset = request(gateway.url() + "/assets/main.js", "text/html", null);
                assertTrue(asset.statusCode() >= 500);
                assertEquals("text/plain; charset=utf-8", asset.headers().firstValue("content-type").orElseThrow());
                assertFalse(asset.body().contains("<html"));
            }
        }
    }

    @Test
    void rendersOnlyGatewayOwnedUnknownRoutesInIngressMode() throws Exception {
        try (TestUpstream frontend = TestUpstream.start("frontend");
             var endpoint = DevGateway.reserve(0, "127.0.0.1");
             DevGateway gateway = DevGateway.start(null,
                     List.of(new DevGateway.FrontendRoute("root", "/", frontend.url(), () -> true)),
                     () -> false, List.of(), endpoint, () -> { }, false, null, "https://application.local")) {
            HttpResponse<String> browser = request(gateway.url() + DevConsole.ROOT + "missing?secret=abc", "text/html", null);
            assertEquals(404, browser.statusCode());
            assertTrue(browser.body().contains("HTTP 404"));
            assertFalse(browser.body().contains("secret=abc"));
            HttpResponse<String> plain = request(gateway.url() + DevConsole.ROOT + "missing", "application/json", null);
            assertEquals(404, plain.statusCode());
            assertEquals("", plain.body());
            assertEquals("no-store", plain.headers().firstValue("cache-control").orElseThrow());
        }
    }

    @Test
    void leavesIntentionalUpstreamErrorsUntouched() throws Exception {
        var upstream = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "application-owned-error".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("X-Upstream-Error", "present");
            exchange.sendResponseHeaders(418, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        try (DevGateway gateway = DevGateway.start(null,
                List.of(new DevGateway.FrontendRoute("root", "/",
                        "http://127.0.0.1:" + upstream.getAddress().getPort(), () -> true)),
                () -> false, List.of(), 0, () -> { }, false)) {
            HttpResponse<String> response = request(gateway.url() + "/intentional", "text/html", null);
            assertEquals(418, response.statusCode());
            assertEquals("application-owned-error", response.body());
            assertEquals("present", response.headers().firstValue("x-upstream-error").orElseThrow());
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void routesAllTrafficToFrontendWhenLocalBackendIsDisabled() throws Exception {
        try (TestUpstream frontend = TestUpstream.start("frontend");
             DevGateway gateway = DevGateway.start(
                     null,
                     List.of(new DevGateway.FrontendRoute("frontend", "/", frontend.url(), () -> true)),
                     () -> false, List.of("/api"), 0, () -> {
                     }, false)) {
            assertEquals(null, gateway.backendUrl());
            assertEquals("frontend POST /api/query payload",
                         post(gateway.url() + "/api/query", "payload").body());
            assertEquals("frontend GET /_fluxzero/proxy/health ",
                         get(gateway.url() + "/_fluxzero/proxy/health").body());
        }
    }

    @Test
    void routesHttpAndWebsocketsToTheLongestMatchingFrontendPath() throws Exception {
        AtomicBoolean auditlogReady = new AtomicBoolean();
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream dashboard = TestUpstream.start("dashboard");
             TestUpstream auditlog = TestUpstream.start("auditlog");
             DevGateway gateway = DevGateway.start(
                     backend.url(),
                     List.of(new DevGateway.FrontendRoute("dashboard", "/", dashboard.url(), () -> true),
                             new DevGateway.FrontendRoute(
                                     "auditlog", "/marketplace/logs/1", auditlog.url(), auditlogReady::get)),
                     () -> true, List.of("/api", "/logs"), 0, () -> {
                     })) {
            assertEquals("dashboard GET / ", get(gateway.url() + "/").body());
            assertEquals("dashboard GET /marketplace/logs/10 ",
                         get(gateway.url() + "/marketplace/logs/10").body());
            assertEquals(503, get(gateway.url() + "/marketplace/logs/1/assets/main.js").statusCode());

            auditlogReady.set(true);
            assertEquals("auditlog GET /marketplace/logs/1/assets/main.js ",
                         get(gateway.url() + "/marketplace/logs/1/assets/main.js").body());
            assertEquals("backend POST /api/query payload",
                         post(gateway.url() + "/api/query", "payload").body());
            assertEquals("backend POST /logs/search query",
                         post(gateway.url() + "/logs/search", "query").body());
            HttpResponse<String> namespaced = HTTP_CLIENT.send(
                    HttpRequest.newBuilder(URI.create(gateway.url() + "/logs/search"))
                            .header(DevNamespaceHeader.NAME, DevNamespaceHeaderTest.jwt("fluxzero_mp_prod-logs"))
                            .POST(HttpRequest.BodyPublishers.ofString("query")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("fluxzero_mp_prod-logs",
                         namespaced.headers().firstValue("X-Received-Namespace").orElseThrow());

            CompletableFuture<String> response = new CompletableFuture<>();
            WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://")
                                           + "/marketplace/logs/1/hmr"), new TextListener(response))
                    .get(5, TimeUnit.SECONDS);
            socket.sendText("refresh", true).get(5, TimeUnit.SECONDS);
            assertEquals("auditlog:/marketplace/logs/1/hmr:refresh", response.get(5, TimeUnit.SECONDS));
            assertEquals(auditlog.url(), auditlog.lastWebsocketOrigin());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
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
                    .header(DevNamespaceHeader.NAME, DevNamespaceHeaderTest.jwt("fluxzero_mp_prod-logs"))
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://")
                                           + DevGateway.BACKEND_PREFIX + "/socket"),
                                new TextListener(backendResponse))
                    .get(5, TimeUnit.SECONDS);
            backendSocket.sendText("backend-ping", true).get(5, TimeUnit.SECONDS);
            assertEquals("backend:/socket:backend-ping", backendResponse.get(5, TimeUnit.SECONDS));
            assertEquals("fluxzero_mp_prod-logs", backend.lastWebsocketNamespace());
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
    void bridgesWebsocketDataControlAndCloseFramesInBothDirections() throws Exception {
        try (TestUpstream backend = TestUpstream.start("backend");
             TestUpstream frontend = TestUpstream.start("frontend");
             DevGateway gateway = DevGateway.start(backend.url(), frontend.url(), () -> true)) {
            FrameListener listener = new FrameListener();
            WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + "/frames"), listener)
                    .get(5, TimeUnit.SECONDS);

            socket.sendText("text-frame", true).get(5, TimeUnit.SECONDS);
            assertEquals("frontend:/frames:text-frame", listener.text.get(5, TimeUnit.SECONDS));

            socket.sendBinary(utf8("binary-frame"), true).get(5, TimeUnit.SECONDS);
            assertEquals("binary-frame", listener.binary.get(5, TimeUnit.SECONDS));

            socket.sendPing(utf8("downstream-ping")).get(5, TimeUnit.SECONDS);
            assertEquals("downstream-ping", frontend.awaitPing());
            assertEquals("downstream-ping", listener.pong.get(5, TimeUnit.SECONDS));

            frontend.sendPing("upstream-ping");
            assertEquals("upstream-ping", listener.ping.get(5, TimeUnit.SECONDS));
            assertEquals("upstream-ping", frontend.awaitPong());

            socket.sendClose(4000, "downstream-close").get(5, TimeUnit.SECONDS);
            assertEquals(new CloseFrame(4000, "downstream-close"), frontend.awaitClose());

            FrameListener upstreamClose = new FrameListener();
            HTTP_CLIENT.newWebSocketBuilder()
                    .buildAsync(URI.create(gateway.url().replace("http://", "ws://") + "/upstream-close"),
                                upstreamClose)
                    .get(5, TimeUnit.SECONDS);
            frontend.closeWebsocket(4001, "upstream-close");
            assertEquals(new CloseFrame(4001, "upstream-close"),
                         upstreamClose.close.get(5, TimeUnit.SECONDS));
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

    private static HttpResponse<String> request(String url, String accept, String marker) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Accept", accept);
        if (marker != null) builder.header("X-Private-Marker", marker);
        return HTTP_CLIENT.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
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

    private static final class FrameListener implements WebSocket.Listener {
        private final CompletableFuture<String> text = new CompletableFuture<>();
        private final CompletableFuture<String> binary = new CompletableFuture<>();
        private final CompletableFuture<String> ping = new CompletableFuture<>();
        private final CompletableFuture<String> pong = new CompletableFuture<>();
        private final CompletableFuture<CloseFrame> close = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.complete(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            binary.complete(string(data));
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            ByteBuffer copy = copy(message);
            ping.complete(string(message));
            return webSocket.sendPong(copy).whenComplete((ignored, error) -> webSocket.request(1));
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pong.complete(string(message));
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            close.complete(new CloseFrame(statusCode, reason));
            return CompletableFuture.completedFuture(null);
        }
    }

    private record CloseFrame(int statusCode, String reason) {
    }

    private static ByteBuffer utf8(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(ByteBuffer value) {
        return StandardCharsets.UTF_8.decode(value.slice()).toString();
    }

    private static ByteBuffer copy(ByteBuffer value) {
        ByteBuffer result = ByteBuffer.allocate(value.remaining());
        result.put(value.slice()).flip();
        return result;
    }

    private static final class TestUpstream implements AutoCloseable {
        private final String name;
        private final Server server;
        private final int port;
        private final java.util.concurrent.atomic.AtomicReference<String> lastWebsocketOrigin;
        private final java.util.concurrent.atomic.AtomicReference<String> lastWebsocketHost;
        private final java.util.concurrent.atomic.AtomicReference<String> lastWebsocketNamespace;
        private final AtomicReference<Session> activeWebsocket;
        private final LinkedBlockingQueue<String> websocketPings;
        private final LinkedBlockingQueue<String> websocketPongs;
        private final LinkedBlockingQueue<CloseFrame> websocketCloses;

        private TestUpstream(String name, Server server, int port,
                             java.util.concurrent.atomic.AtomicReference<String> lastWebsocketOrigin,
                             java.util.concurrent.atomic.AtomicReference<String> lastWebsocketHost,
                             java.util.concurrent.atomic.AtomicReference<String> lastWebsocketNamespace,
                             AtomicReference<Session> activeWebsocket,
                             LinkedBlockingQueue<String> websocketPings,
                             LinkedBlockingQueue<String> websocketPongs,
                             LinkedBlockingQueue<CloseFrame> websocketCloses) {
            this.name = name;
            this.server = server;
            this.port = port;
            this.lastWebsocketOrigin = lastWebsocketOrigin;
            this.lastWebsocketHost = lastWebsocketHost;
            this.lastWebsocketNamespace = lastWebsocketNamespace;
            this.activeWebsocket = activeWebsocket;
            this.websocketPings = websocketPings;
            this.websocketPongs = websocketPongs;
            this.websocketCloses = websocketCloses;
        }

        static TestUpstream start(String name) throws Exception {
            Server server = new Server();
            java.util.concurrent.atomic.AtomicReference<String> websocketOrigin =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> websocketHost =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> websocketNamespace =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AtomicReference<Session> activeWebsocket = new AtomicReference<>();
            LinkedBlockingQueue<String> websocketPings = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<String> websocketPongs = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<CloseFrame> websocketCloses = new LinkedBlockingQueue<>();
            ServerConnector connector = new ServerConnector(server);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            ContextHandler context = new ContextHandler("/");
            WebSocketUpgradeHandler websocket = WebSocketUpgradeHandler.from(server, context, container ->
                    container.addMapping("/*", (request, response, callback) -> {
                        websocketOrigin.set(request.getHeaders().get("Origin"));
                        websocketHost.set(request.getHeaders().get("Host"));
                        websocketNamespace.set(request.getHeaders().get(DevNamespaceHeader.NAME));
                        if (!request.getSubProtocols().isEmpty()) {
                            response.setAcceptedSubProtocol(request.getSubProtocols().getFirst());
                        }
                        return new EchoSocket(name, request.getHttpURI().getPath(), activeWebsocket,
                                              websocketPings, websocketPongs, websocketCloses);
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
                    String namespace = request.getHeaders().get(DevNamespaceHeader.NAME);
                    if (namespace != null) {
                        response.getHeaders().put("X-Received-Namespace", namespace);
                    }
                    response.getHeaders().add(HttpHeader.SET_COOKIE, "session=" + name + "; Path=/");
                    response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
                    return true;
                }
            });
            context.setHandler(websocket);
            server.setHandler(context);
            server.start();
            return new TestUpstream(name, server, connector.getLocalPort(), websocketOrigin, websocketHost,
                                    websocketNamespace, activeWebsocket, websocketPings, websocketPongs,
                                    websocketCloses);
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

        String lastWebsocketNamespace() {
            return lastWebsocketNamespace.get();
        }

        void sendPing(String message) throws Exception {
            CompletableFuture<Void> sent = new CompletableFuture<>();
            activeWebsocket().sendPing(utf8(message), org.eclipse.jetty.websocket.api.Callback.from(
                    () -> sent.complete(null), sent::completeExceptionally));
            sent.get(5, TimeUnit.SECONDS);
        }

        void closeWebsocket(int statusCode, String reason) throws Exception {
            CompletableFuture<Void> sent = new CompletableFuture<>();
            activeWebsocket().close(statusCode, reason, org.eclipse.jetty.websocket.api.Callback.from(
                    () -> sent.complete(null), sent::completeExceptionally));
            sent.get(5, TimeUnit.SECONDS);
        }

        String awaitPing() throws Exception {
            return websocketPings.poll(5, TimeUnit.SECONDS);
        }

        String awaitPong() throws Exception {
            return websocketPongs.poll(5, TimeUnit.SECONDS);
        }

        CloseFrame awaitClose() throws Exception {
            return websocketCloses.poll(5, TimeUnit.SECONDS);
        }

        private Session activeWebsocket() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            Session result;
            while ((result = activeWebsocket.get()) == null || !result.isOpen()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Upstream websocket did not become available");
                }
                Thread.sleep(10);
            }
            return result;
        }

        @Override
        public void close() throws Exception {
            server.stop();
        }
    }

    public static final class EchoSocket extends Session.Listener.AbstractAutoDemanding {
        private final String name;
        private final String path;
        private final AtomicReference<Session> activeWebsocket;
        private final LinkedBlockingQueue<String> websocketPings;
        private final LinkedBlockingQueue<String> websocketPongs;
        private final LinkedBlockingQueue<CloseFrame> websocketCloses;
        private volatile Session session;

        private EchoSocket(String name, String path, AtomicReference<Session> activeWebsocket,
                           LinkedBlockingQueue<String> websocketPings,
                           LinkedBlockingQueue<String> websocketPongs,
                           LinkedBlockingQueue<CloseFrame> websocketCloses) {
            this.name = name;
            this.path = path;
            this.activeWebsocket = activeWebsocket;
            this.websocketPings = websocketPings;
            this.websocketPongs = websocketPongs;
            this.websocketCloses = websocketCloses;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            this.session = session;
            activeWebsocket.set(session);
        }

        @Override
        public void onWebSocketText(String message) {
            session.sendText(name + ":" + path + ":" + message,
                             org.eclipse.jetty.websocket.api.Callback.NOOP);
        }

        @Override
        public void onWebSocketBinary(ByteBuffer message, org.eclipse.jetty.websocket.api.Callback callback) {
            session.sendBinary(copy(message), callback);
        }

        @Override
        public void onWebSocketPing(ByteBuffer message) {
            ByteBuffer copy = copy(message);
            websocketPings.add(string(message));
            session.sendPong(copy, org.eclipse.jetty.websocket.api.Callback.NOOP);
        }

        @Override
        public void onWebSocketPong(ByteBuffer message) {
            websocketPongs.add(string(message));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason,
                                     org.eclipse.jetty.websocket.api.Callback callback) {
            websocketCloses.add(new CloseFrame(statusCode, reason));
            activeWebsocket.compareAndSet(session, null);
            callback.succeed();
        }
    }
}
