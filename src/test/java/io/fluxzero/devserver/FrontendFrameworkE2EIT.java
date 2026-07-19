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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfSystemProperty(named = "fluxzero.devserver.frontend.e2e", matches = "true")
@EnabledOnOs({OS.LINUX, OS.MAC})
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class FrontendFrameworkE2EIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern VITE_WS_TOKEN = Pattern.compile("const wsToken = \\\"([^\\\"]+)\\\";");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void viteRoutesHttpBackendAndHmrThroughPublicGateway(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture("vite", tempDirectory);
        installDependencies(project);
        Path source = project.resolve("src/main.js");
        String command = nodeCommand(project.resolve("node_modules/vite/bin/vite.js"))
                         + " --host 127.0.0.1 --port {port} --strictPort";

        exerciseFramework(project, command, source, "vite-phase-14-v1", "vite-phase-14-v2",
                          "/src/main.js");
    }

    @Test
    void angularRoutesHttpBackendAndLiveReloadThroughPublicGateway(@TempDir Path tempDirectory) throws Exception {
        Path project = copyFixture("angular", tempDirectory);
        installDependencies(project);
        Path source = project.resolve("src/main.ts");
        String command = nodeCommand(project.resolve("node_modules/@angular/cli/bin/ng.js"))
                         + " serve --host 127.0.0.1 --port {port} --hmr";

        exerciseFramework(project, command, source, "angular-phase-14-v1", "angular-phase-14-v2",
                          "/main.js");
    }

    private static void exerciseFramework(Path project, String command, Path source, String oldMarker,
                                          String newMarker, String compiledSourcePath) throws Exception {
        DevServerConfig config = new DevServerConfig(
                project, null, "frontend-e2e-" + UUID.randomUUID(), null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.command(command), List.of());

        try (DevServer devServer = new DevServer(config).start()) {
            DevSession session = awaitSession(devServer, current -> "running".equals(current.frontend().state()),
                                              "frontend readiness");
            String publicUrl = session.gateway().url();
            assertNotEquals(session.frontend().port(), session.gateway().port());
            assertEquals(200, get(publicUrl + "/").statusCode());
            assertEquals(200, get(publicUrl + DevGateway.BACKEND_PREFIX + "/proxy/health").statusCode());

            HmrListener listener = new HmrListener();
            WebSocket socket = HTTP_CLIENT.newWebSocketBuilder()
                    .subprotocols("vite-hmr")
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(websocketUri(publicUrl), listener)
                    .get(15, TimeUnit.SECONDS);
            try {
                assertEquals("vite-hmr", socket.getSubprotocol());
                listener.await(message -> message.contains("\"type\":\"connected\""), "HMR connection");

                Files.writeString(source, Files.readString(source, UTF_8).replace(oldMarker, newMarker), UTF_8);

                listener.await(message -> message.contains("\"type\":\"update\"")
                                          || message.contains("\"type\":\"full-reload\""),
                               "HMR update after source change");
                assertEquals(200, get(publicUrl + "/").statusCode(),
                             "browser navigation immediately after frontend reload must not see a gateway 503");
                awaitHttpBody(publicUrl + compiledSourcePath, body -> body.contains(newMarker),
                              "updated frontend source");
            } finally {
                if (!socket.isOutputClosed()) {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static void installDependencies(Path project) throws Exception {
        List<String> command = new ArrayList<>();
        String npmCli = System.getProperty("fluxzero.npm.cli");
        if (npmCli == null || npmCli.isBlank()) {
            command.add("npm");
        } else {
            command.add(nodeExecutable());
            command.add(npmCli);
        }
        command.addAll(List.of("ci", "--no-audit", "--no-fund", "--ignore-scripts"));
        ProcessUtils.ProcessResult result = ProcessUtils.run(command, project, Map.of(),
                                                             line -> System.out.println("[frontend-install] " + line));
        if (!result.success()) {
            fail("Frontend dependency installation failed" + System.lineSeparator() + result.tail(40));
        }
    }

    private static String nodeCommand(Path script) {
        return quote(nodeExecutable()) + " " + quote(script.toString());
    }

    private static String nodeExecutable() {
        return System.getProperty("fluxzero.node", "node");
    }

    private static DevSession awaitSession(DevServer server, Predicate<DevSession> predicate, String reason)
            throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        DevSession last;
        do {
            last = server.session();
            if (predicate.test(last)) {
                return last;
            }
            if ("failed".equals(last.frontend().state())) {
                fail("Frontend failed while waiting for " + reason + ": " + last.frontend().detail());
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for " + reason + ": " + last.frontend());
        throw new IllegalStateException("unreachable");
    }

    private static String awaitHttpBody(String url, Predicate<String> predicate, String reason) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        String last = null;
        do {
            HttpResponse<String> response = get(url);
            last = response.body();
            if (response.statusCode() == 200 && predicate.test(last)) {
                return last;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for " + reason + " at " + url + ". Last response: " + last);
        throw new IllegalStateException("unreachable");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
    }

    private static URI websocketUri(String httpUrl) throws Exception {
        String viteClient = get(httpUrl + "/@vite/client").body();
        Matcher token = VITE_WS_TOKEN.matcher(viteClient);
        assertTrue(token.find(), "Vite client did not expose an HMR websocket token");
        return URI.create(httpUrl.replaceFirst("^http", "ws") + "/?token="
                          + URLEncoder.encode(token.group(1), UTF_8));
    }

    private static Path copyFixture(String name, Path tempDirectory) throws IOException, URISyntaxException {
        URL resource = FrontendFrameworkE2EIT.class.getResource("/frontend-fixtures/" + name);
        if (resource == null) {
            throw new IllegalStateException("Missing frontend E2E fixture " + name);
        }
        Path source = Path.of(resource.toURI());
        Path target = tempDirectory.resolve(name);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                           StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        return target;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class HmrListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String await(Predicate<String> predicate, String reason) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
            List<String> seen = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                String message = messages.poll(250, TimeUnit.MILLISECONDS);
                if (message != null) {
                    seen.add(message);
                    if (predicate.test(message)) {
                        return message;
                    }
                }
            }
            fail("Timed out waiting for " + reason + ". Messages: " + seen);
            throw new IllegalStateException("unreachable");
        }
    }
}
