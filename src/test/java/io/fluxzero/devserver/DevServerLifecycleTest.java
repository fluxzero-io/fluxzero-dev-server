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
import io.fluxzero.devserver.fixture.FixtureAppMain;
import io.fluxzero.idp.client.Pkce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerLifecycleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void startsEmbeddedRuntimeAndProxyOnDynamicPorts(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        DevSession session;
        Path sessionFile;
        Path mcpTokenFile;
        try (DevServer devServer = new DevServer(config).start()) {
            session = devServer.session();
            sessionFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                    .resolve(DevSessionStore.SESSION_FILE);
            mcpTokenFile = Path.of(session.mcp().metadata().get("tokenFile"));

            assertEquals("running", session.status());
            assertEquals("running", session.runtime().state());
            assertEquals("running", session.proxy().state());
            assertEquals("running", session.idp().state());
            assertEquals("running", session.mcp().state());
            assertTrue(session.runtime().port() > 0);
            assertTrue(session.proxy().port() > 0);
            assertTrue(session.mcp().port() > 0);
            assertNotEquals(session.runtime().port(), session.mcp().port());
            assertNotEquals(session.proxy().port(), session.mcp().port());
            assertEquals("ws://localhost:" + session.runtime().port(), session.runtime().url());
            assertEquals("http://localhost:" + session.proxy().port(), session.proxy().url());
            assertEquals("http://127.0.0.1:" + session.mcp().port() + DevMcpServer.ENDPOINT, session.mcp().url());
            assertEquals(session.proxy().url(), session.idp().url());
            assertEquals("streamable-http", session.mcp().metadata().get("transport"));
            assertTrue(Files.isRegularFile(mcpTokenFile));
            assertEquals(200, healthStatus(session.proxy().url()));

            JsonNode json = objectMapper.readTree(sessionFile.toFile());
            assertEquals(session.proxy().url(), json.path("proxy").path("url").asText());
            assertEquals(session.idp().url(), json.path("idp").path("url").asText());
            assertEquals(session.mcp().url(), json.path("mcp").path("url").asText());
            assertEquals(mcpTokenFile.toString(), json.path("mcp").path("metadata").path("tokenFile").asText());
            assertTrue(json.path("sessionId").isTextual());
            assertTrue(json.path("heartbeatAt").asLong() > 0);
        }

        JsonNode stoppedJson = objectMapper.readTree(sessionFile.toFile());
        assertEquals("stopped", stoppedJson.path("status").asText());
        assertEquals("stopped", stoppedJson.path("runtime").path("state").asText());
        assertEquals("stopped", stoppedJson.path("proxy").path("state").asText());
        assertEquals("stopped", stoppedJson.path("idp").path("state").asText());
        assertEquals("stopped", stoppedJson.path("mcp").path("state").asText());
        assertEquals("dev server stopped", stoppedJson.path("runtime").path("detail").asText());
        assertEquals(session.runtime().port(), stoppedJson.path("runtime").path("port").asInt());
        assertFalse(Files.exists(mcpTokenFile));
    }

    @Test
    void externalIdpModeSkipsManagedIdp(@TempDir Path projectDirectory) {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "external-idp-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), List.of(), false, "local", List.of(), 0, IdpMode.EXTERNAL);

        try (DevServer devServer = new DevServer(config).start()) {
            assertEquals("running", devServer.session().runtime().state());
            assertEquals("running", devServer.session().proxy().state());
            assertEquals("external", devServer.session().idp().state());
            assertTrue(devServer.session().idp().detail().contains("application configuration applies"));
        }
    }

    @Test
    void rejectsSecondDevServerForSameProjectWithoutReplacingDiagnostics(@TempDir Path projectDirectory)
            throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        try (DevServer first = new DevServer(config).start()) {
            Path diagnostics = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                    .resolve(DevLogStore.DIAGNOSTICS_FILE);
            String owningSession = objectMapper.readTree(diagnostics.toFile()).path("sessionId").asText();

            assertThrows(IllegalStateException.class, () -> new DevServer(config).start());
            assertEquals(first.session().sessionId(), owningSession);
            assertEquals(owningSession,
                         objectMapper.readTree(diagnostics.toFile()).path("sessionId").asText());
        }
    }

    @Test
    void rejectsActiveSessionWhenPreviousDevServerPidStillLives(@TempDir Path projectDirectory) {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        store.writeSession(DevSession.empty(config).withStatus("running"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                       () -> new DevServer(config).start());

        assertTrue(exception.getMessage().contains("already active"));
    }

    @Test
    void cleansUpOwnedOrphanAppFromStaleSession(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
        Process orphan = ProcessUtils.start(List.of(
                javaExecutable(),
                "-Dfluxzero.dev.project=" + projectDirectory.toAbsolutePath().normalize(),
                "-cp", testClassesDirectory().toString(),
                FixtureAppMain.class.getName()),
                                            projectDirectory, Map.of(), ignored -> {
                });
        try {
            assertTrue(orphan.isAlive());
            DevSession stale = withPid(DevSession.empty(config).withStatus("running")
                                               .withApp(DevSession.ServiceStatus.running(
                                                       "app", null, null, orphan.pid(), "running build 7")),
                                       unusedPid());
            new DevSessionStore(projectDirectory).writeSession(stale);

            try (DevServer devServer = new DevServer(config).start()) {
                assertTrue(awaitStopped(orphan));
                assertEquals("running", devServer.session().status());
            }
        } finally {
            if (orphan.isAlive()) {
                orphan.destroyForcibly();
            }
        }
    }

    @Test
    void managedIdpCompletesAuthorizationCodeFlowThroughProxy(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);

        try (DevServer devServer = new DevServer(config).start()) {
            String proxyUrl = devServer.session().proxy().url();
            JsonNode discovery = awaitJson(proxyUrl + "/.well-known/openid-configuration");
            assertEquals(proxyUrl, discovery.path("issuer").asText());
            assertEquals(proxyUrl + "/oauth2/token", discovery.path("token_endpoint").asText());
            assertTrue(awaitJson(proxyUrl + "/.well-known/jwks.json").path("keys").isArray());

            String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
            String state = "dev-state";
            String redirectUri = proxyUrl + "/app/callback";
            HttpResponse<String> authorize = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/oauth2/auth?"
                    + form(Map.of(
                            "response_type", "code",
                            "client_id", ManagedIdpService.CLIENT_ID,
                            "redirect_uri", redirectUri,
                            "scope", ManagedIdpService.SCOPE,
                            "state", state,
                            "code_challenge", Pkce.challenge(verifier),
                            "code_challenge_method", "S256"))))
                    .GET()
                    .build());
            assertEquals(302, authorize.statusCode(), authorize.body());
            String loginRequestCookie = cookie(authorize, "fz_local_stub_login_request");

            HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/login"))
                    .header("Cookie", loginRequestCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", "rene@example.com"))))
                    .build());
            assertEquals(302, login.statusCode(), login.body());
            URI callback = URI.create(location(login));
            assertEquals(state, queryParam(callback, "state"));
            String code = queryParam(callback, "code");

            HttpResponse<String> token = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                            "grant_type", "authorization_code",
                            "code", code,
                            "redirect_uri", redirectUri,
                            "client_id", ManagedIdpService.CLIENT_ID,
                            "code_verifier", verifier))))
                    .build());
            assertEquals(200, token.statusCode(), token.body());
            String accessToken = objectMapper.readTree(token.body()).path("access_token").asText();

            HttpResponse<String> userInfo = send(HttpRequest.newBuilder(URI.create(proxyUrl + "/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build());
            assertEquals(200, userInfo.statusCode(), userInfo.body());
            JsonNode identity = objectMapper.readTree(userInfo.body());
            assertEquals("rene@example.com", identity.path("sub").asText());
            assertEquals("rene@example.com", identity.path("email").asText());
            assertEquals("local-auth", identity.path("tenant_id").asText());
        }
    }

    private static int healthStatus(String proxyUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxyUrl + "/proxy/health")).GET().build();
        return send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static DevSession withPid(DevSession session, long pid) {
        return new DevSession(session.sessionId(), pid, session.projectDirectory(), session.observability(),
                              session.status(),
                              session.runtime(), session.proxy(), session.gateway(), session.idp(), session.app(),
                              session.reload(), session.compile(),
                              session.tests(), session.commands(), session.frontend(), session.mcp(),
                              session.startedAt(), session.heartbeatAt(), session.updatedAt());
    }

    private static long unusedPid() {
        long pid = ProcessHandle.current().pid() + 10_000;
        while (ProcessUtils.isAlive(pid)) {
            pid++;
        }
        return pid;
    }

    private static boolean awaitStopped(Process process) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return !process.isAlive();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                       System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private static Path testClassesDirectory() throws Exception {
        return Path.of(DevServerLifecycleTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private JsonNode awaitJson(String url) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url)).GET().build());
                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                }
            } catch (Exception e) {
                lastFailure = e;
            }
            Thread.sleep(50);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("No successful JSON response from " + url);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(request, bodyHandler);
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Set-Cookie " + name));
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("location")
                .orElseThrow(() -> new AssertionError("Missing Location header"));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryParam(URI uri, String name) {
        for (String parameter : uri.getRawQuery().split("&")) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                String value = separator < 0 ? "" : parameter.substring(separator + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter " + name + " in " + uri);
    }
}
