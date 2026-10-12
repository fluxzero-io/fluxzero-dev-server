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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.LINUX, OS.MAC})
class DevServerIngressTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void routesThroughPreparedIngressAndKeepsConsolePrivate(@TempDir Path project) throws Exception {
        String front = javaCommand(FrontendFixtureServer.class) + " {frontendPort}";
        String edge = javaCommand(IngressFixtureServer.class);
        var frontend = FrontendConfig.command(front).withBackendPaths(List.of());
        var service = new DevServiceConfig(edge + " {servicePort.http} upstream.txt", null,
                "http://localhost:{servicePort.http}", null, Map.of("http", 0), Map.of(),
                new DevServiceConfig.Readiness("{url}/inbox", null, Duration.ofSeconds(15)), List.of(),
                edge + " setup upstream.txt {gateway.url}");
        var config = config(project, frontend, service);
        int internalPort;
        int consolePort;
        int ingressPort;
        long ingressPid;
        List<ProcessHandle> ingressChildren;
        try (var server = new DevServer(config).start()) {
            await(() -> "running".equals(server.session().services().get("edge").state())
                    && "running".equals(server.session().frontend().state()));
            var session = server.session();
            String publicUrl = session.gateway().url();
            String console = session.consoleOrigin();
            String internal = Files.readString(project.resolve("upstream.txt"));
            internalPort = URI.create(internal).getPort();
            consolePort = URI.create(console).getPort();
            ingressPort = session.gateway().port();
            ingressPid = session.services().get("edge").pid();
            assertNotEquals(publicUrl, console);
            assertEquals(3, java.util.Set.of(internalPort, consolePort, ingressPort).size());
            assertEquals(200, get(publicUrl + "/").statusCode());
            assertEquals(200, get(publicUrl + "/inbox/messages/1?q=2").statusCode());
            assertEquals(404, get(publicUrl + DevConsole.ROOT + "status.json").statusCode());
            assertEquals(404, get(internal + DevConsole.ROOT + "status.json").statusCode());
            var missingBrowser = CLIENT.send(HttpRequest.newBuilder(URI.create(publicUrl + DevConsole.ROOT + "unknown?token=hidden"))
                    .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missingBrowser.statusCode());
            assertTrue(missingBrowser.body().contains("Fluxzero Dev Server"));
            assertTrue(missingBrowser.body().contains(console + DevConsole.ROOT + "#monitoring/issues"));
            assertFalse(missingBrowser.body().contains("token=hidden"));
            var missingJson = CLIENT.send(HttpRequest.newBuilder(URI.create(publicUrl + DevConsole.ROOT + "unknown"))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missingJson.statusCode());
            assertEquals("", missingJson.body());
            var redirect = get(console + "/inbox/messages/1?q=2");
            assertEquals(307, redirect.statusCode());
            assertEquals(publicUrl + "/inbox/messages/1?q=2", redirect.headers().firstValue("location").orElseThrow());
            JsonNode status = awaitFrontendState(console, "running");
            assertEquals(publicUrl, status.path("publicApplicationUrl").asText());
            assertEquals(2, status.path("frontends").size());
            assertEquals("running", status.path("frontends").get(1).path("state").asText());
            assertTrue(status.path("components").toString().contains(publicUrl + "/inbox"));
            ingressChildren = ProcessHandle.of(ingressPid).orElseThrow().descendants().toList();
            ProcessHandle.of(ingressPid).orElseThrow().destroy();
            await(() -> "failed".equals(server.session().services().get("edge").state()));
            status = awaitFrontendState(console, "failed");
            assertEquals("failed", status.path("frontends").get(0).path("state").asText());
        }
        assertFalse(ProcessUtils.isAlive(ingressPid));
        ingressChildren.forEach(child -> assertFalse(child.isAlive(), "Ingress child survived launcher exit: " + child.pid()));
        for (int port : List.of(internalPort, consolePort, ingressPort)) {
            // Use the real gateway's reuse policy: Linux can retain closed HTTP connections in TIME_WAIT.
            try (var endpoint = DevGateway.reserve(port, "127.0.0.1")) { assertEquals(port, endpoint.port()); }
        }
    }

    @Test
    void advertisesIngressForManagedIdpAndFluxzeroEndpoints(@TempDir Path project) throws Exception {
        String edge = javaCommand(IngressFixtureServer.class);
        var service = new DevServiceConfig(edge + " {servicePort.http} upstream.txt", null,
                "http://localhost:{servicePort.http}", null, Map.of("http", 0), Map.of(),
                new DevServiceConfig.Readiness("{url}/_fluxzero/proxy/health", null, Duration.ofSeconds(15)),
                List.of(), edge + " setup upstream.txt {gateway.url}");
        var frontend = FrontendConfig.command(javaCommand(FrontendFixtureServer.class) + " {frontendPort}");
        var config = new DevServerConfig(project, null, "ingress-idp", null, false, false, false,
                null, null, null, frontend, List.of(), false, "local", List.of(), 0, IdpMode.MANAGED,
                Map.of(), null, null, null, null, Map.of("edge", service), true,
                new DevIngressConfig("edge", null, null));
        try (var server = new DevServer(config).start()) {
            await(() -> "running".equals(server.session().services().get("edge").state()));
            String backend = server.session().gateway().url() + DevGateway.BACKEND_PREFIX;
            assertEquals(backend, server.session().idp().url());
            assertEquals(200, get(backend + "/proxy/health").statusCode());
            var discovery = JSON.readTree(get(backend + "/.well-known/openid-configuration").body());
            assertEquals(backend, discovery.path("issuer").asText());
            assertTrue(discovery.path("token_endpoint").asText().startsWith(backend));
        }
    }

    @Test
    void setupFailureLeavesConsoleAvailableAndApplicationUnready(@TempDir Path project) throws Exception {
        var frontend = FrontendConfig.command(javaCommand(FrontendFixtureServer.class) + " {frontendPort}");
        var service = new DevServiceConfig("exit 99", null, "http://localhost:{servicePort.http}", null,
                Map.of("http", 0), Map.of(), null, List.of(), "exit 7");
        try (var server = new DevServer(config(project, frontend, service)).start()) {
            await(() -> "failed".equals(server.session().services().get("edge").state()));
            var status = awaitFrontendState(server.session().consoleOrigin(), "failed");
            assertEquals("failed", status.path("frontends").get(0).path("state").asText());
            assertTrue(server.session().services().get("edge").detail().contains("setup command exited 7"));
        }
    }

    @Test
    void releasesUnclaimedGatewayReservation() throws Exception {
        int port;
        try (var reservation = DevGateway.reserve(0, "127.0.0.1")) {
            port = reservation.port();
            assertThrows(DevServerStartupException.class, () -> {
                try (var ignored = DevGateway.reserve(port, "127.0.0.1")) { }
            });
            try (var socket = new ServerSocket()) {
                assertThrows(java.net.BindException.class, () -> socket.bind(new InetSocketAddress("127.0.0.1", port)));
            }
        }
        try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port)); }
    }

    private static JsonNode awaitFrontendState(String console, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode result;
        do {
            result = JSON.readTree(get(console + DevConsole.ROOT + "status.json").body());
            if (result.path("frontends").findValuesAsText("state").stream().allMatch(state::equals)) return result;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Expected frontend state " + state + ": " + result);
        return result;
    }

    private static DevServerConfig config(Path project, FrontendConfig frontend, DevServiceConfig service) {
        return new DevServerConfig(project, null, "ingress-test", null, false, false, false,
                null, null, null, frontend, List.of(), false, "local", List.of(), 0,
                IdpMode.EXTERNAL, Map.of(), null, null,
                List.of(new RoutedFrontend("root", "/", frontend), new RoutedFrontend("inbox", "/inbox", frontend)),
                null, Map.of("edge", service), false, new DevIngressConfig("edge", null, null));
    }
    private static String javaCommand(Class<?> type) {
        return quote(Path.of(System.getProperty("java.home"), "bin", "java").toString()) + " -cp "
                + quote(System.getProperty("java.class.path")) + " " + type.getName();
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(ready.getAsBoolean(), "Expected service state within deadline");
    }
}
