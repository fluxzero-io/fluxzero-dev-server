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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Opt-in real Docker qualification; never required on machines without a container runtime. */
@EnabledIfSystemProperty(named = "fluxzero.dev.containerE2e", matches = "true")
class ContainerServicesE2EIT {
    private static final String PUBLIC_IMAGE = "nginx@sha256:516475cc129da42866742567714ddc681e5eed7b9ee0b9e9c015e464b4221a00";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void verifiesPrivatePullsEvenWithWarmCacheAndRedactsAuthenticationFailures(@TempDir Path root) throws Exception {
        assumeFalse(ProcessUtils.isWindows(), "credential-wrapper fixture uses a POSIX shell");
        Path project = Files.createDirectories(root.resolve("project"));
        Path auth = Files.createDirectories(root.resolve("auth"));
        Path credentials = Files.createDirectories(root.resolve("credentials"));
        Files.writeString(auth.resolve("htpasswd"), "dev:$2y$05$34KdzQ7Yct0IXBxTAM3iXukCiZOJyZd2wpxlLSN2w/OfSBWO/D5SO\n");
        int port = ProcessUtils.availablePort();
        String registry = "fz-registry-" + UUID.randomUUID();
        String host = cli(root, "docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}");
        String repo = "localhost:" + port + "/qualification/web";
        String pinned = null;
        cli(root, "docker", "pull", PUBLIC_IMAGE);
        cli(root, "docker", "run", "--detach", "--name", registry, "--publish", "127.0.0.1:" + port + ":5000",
                "--mount", "type=bind,source=" + auth + ",target=/auth,readonly",
                "--env", "REGISTRY_AUTH=htpasswd", "--env", "REGISTRY_AUTH_HTPASSWD_REALM=qualification",
                "--env", "REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd", "registry:2");
        try {
            try (HttpClient http = HttpClient.newHttpClient()) {
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                boolean ready = false;
                while (System.nanoTime() < deadline) {
                    try {
                        ready = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v2/"))
                                .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 401;
                        if (ready) break;
                    } catch (Exception ignored) { }
                    Thread.sleep(50);
                }
                assertTrue(ready);
            }
            writeCredentials(credentials, port, true);
            cli(root, "docker", "tag", PUBLIC_IMAGE, repo + ":fixture");
            cli(root, "docker", "--config", credentials.toString(), "--host", host, "push", repo + ":fixture");
            // Select the private repository digest without depending on local RepoDigests ordering.
            String digests = cli(root, "docker", "image", "inspect", "--format", "{{join .RepoDigests \"\\n\"}}", repo + ":fixture");
            pinned = digests.lines().filter(value -> value.startsWith(repo + "@sha256:")).findFirst().orElseThrow();
            cli(root, "docker", "image", "rm", repo + ":fixture");
            Path wrapper = root.resolve("runtime");
            Files.writeString(wrapper, "#!/bin/sh\nexec docker --host '" + host + "' --config '" + credentials.toString().replace("'", "'\\''") + "' \"$@\"\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            var state = new AtomicReference<DevSession.ServiceStatus>();
            try (var service = service(project, wrapper.toString(), pinned, "always", state)) {
                service.start();
                assertEquals("running", service.status().state());
                assertEquals("false", service.status().metadata().get("container.cachedBeforePull"));
                assertEquals("verified", service.status().metadata().get("container.registryAccess"));
                try (HttpClient http = HttpClient.newHttpClient()) {
                    assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(service.url())).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                }
            }
            writeCredentials(credentials, port, false);
            try (var service = service(project, wrapper.toString(), pinned, "verify", state)) {
                assertThrows(DevServerStartupException.class, service::start);
                assertEquals("true", service.status().metadata().get("container.cachedBeforePull"));
                assertEquals("failed", service.status().metadata().get("container.registryAccess"));
                assertEquals("registry-access", service.status().metadata().get("container.failure"));
                assertFalse(json.writeValueAsString(service.status()).contains("fixture-only"));
            }
            for (String policy : List.of("never", "if-missing")) {
                try (var service = service(project, wrapper.toString(), pinned, policy, state)) {
                    service.start();
                    assertEquals("not-checked", service.status().metadata().get("container.registryAccess"));
                    assertEquals("true", service.status().metadata().get("container.cachedBeforePull"));
                }
            }
            assertFalse(Files.exists(project.resolve(".fluxzero/dev/containers.json")));
            DevContainerRuntime.reconcile(project);
        } finally {
            cli(root, "docker", "rm", "--force", registry);
            if (pinned != null) cli(root, "docker", "image", "rm", pinned);
        }
    }

    @Test
    void reconcilesCrashedSessionAndCleansSharedNetworksOnShutdown(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path project) throws Exception {
        cli(project, "docker", "pull", PUBLIC_IMAGE);
        Files.createDirectories(project.resolve(".fluxzero"));
        String service = """
                  %s:
                    container:
                      image: %s
                      pull: never
                      ports: {http: 80}
                    ports: {http: dynamic}
                    url: http://127.0.0.1:{servicePort.http}
                """;
        Files.writeString(project.resolve(".fluxzero/dev.yaml"), "version: 1\nfrontendOnly: true\nfrontend: {url: '{services.one.url}'}\nservices:\n"
                + service.formatted("one", PUBLIC_IMAGE) + service.formatted("two", PUBLIC_IMAGE));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", ProcessUtils.isWindows() ? "java.exe" : "java").toString(),
                "-cp", System.getProperty("java.class.path"), CrashedSession.class.getName(), project.toString())
                .redirectErrorStream(true).redirectOutput(project.resolve("child.log").toFile()).start();
        String oldContainer = null, oldNetwork = null;
        try {
            var store = new DevSessionStore(project);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            DevSession previous = null;
            while (child.isAlive() && System.nanoTime() < deadline) {
                previous = store.readSession().orElse(null);
                if (previous != null && previous.services().size() == 2
                    && previous.services().values().stream().allMatch(s -> "running".equals(s.state()))) break;
                Thread.sleep(50);
            }
            assertNotNull(previous, Files.readString(project.resolve("child.log")));
            assertEquals("running", previous.services().get("two").state(), Files.readString(project.resolve("child.log")));
            oldContainer = previous.services().get("one").metadata().get("container.name");
            oldNetwork = previous.services().get("one").metadata().get("container.network");
            assertEquals(oldNetwork, previous.services().get("two").metadata().get("container.network"));
            child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            String currentNetwork;
            try (var server = new DevServer(config(project)).start()) {
                assertFalse(cli(project, "docker", "container", "ls", "--all", "--format", "{{.Names}}").lines().toList().contains(oldContainer));
                assertFalse(cli(project, "docker", "network", "ls", "--format", "{{.Name}}").lines().toList().contains(oldNetwork));
                currentNetwork = server.session().services().get("one").metadata().get("container.network");
                assertEquals("running", server.session().services().get("two").state());
            }
            assertFalse(cli(project, "docker", "network", "ls", "--format", "{{.Name}}").lines().toList().contains(currentNetwork));
            assertFalse(Files.exists(project.resolve(".fluxzero/dev/containers.json")));
            DevContainerRuntime.reconcile(project);
        } finally {
            child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS);
            DevContainerRuntime.reconcile(project);
        }
    }

    @Test
    void allowsContainerToHandleTerminationBeforeRemoval(@TempDir Path project) throws Exception {
        cli(project, "docker", "pull", PUBLIC_IMAGE);
        var container = new DevContainerConfig(PUBLIC_IMAGE, "docker", "never", Map.of(),
                List.of(new DevContainerConfig.Mount(project.toString(), "/evidence", false)), null, null, null,
                false, List.of(), List.of(), List.of("/bin/sh", "-c",
                "trap 'echo terminated > /evidence/stopped; exit 0' TERM QUIT; echo READY; while :; do sleep 1 & wait $!; done"),
                null, false);
        var config = new DevServiceConfig(null, null, null, null, Map.of(), Map.of(),
                new DevServiceConfig.Readiness(null, null, java.util.regex.Pattern.compile("READY"), Duration.ofSeconds(20)),
                List.of(), null, container);
        try {
            try (var service = DevServiceProcess.prepare("graceful", config, project, UUID.randomUUID().toString(),
                    Duration.ofSeconds(15), ignored -> {}, ignored -> {})) {
                service.start();
                assertEquals("running", service.status().state());
            }
            assertEquals("terminated", Files.readString(project.resolve("stopped")).strip());
        } finally { DevContainerRuntime.reconcile(project); }
    }

    private void writeCredentials(Path directory, int port, boolean valid) throws Exception {
        json.writeValue(directory.resolve("config.json").toFile(), Map.of("auths", Map.of("localhost:" + port,
                Map.of("auth", Base64.getEncoder().encodeToString(("dev:" + (valid ? "fixture-only" : "expired")).getBytes(java.nio.charset.StandardCharsets.UTF_8))))));
    }

    private DevServiceProcess service(Path project, String runtime, String image, String pull,
                                       AtomicReference<DevSession.ServiceStatus> state) throws Exception {
        Files.createDirectories(project.resolve(".fluxzero"));
        Files.writeString(project.resolve(".fluxzero/dev.yaml"), """
                version: 1
                frontendOnly: true
                frontend: {url: '{services.web.url}'}
                services:
                  web:
                    container:
                      image: %s
                      runtime: '%s'
                      registryInsecure: true
                      pull: %s
                      ports: {http: 80}
                    ports: {http: dynamic}
                    url: http://127.0.0.1:{servicePort.http}
                """.formatted(image, runtime.replace("'", "''"), pull));
        return DevServiceProcess.prepare("web", config(project).services().get("web"), project,
                UUID.randomUUID().toString(), Duration.ofSeconds(15), state::set, ignored -> {});
    }

    private static DevServerConfig config(Path project) {
        return DevServerConfig.fromArgs(new String[]{"--project-dir", project.toString(), "--no-watch", "--no-tests", "--no-compile-on-start", "--port", "0"});
    }

    private String cli(Path root, String... command) throws Exception {
        Path output = Files.createTempFile(root, "cli-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {process.destroyForcibly(); throw new AssertionError("Fixture runtime command timed out");}
        String result = Files.readString(output);
        assertEquals(0, process.exitValue(), result);
        return result.strip();
    }

    public static class CrashedSession {
        public static void main(String[] args) throws Exception {
            try (var server = new DevServer(config(Path.of(args[0]))).start()) { new java.util.concurrent.CountDownLatch(1).await(); }
        }
    }
}
