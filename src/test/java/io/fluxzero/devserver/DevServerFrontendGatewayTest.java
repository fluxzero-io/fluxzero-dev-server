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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class DevServerFrontendGatewayTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Test
    void explicitOccupiedPortFailsBeforeInfrastructureOrUsesConfirmedDynamicFallback(
            @TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            DevServerConfig config = new DevServerConfig(
                    projectDirectory, null, "fixed-port-test", null,
                    false, false, false,
                    DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                    DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                    DevServerConfig.DEFAULT_DEBOUNCE,
                    FrontendConfig.command(command), List.of(), false, "local", List.of(),
                    occupied.getLocalPort(), IdpMode.MANAGED);

            try (DevServer rejected = new DevServer(config, ignored -> false)) {
                DevServerStartupException exception = assertThrows(DevServerStartupException.class, rejected::start);
                assertTrue(exception.getMessage().contains("Port " + occupied.getLocalPort()));
                assertEquals("stopped", rejected.session().runtime().state());
                assertEquals("stopped", rejected.session().proxy().state());
            }

            try (DevServer fallback = new DevServer(config, ignored -> true).start()) {
                DevSession session = awaitSession(fallback);
                assertEquals("running", session.gateway().state());
                assertNotEquals(occupied.getLocalPort(), session.gateway().port());
            }
        }
    }

    @Test
    void publishesSingleOriginAndUsesItForManagedIdp(@TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "frontend-gateway-test", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command(command), List.of());

        try (DevServer devServer = new DevServer(config).start()) {
            DevSession session = awaitSession(devServer);
            assertEquals("running", session.gateway().state());
            assertEquals("running", session.frontend().state());
            assertNotEquals(session.gateway().port(), session.proxy().port());
            assertNotEquals(session.gateway().port(), session.frontend().port());

            String publicUrl = session.gateway().url();
            String backendUrl = publicUrl + DevGateway.BACKEND_PREFIX;
            assertTrue(get(publicUrl + "/").body().contains("backend=" + DevGateway.BACKEND_PREFIX));
            assertEquals(200, get(backendUrl + "/proxy/health").statusCode());
            assertEquals(backendUrl, session.idp().url());

            JsonNode discovery = OBJECT_MAPPER.readTree(
                    get(backendUrl + "/.well-known/openid-configuration").body());
            assertEquals(backendUrl, discovery.path("issuer").asText());
            assertTrue(discovery.path("token_endpoint").asText().startsWith(backendUrl));
        }
    }

    @Test
    void frontendFailureLeavesFluxzeroEnvironmentAvailable(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "failed-frontend-test", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command("exit 7"), List.of());

        try (DevServer devServer = new DevServer(config).start()) {
            DevSession session = awaitSession(devServer, current -> "exited".equals(current.frontend().state()));
            assertEquals("running", session.runtime().state());
            assertEquals("running", session.proxy().state());
            assertEquals("running", session.gateway().state());
            assertEquals("running", session.idp().state());
            assertEquals(503, get(session.gateway().url() + "/").statusCode());
            assertEquals(503, get(session.gateway().url() + "/api/query").statusCode());
            assertEquals(200, get(session.gateway().url() + DevGateway.BACKEND_PREFIX + "/proxy/health")
                    .statusCode());
        }
    }

    @Test
    void lateFrontendCallbackCannotOverwriteStoppedSession(@TempDir Path projectDirectory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String command = quote(java) + " -cp " + quote(System.getProperty("java.class.path")) + " "
                         + FrontendFixtureServer.class.getName() + " {port}";
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "frontend-shutdown-test", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command(command), List.of());
        Path sessionFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                .resolve(DevSessionStore.SESSION_FILE);

        DevServer devServer = new DevServer(config).start();
        try {
            awaitSession(devServer);
        } finally {
            devServer.close();
        }
        Thread.sleep(300);

        JsonNode session = OBJECT_MAPPER.readTree(Files.readString(sessionFile));
        assertEquals("stopped", session.path("status").asText());
        assertEquals("stopped", session.path("frontend").path("state").asText());
        assertEquals("stopped", session.path("gateway").path("state").asText());
    }

    private static DevSession awaitSession(DevServer server) throws Exception {
        return awaitSession(server, session -> "running".equals(session.frontend().state())
                                              && "running".equals(session.idp().state()));
    }

    private static DevSession awaitSession(DevServer server,
                                           java.util.function.Predicate<DevSession> predicate) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        DevSession session;
        do {
            session = server.session();
            if (predicate.test(session)) {
                return session;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return session;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
