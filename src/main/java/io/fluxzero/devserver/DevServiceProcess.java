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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class DevServiceProcess implements AutoCloseable {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration STOP_COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final long PROBE_INTERVAL_MILLIS = 200;
    private static final int UNAVAILABLE_PROBES = 3;

    private final String id;
    private final DevServiceConfig config;
    private final String ownershipMarker;
    private final Map<String, Integer> ports;
    private final String url;
    private final String command;
    private final String stopCommand;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final DevServiceConfig.Readiness readiness;
    private final Consumer<DevSession.ServiceStatus> statusConsumer;
    private final Consumer<ProcessUtils.ProcessOutput> outputConsumer;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Process process;
    private volatile String state = "starting";
    private volatile String detail = "waiting to start";
    private volatile String probeFailure;

    static DevServiceProcess prepare(
            String id, DevServiceConfig config, Path projectDirectory, String sessionId,
            Consumer<DevSession.ServiceStatus> statusConsumer,
            Consumer<ProcessUtils.ProcessOutput> outputConsumer
    ) {
        try {
            Map<String, Integer> ports = new LinkedHashMap<>();
            config.ports().forEach((name, configured) -> {
                try {
                    ports.put(name, configured == 0 ? ProcessUtils.availablePort() : configured);
                } catch (IOException e) {
                    throw new PortAllocationException(e);
                }
            });
            Map<String, String> localValues = new LinkedHashMap<>();
            localValues.put("session.id", sessionId);
            ports.forEach((name, port) -> {
                localValues.put("servicePort." + name, Integer.toString(port));
                localValues.put("port." + name, Integer.toString(port));
            });
            DevPlaceholderResolver resolver = new DevPlaceholderResolver(
                    localValues, Set.of("servicePort.", "port.", "services.", "session.", "url"));
            String url = resolver.resolve(config.url());
            resolver = resolver.with(url == null ? Map.of() : Map.of("url", url), Set.of("url"));
            String directory = resolver.resolve(config.directory());
            Path workingDirectory = directory == null ? projectDirectory : Path.of(directory);
            if (!workingDirectory.isAbsolute()) {
                workingDirectory = projectDirectory.resolve(workingDirectory);
            }
            workingDirectory = workingDirectory.normalize();
            Map<String, String> environment = resolver.resolve(config.environment());
            environment = processEnvironment(id, sessionId, url, ports, environment);
            DevServiceConfig.Readiness readiness = new DevServiceConfig.Readiness(
                    resolver.resolve(config.readiness().http()), resolver.resolve(config.readiness().tcp()),
                    config.readiness().timeout());
            return new DevServiceProcess(
                    id, config, projectDirectory, sessionId + "-service-" + id, ports, url,
                    resolver.resolve(config.command()), resolver.resolve(config.stopCommand()), workingDirectory,
                    environment, readiness, statusConsumer, outputConsumer);
        } catch (PortAllocationException e) {
            throw new DevServerStartupException("Could not allocate a port for service " + id, e.getCause());
        } catch (RuntimeException e) {
            throw new DevServerStartupException("Could not prepare service " + id + ": " + e.getMessage(), e);
        }
    }

    private DevServiceProcess(
            String id, DevServiceConfig config, Path projectDirectory, String ownershipMarker,
            Map<String, Integer> ports, String url, String command, String stopCommand, Path workingDirectory,
            Map<String, String> environment, DevServiceConfig.Readiness readiness,
            Consumer<DevSession.ServiceStatus> statusConsumer,
            Consumer<ProcessUtils.ProcessOutput> outputConsumer
    ) {
        this.id = id;
        this.config = config;
        this.ownershipMarker = ownershipMarker;
        this.ports = Collections.unmodifiableMap(new LinkedHashMap<>(ports));
        this.url = url;
        this.command = command;
        this.stopCommand = stopCommand;
        this.workingDirectory = workingDirectory;
        this.environment = Map.copyOf(environment);
        this.readiness = readiness;
        this.statusConsumer = statusConsumer;
        this.outputConsumer = outputConsumer;
    }

    void start() {
        if (closed.get()) {
            return;
        }
        if (!Files.isDirectory(workingDirectory)) {
            fail("service directory does not exist: " + workingDirectory);
            throw new DevServerStartupException("Service " + id + " directory does not exist: " + workingDirectory);
        }
        detail = config.managed() ? "starting managed service" : "waiting for external service";
        publishStatus();
        if (config.managed()) {
            try {
                Process started = ProcessUtils.startWithStreams(
                        ProcessUtils.shellCommand(command, ownershipMarker), workingDirectory, environment,
                        outputConsumer);
                process = started;
                publishStatus();
                started.onExit().thenRun(() -> processExited(started));
            } catch (IOException e) {
                fail("could not start service command: " + oneLine(e.getMessage()));
                throw new DevServerStartupException("Could not start service " + id + ": " + e.getMessage(), e);
            }
        }
        awaitReadiness();
        if (closed.get()) {
            return;
        }
        ready.set(true);
        state = "running";
        detail = config.managed() ? "managed service ready" : "external service ready";
        publishStatus();
        startReadinessMonitor();
    }

    boolean ready() {
        return ready.get();
    }

    String url() {
        return url;
    }

    Map<String, Integer> ports() {
        return ports;
    }

    Map<String, String> placeholders() {
        Map<String, String> result = new LinkedHashMap<>();
        if (url != null) {
            result.put("services." + id + ".url", url);
        }
        ports.forEach((name, port) -> result.put(
                "services." + id + ".ports." + name, Integer.toString(port)));
        return result;
    }

    DevSession.ServiceStatus status() {
        Process current = process;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("mode", config.managed() ? "managed" : "external");
        ports.forEach((name, port) -> metadata.put("port." + name, Integer.toString(port)));
        ProcessUtils.startedAt(current).ifPresent(startedAt -> metadata.put(
                ProcessUtils.PROCESS_STARTED_AT, Long.toString(startedAt)));
        return new DevSession.ServiceStatus(
                id, state, url, primaryPort(), current == null ? null : current.pid(), detail, metadata);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready.set(false);
        if (config.managed() && stopCommand != null) {
            runStopCommand();
        }
        Process current = process;
        process = null;
        if (current != null && current.isAlive()) {
            ProcessUtils.forceStopTree(current);
        }
        state = "stopped";
        detail = "dev server stopped";
        publishStatus();
    }

    private void awaitReadiness() {
        long deadline = System.nanoTime() + readiness.timeout().toNanos();
        while (!closed.get() && System.nanoTime() < deadline) {
            Process current = process;
            if (config.managed() && current != null && !current.isAlive()) {
                fail("service process exited before readiness");
                throw new DevServerStartupException("Service " + id + " process exited before readiness");
            }
            if (probe()) {
                return;
            }
            sleep(PROBE_INTERVAL_MILLIS);
        }
        if (closed.get()) {
            return;
        }
        String failure = "service did not become ready within " + readiness.timeout()
                         + (probeFailure == null ? "" : " (" + probeFailure + ")");
        fail(failure);
        throw new DevServerStartupException("Service " + id + " " + failure);
    }

    private void startReadinessMonitor() {
        Thread.ofPlatform().daemon(true).name("fluxzero-dev-service-readiness-" + id).start(() -> {
            int failedProbes = 0;
            while (!closed.get()) {
                Process current = process;
                if (config.managed() && (current == null || !current.isAlive())) {
                    fail("service process exited unexpectedly");
                    return;
                }
                boolean observedReady = probe();
                if (observedReady) {
                    failedProbes = 0;
                    if (!ready.getAndSet(true)) {
                        state = "running";
                        detail = "service recovered";
                        publishStatus();
                    }
                } else if (++failedProbes >= UNAVAILABLE_PROBES && ready.getAndSet(false)) {
                    state = "degraded";
                    detail = "service unavailable" + (probeFailure == null ? "" : ": " + probeFailure);
                    publishStatus();
                }
                sleep(PROBE_INTERVAL_MILLIS);
            }
        });
    }

    private boolean probe() {
        if (readiness.http() == null && readiness.tcp() == null) {
            probeFailure = "readiness is not configured";
            return false;
        }
        return readiness.http() != null ? probeHttp(readiness.http()) : probeTcp(readiness.tcp());
    }

    private boolean probeHttp(String target) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(target).toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout((int) PROBE_TIMEOUT.toMillis());
            connection.setReadTimeout((int) PROBE_TIMEOUT.toMillis());
            connection.setRequestMethod("GET");
            int statusCode = connection.getResponseCode();
            probeFailure = "HTTP " + statusCode;
            return statusCode >= 200 && statusCode < 400;
        } catch (Exception e) {
            probeFailure = oneLine(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean probeTcp(String target) {
        try {
            URI uri = URI.create(target.contains("://") ? target : "tcp://" + target);
            if (uri.getHost() == null || uri.getPort() < 1) {
                throw new IllegalArgumentException("expected host:port");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), (int) PROBE_TIMEOUT.toMillis());
            }
            probeFailure = null;
            return true;
        } catch (Exception e) {
            probeFailure = oneLine(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return false;
        }
    }

    private void processExited(Process exited) {
        if (closed.get() || process != exited) {
            return;
        }
        ready.set(false);
        fail("service process exited unexpectedly with code " + exited.exitValue());
    }

    private void runStopCommand() {
        Process cleanup = null;
        try {
            cleanup = ProcessUtils.startWithStreams(
                    ProcessUtils.shellCommand(stopCommand, ownershipMarker), workingDirectory, environment,
                    outputConsumer);
            if (!cleanup.waitFor(STOP_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessUtils.forceStopTree(cleanup);
            }
        } catch (IOException e) {
            outputConsumer.accept(new ProcessUtils.ProcessOutput(
                    "stderr", "cleanup command could not start: " + oneLine(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cleanup != null) {
                ProcessUtils.forceStopTree(cleanup);
            }
        }
    }

    private void fail(String failure) {
        state = "failed";
        detail = failure;
        ready.set(false);
        publishStatus();
    }

    private void publishStatus() {
        statusConsumer.accept(status());
    }

    private Integer primaryPort() {
        if (url != null) {
            int urlPort = URI.create(url).getPort();
            if (urlPort >= 0) {
                return urlPort;
            }
        }
        return ports.values().stream().findFirst().orElse(null);
    }

    private static Map<String, String> processEnvironment(
            String id, String sessionId, String url, Map<String, Integer> ports, Map<String, String> configured
    ) {
        Map<String, String> result = new LinkedHashMap<>(configured);
        result.put("FLUXZERO_DEV_SESSION_ID", sessionId);
        result.put("FLUXZERO_SERVICE_ID", id);
        if (url != null) {
            result.put("FLUXZERO_SERVICE_URL", url);
        }
        ports.forEach((name, port) -> result.put(
                "FLUXZERO_SERVICE_PORT_" + environmentName(name), Integer.toString(port)));
        if (ports.size() == 1) {
            result.put("PORT", Integer.toString(ports.values().iterator().next()));
        }
        return result;
    }

    private static String environmentName(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static String oneLine(String value) {
        return value == null ? "unknown error" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PortAllocationException extends RuntimeException {
        private PortAllocationException(IOException cause) {
            super(cause);
        }
    }
}
