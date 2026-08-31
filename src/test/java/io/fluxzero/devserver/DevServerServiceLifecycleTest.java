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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerServiceLifecycleTest {

    @Test
    void publishesAndCleansUpManagedServiceAsPartOfDevEnvironment(@TempDir Path projectDirectory)
            throws Exception {
        Path stopped = projectDirectory.resolve("service-stopped.txt");
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        String java = javaCommand();
        Files.writeString(configFile, """
                version: 1
                services:
                  logs:
                    command: >-
                      %s %s {port.http}
                    stopCommand: >-
                      %s %s stop %s
                    ports:
                      http: dynamic
                    url: "http://127.0.0.1:{port.http}"
                    readiness:
                      http: "{url}"
                      timeout: 5s
                """.formatted(java, DevServiceFixtureServer.class.getName(), java,
                               DevServiceFixtureServer.class.getName(), quote(stopped.toString())));
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--no-watch", "--no-compile-on-start", "--no-tests"
        });
        long pid;

        try (DevServer server = new DevServer(config).start()) {
            DevSession.ServiceStatus service = server.session().services().get("logs");
            pid = service.pid();
            assertEquals("running", service.state());
            assertTrue(ProcessUtils.isAlive(pid));
            assertEquals("logs", HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(service.url())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().split(";")[1].split("=")[1]);
            DevSession persisted = new DevSessionStore(projectDirectory).readSession().orElseThrow();
            assertEquals(service.url(), persisted.services().get("logs").url());
        }

        assertTrue(Files.isRegularFile(stopped));
        assertFalse(ProcessUtils.isAlive(pid));
    }

    private static String javaCommand() {
        Path testClasses = Path.of(URI.create(DevServiceFixtureServer.class.getProtectionDomain()
                                                       .getCodeSource().getLocation().toExternalForm()));
        return quote(Path.of(System.getProperty("java.home"), "bin", executable("java")).toString())
               + " -cp " + quote(testClasses.toString());
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }
}
