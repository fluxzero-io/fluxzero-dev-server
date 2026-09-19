/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerApplicationRestartTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void restartsOnlySelectedAppAndRetainsOtherProcessesOnFailure(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve(".fluxzero"));
        Files.writeString(project.resolve(".fluxzero/dev.yaml"), """
                version: 1
                profiles:
                  local:
                    monitoring:
                      enabled: false
                """);
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--profile", "local",
                "--idp", "external", "--no-watch", "--no-compile-on-start"});
        try (var server = new DevServer(config).start(); var http = HttpClient.newHttpClient();
             var readiness = Executors.newSingleThreadScheduledExecutor()) {
            // Fixture processes do not use an SDK. Signal readiness through the same pending-future boundary.
            Map<?, ?> pending = (Map<?, ?>) field(server, "appReadiness");
            readiness.scheduleWithFixedDelay(() -> pending.values().forEach(p -> {
                try {
                    var accessor = p.getClass().getDeclaredMethod("ready");
                    accessor.setAccessible(true);
                    ((CompletableFuture<?>) accessor.invoke(p)).complete(null);
                } catch (Exception e) { throw new AssertionError(e); }
            }), 0, 20, TimeUnit.MILLISECONDS);
            Object runtime = ((Map<?, ?>) field(server, "projects")).values().iterator().next();
            CompilePipeline pipeline = (CompilePipeline) field(runtime, "compilePipeline");
            Path classes = Path.of(FixtureAppMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var apps = List.of(new ApplicationBuild("orders", ".", FixtureAppMain.class.getName(), List.of(classes), List.of()),
                    new ApplicationBuild("billing", ".", FixtureAppMain.class.getName(), List.of(classes), List.of()));
            Path build = Files.createDirectories(project.resolve("build-1"));
            var snapshot = new BuildSnapshot(1, build, classes, List.of(), Instant.now(), CompileTiming.unknown(), apps);
            pipeline.activate(snapshot);
            var start = DevServer.class.getDeclaredMethod("startCandidateApps", runtime.getClass(), BuildSnapshot.class);
            start.setAccessible(true);
            assertNotNull(start.invoke(server, runtime, snapshot));
            String base = server.session().gateway().url();
            JsonNode before = awaitStatus(http, base, status -> pid(status, "app-orders") > 0 && pid(status, "app-billing") > 0);
            long orders = pid(before, "app-orders"), billing = pid(before, "app-billing");
            long runtimePid = server.session().runtime().pid();
            assertEquals(409, restart(http, base, "app-missing"));
            assertEquals(400, restart(http, base, "frontend-ui"));
            assertEquals(202, restart(http, base, "app-billing"));
            JsonNode after = awaitStatus(http, base, status -> pid(status, "app-billing") > 0 && pid(status, "app-billing") != billing);
            assertEquals(orders, pid(after, "app-orders"));
            assertNotEquals(billing, pid(after, "app-billing"));
            assertFalse(ProcessUtils.isAlive(billing));
            assertEquals(runtimePid, server.session().runtime().pid());
            assertEquals("", after.path("maintenance").path("error").asText());
            long replacement = pid(after, "app-billing");
            var broken = new ApplicationBuild("billing", ".", "missing.Main", List.of(classes), List.of());
            pipeline.activate(new BuildSnapshot(2, Files.createDirectories(project.resolve("build-2")), classes, List.of(),
                    Instant.now(), CompileTiming.unknown(), List.of(apps.getFirst(), broken)));
            assertEquals(202, restart(http, base, "app-billing"));
            JsonNode failed = awaitStatus(http, base, status -> !status.path("maintenance").path("error").asText().isEmpty());
            assertFalse(failed.path("maintenance").path("error").asText().isEmpty());
            assertEquals(orders, pid(failed, "app-orders"));
            assertEquals(replacement, pid(failed, "app-billing"));
            assertTrue(ProcessUtils.isAlive(orders));
            assertTrue(ProcessUtils.isAlive(replacement));
            readiness.shutdownNow();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static int restart(HttpClient http, String base, String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "actions/restart-app"))
                .header("Origin", base).header("X-Fluxzero-Console", "1")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("componentId", id))))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static JsonNode status(HttpClient http, String base) throws Exception {
        return JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(base + DevConsole.ROOT + "status.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static JsonNode awaitStatus(HttpClient http, String base, Predicate<JsonNode> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            JsonNode result = status(http, base);
            if (!result.path("maintenance").path("busy").asBoolean() && condition.test(result)) return result;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Restart did not finish");
    }

    private static long pid(JsonNode status, String id) {
        for (JsonNode component : status.path("components")) {
            if (id.equals(component.path("id").asText())) return component.path("pid").asLong();
        }
        return -1;
    }
}
