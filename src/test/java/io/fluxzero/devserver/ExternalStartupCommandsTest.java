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
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExternalStartupCommandsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runsConfiguredCommandsForAnExternalProcessWithoutBuildingOrResetting(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve(".fluxzero"));
        configure(project, "first");
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--no-watch",
                "--no-compile-on-start", "--no-tests", "--idp", "external", "--no-frontend", "--port", "0"});
        try (DevServer server = new DevServer(config).start(); HttpClient http = HttpClient.newHttpClient()) {
            String base = server.session().consoleOrigin();
            long runtimePid = server.session().runtime().pid();
            String sessionId = server.session().sessionId();
            assertEquals("pending", server.session().commands().state());
            assertTrue(server.session().commands().detail().contains("pending=1"));
            Path processed = project.resolve("processed.txt");
            Process app = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", ProcessUtils.isWindows() ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("java.class.path"), ExternalApplication.class.getName(),
                    server.session().runtime().url(), processed.toString())
                    .redirectErrorStream(true).redirectOutput(project.resolve("external.log").toFile()).start();
            try {
                awaitFile(project.resolve("ready"), app);
                assertFalse(Files.exists(processed), "configuration discovery must not send commands");
                assertEquals("succeeded", run(http, base).path("startup").path("state").asText());
                assertEquals(List.of("first:$system"), Files.readAllLines(processed));
                assertEquals("succeeded", run(http, base).path("startup").path("state").asText());
                assertEquals(1, Files.readAllLines(processed).size());
                configure(project, "changed");
                assertEquals("succeeded", run(http, base).path("startup").path("state").asText());
                assertEquals(List.of("first:$system", "changed:$system"), Files.readAllLines(processed));
                configure(project, "fail");
                JsonNode failure = run(http, base);
                assertEquals("failed", failure.path("startup").path("state").asText());
                assertTrue(failure.path("maintenance").path("error").asText().contains("Startup commands failed"));
                assertFalse(failure.toString().contains("secret-handler-detail"));
                assertFalse(Files.readString(new DevSessionStore(project).directory().resolve(DevSessionStore.COMMAND_STATUS_FILE))
                        .contains("secret-handler-detail"));
                assertEquals(runtimePid, server.session().runtime().pid());
                assertEquals(sessionId, server.session().sessionId());
                assertFalse(Files.exists(project.resolve("target")), "control action must not compile applications");
                assertTrue(app.isAlive());
            } finally {
                app.destroy();
                if (!app.waitFor(5, TimeUnit.SECONDS)) {app.destroyForcibly(); app.waitFor(5, TimeUnit.SECONDS);}
            }
        }
    }

    private JsonNode run(HttpClient http, String base) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "actions/run-startup-commands"))
                .timeout(Duration.ofSeconds(5)).header("Origin", base).header("X-Fluxzero-Console", "1")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            JsonNode status = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "status.json"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            if (!status.path("maintenance").path("busy").asBoolean()) return status;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Startup command action did not complete");
    }

    private void configure(Path project, String value) throws Exception {
        Files.writeString(project.resolve(".fluxzero/dev.yaml"), """
                version: 1
                monitoring: {enabled: false}
                commands:
                  sample:
                    type: io.fluxzero.devserver.ExternalStartupCommandsTest$Sample
                    payload:
                      value: %s
                """.formatted(value));
    }

    private void awaitFile(Path file, Process app) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(file) && app.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(file), () -> "External application failed to start: " + app.isAlive());
    }

    public record Sample(String value) {}

    public static class SampleFailure extends FunctionalException {
        public SampleFailure(String message) { super(message); }
    }

    public static class ExternalApplication {
        public static void main(String[] args) throws Exception {
            Path processed = Path.of(args[1]);
            var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl(args[0]).name("external-sample").id("external-sample").build());
            try (var fluxzero = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive()
                    .disableTrackingMetrics().disableCacheEvictionMetrics().build(client)) {
                fluxzero.registerHandlers(new Object() {
                    @HandleCommand
                    String handle(Sample command, Metadata metadata) throws Exception {
                        if (command.value().equals("fail")) throw new SampleFailure("secret-handler-detail");
                        Files.writeString(processed, command.value() + ":" + metadata.get("$user") + "\n",
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        return "ok";
                    }
                });
                Files.writeString(processed.resolveSibling("ready"), "ready");
                new java.util.concurrent.CountDownLatch(1).await();
            }
        }
    }
}
