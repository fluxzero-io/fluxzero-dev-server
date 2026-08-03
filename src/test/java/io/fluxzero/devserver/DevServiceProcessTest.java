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
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServiceProcessTest {

    @Test
    void startsManagedServiceOnDynamicPortAndRunsExplicitCleanup(@TempDir Path projectDirectory) throws Exception {
        Path stopped = projectDirectory.resolve("stopped.txt");
        String java = javaCommand();
        DevServiceConfig config = new DevServiceConfig(
                java + " " + DevServiceFixtureServer.class.getName() + " {servicePort.http}",
                java + " " + DevServiceFixtureServer.class.getName() + " stop " + quote(stopped.toString()),
                "http://127.0.0.1:{servicePort.http}", null,
                new LinkedHashMap<>(Map.of("http", 0)),
                Map.of("FIXTURE_VALUE", "{url}/configured"),
                new DevServiceConfig.Readiness("{url}", null, Duration.ofSeconds(5)));
        List<DevSession.ServiceStatus> statuses = new CopyOnWriteArrayList<>();
        DevServiceProcess service = DevServiceProcess.prepare(
                "victoriaLogs", config, projectDirectory, "session-1", statuses::add, ignored -> {
                });

        service.start();
        long pid = service.status().pid();
        String body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(service.url())).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertTrue(service.ready());
        assertEquals("running", service.status().state());
        assertEquals(service.url(), service.placeholders().get("services.victoriaLogs.url"));
        assertEquals(Integer.toString(service.ports().get("http")),
                     service.placeholders().get("services.victoriaLogs.ports.http"));
        assertEquals("port=" + service.ports().get("http")
                     + ";service=victoriaLogs;servicePort=" + service.ports().get("http")
                     + ";session=session-1;configured=" + service.url() + "/configured", body);
        assertTrue(ProcessUtils.isAlive(pid));

        service.close();

        assertTrue(Files.isRegularFile(stopped));
        assertFalse(ProcessUtils.isAlive(pid));
        assertEquals("stopped", statuses.getLast().state());
    }

    @Test
    void waitsForExternalServiceWithoutOwningItsLifecycle(@TempDir Path projectDirectory) throws Exception {
        int port = ProcessUtils.availablePort();
        Process external = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable("java")).toString(),
                "-cp", System.getProperty("java.class.path"), DevServiceFixtureServer.class.getName(),
                Integer.toString(port)).start();
        try {
            DevServiceConfig config = new DevServiceConfig(
                    null, null, "http://127.0.0.1:" + port, null, Map.of(), Map.of(),
                    new DevServiceConfig.Readiness("http://127.0.0.1:" + port, null, Duration.ofSeconds(5)));
            DevServiceProcess service = DevServiceProcess.prepare(
                    "external", config, projectDirectory, "session-1", ignored -> {
                    }, ignored -> {
                    });

            service.start();
            service.close();

            assertTrue(external.isAlive());
        } finally {
            ProcessUtils.forceStopTree(external);
        }
    }

    @Test
    void reportsBoundedReadinessFailure(@TempDir Path projectDirectory) throws Exception {
        int port = ProcessUtils.availablePort();
        DevServiceConfig config = new DevServiceConfig(
                null, null, "http://127.0.0.1:" + port, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness("http://127.0.0.1:" + port, null, Duration.ofMillis(250)));
        DevServiceProcess service = DevServiceProcess.prepare(
                "missing", config, projectDirectory, "session-1", ignored -> {
                }, ignored -> {
                });

        DevServerStartupException exception = assertThrows(DevServerStartupException.class, service::start);

        assertTrue(exception.getMessage().contains("did not become ready"));
        assertEquals("failed", service.status().state());
        service.close();
    }

    private static String javaCommand() {
        return quote(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString())
               + " -cp " + quote(System.getProperty("java.class.path"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }
}
