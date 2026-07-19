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
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class FrontendProcess implements AutoCloseable {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READY_STABILITY = Duration.ofSeconds(3);
    private static final Duration UNAVAILABLE_STABILITY = Duration.ofSeconds(1);
    private static final Duration RESTART_DELAY = Duration.ofMillis(250);
    private static final long PROBE_INTERVAL_MILLIS = 100;

    private final FrontendConfig config;
    private final DevServerConfig devConfig;
    private volatile Process process;
    private volatile Process setupProcess;
    private final String internalUrl;
    private final Consumer<DevSession.ServiceStatus> statusConsumer;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean launchStarted = new AtomicBoolean();
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> output;
    private volatile boolean setupRunning;
    private volatile boolean everReady;
    private volatile boolean recoveryUsed;
    private volatile String failureDetail;
    private volatile long unavailableSinceNanos = -1;
    private volatile String probeFailure;

    private FrontendProcess(DevServerConfig devConfig, FrontendConfig config, Process process, String internalUrl,
                            Consumer<DevSession.ServiceStatus> statusConsumer) {
        this.devConfig = devConfig;
        this.config = config;
        this.process = process;
        this.internalUrl = internalUrl;
        this.statusConsumer = statusConsumer;
    }

    static FrontendProcess start(DevServerConfig devConfig, String proxyUrl, Consumer<String> output) {
        return start(devConfig, ignored -> {
        }, output);
    }

    static FrontendProcess start(DevServerConfig devConfig, Consumer<DevSession.ServiceStatus> statusConsumer,
                                 Consumer<String> output) {
        FrontendProcess frontend = prepare(devConfig, statusConsumer, output);
        try {
            frontend.launch(output);
            return frontend;
        } catch (RuntimeException e) {
            frontend.close();
            throw e;
        }
    }

    static FrontendProcess prepare(DevServerConfig devConfig, Consumer<DevSession.ServiceStatus> statusConsumer,
                                   Consumer<String> output) {
        FrontendConfig config = devConfig.frontend();
        if (config.mode() == FrontendConfig.Mode.NONE) {
            return new FrontendProcess(devConfig, config, null, null, statusConsumer);
        }
        try {
            if (config.mode() == FrontendConfig.Mode.EXTERNAL_URL) {
                FrontendProcess frontend = new FrontendProcess(devConfig, config, null, config.url(), statusConsumer);
                frontend.startReadinessMonitor(output);
                return frontend;
            }
            int port = availablePort();
            String internalUrl = "http://127.0.0.1:" + port;
            FrontendProcess frontend = new FrontendProcess(devConfig, config, null, internalUrl, statusConsumer);
            frontend.startReadinessMonitor(output);
            return frontend;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start frontend command", e);
        }
    }

    void launch(Consumer<String> output) {
        if (config.mode() != FrontendConfig.Mode.COMMAND || closed.get()
            || !launchStarted.compareAndSet(false, true)) {
            return;
        }
        this.output = output;
        try {
            runSetup(output);
            if (!closed.get()) {
                launchProcess(output);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (closed.get()) {
                return;
            }
            throw new IllegalStateException("Failed to start frontend command", e);
        }
    }

    DevSession.ServiceStatus status() {
        return switch (config.mode()) {
            case NONE -> DevSession.ServiceStatus.stopped("frontend");
            case EXTERNAL_URL -> ready.get()
                    ? DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl), null, "external ready")
                    : DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl), null,
                                                       waitingDetail("waiting for external frontend")).withState(
                            "starting", waitingDetail("waiting for external frontend"));
            case COMMAND -> commandStatus();
        };
    }

    private DevSession.ServiceStatus commandStatus() {
        String failure = failureDetail;
        if (failure != null) {
            return DevSession.ServiceStatus.failed("frontend", failure);
        }
        Process setup = setupProcess;
        if (setupRunning) {
            return DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl),
                                                    setup == null ? null : setup.pid(), "running frontend setup")
                    .withState("starting", "running frontend setup");
        }
        Process current = process;
        if (recoveryInProgress.get()) {
            return DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl),
                                                    current == null ? null : current.pid(), "restarting frontend")
                    .withState("starting", "restarting frontend");
        }
        if (current == null || !current.isAlive()) {
            return DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl), null,
                                                    "waiting to launch frontend command")
                    .withState("starting", "waiting to launch frontend command");
        }
        return ready.get()
                ? DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl), current.pid(),
                                                   config.command())
                : DevSession.ServiceStatus.running("frontend", internalUrl, port(internalUrl), current.pid(),
                                                   waitingDetail("waiting for frontend readiness"))
                        .withState("starting", waitingDetail("waiting for frontend readiness"));
    }

    String internalUrl() {
        return internalUrl;
    }

    boolean ready() {
        return ready.get();
    }

    @Override
    public void close() {
        closed.set(true);
        ready.set(false);
        Process setup = setupProcess;
        setupProcess = null;
        Process current = process;
        process = null;
        if (setup != null && setup.isAlive()) {
            ProcessUtils.forceStopTree(setup);
        }
        if (current != null && current.isAlive()) {
            ProcessUtils.forceStopTree(current);
        }
    }

    private void startReadinessMonitor(Consumer<String> output) {
        statusConsumer.accept(status());
        Thread.ofPlatform().daemon(true).name("fluxzero-dev-frontend-readiness").start(() -> {
            StableReadiness stableReadiness = new StableReadiness(
                    READY_STABILITY, UNAVAILABLE_STABILITY, Duration.ZERO);
            int failedProbes = 0;
            while (!closed.get()) {
                Process current = process;
                if (config.mode() == FrontendConfig.Mode.COMMAND && (current == null || !current.isAlive())) {
                    sleep(100);
                    continue;
                }
                boolean observedReady = probe();
                if (closed.get()) {
                    break;
                }
                Boolean confirmedReady = stableReadiness.observe(observedReady, System.nanoTime());
                if (confirmedReady != null) {
                    ready.set(confirmedReady);
                    failedProbes = 0;
                    if (confirmedReady) {
                        everReady = true;
                        recoveryUsed = false;
                        failureDetail = null;
                        unavailableSinceNanos = -1;
                    } else {
                        unavailableSinceNanos = System.nanoTime();
                    }
                    statusConsumer.accept(status());
                    output.accept(confirmedReady
                                          ? "[frontend] ready at " + internalUrl
                                          : "[frontend] unavailable at " + internalUrl);
                } else if (!observedReady && !ready.get() && ++failedProbes == 10) {
                    output.accept("[frontend] still waiting for " + internalUrl + ": " + probeFailure);
                    statusConsumer.accept(status());
                } else if (observedReady) {
                    failedProbes = 0;
                }
                recoverIfPersistentlyUnavailable(current, observedReady, output);
                sleep(PROBE_INTERVAL_MILLIS);
            }
            ready.set(false);
        });
    }

    private void runSetup(Consumer<String> output) throws IOException, InterruptedException {
        if (config.setupCommand() == null) {
            return;
        }
        setupRunning = true;
        statusConsumer.accept(status());
        output.accept("[frontend] running setup command");
        long startedNanos = System.nanoTime();
        try {
            Process started = ProcessUtils.start(
                    shellCommand(config.setupCommand(), devConfig.projectDirectory().toString()),
                    workingDirectory(), environment(), line -> output.accept("[frontend] [setup] " + line));
            setupProcess = started;
            if (closed.get()) {
                setupProcess = null;
                ProcessUtils.forceStopTree(started);
                return;
            }
            statusConsumer.accept(status());
            int exitCode = started.waitFor();
            if (closed.get()) {
                return;
            }
            if (exitCode != 0) {
                throw new IllegalStateException("Frontend setup command failed with exit code " + exitCode);
            }
            output.accept("[frontend] setup completed in "
                          + formatDuration(System.nanoTime() - startedNanos));
        } finally {
            setupProcess = null;
            setupRunning = false;
            if (!closed.get()) {
                statusConsumer.accept(status());
            }
        }
    }

    private void launchProcess(Consumer<String> output) throws IOException {
        if (closed.get()) {
            return;
        }
        String commandValue = config.command().replace("{port}", Integer.toString(port(internalUrl)));
        Process started = ProcessUtils.start(
                shellCommand(commandValue, devConfig.projectDirectory().toString()), workingDirectory(), environment(),
                line -> output.accept("[frontend] " + line));
        process = started;
        if (closed.get()) {
            process = null;
            ProcessUtils.forceStopTree(started);
            return;
        }
        recoveryInProgress.set(false);
        failureDetail = null;
        unavailableSinceNanos = -1;
        statusConsumer.accept(status());
        started.onExit().thenRun(() -> processExited(started));
    }

    private void processExited(Process exited) {
        if (closed.get() || process != exited) {
            return;
        }
        process = null;
        ready.set(false);
        if (recoveryUsed) {
            recoveryInProgress.set(false);
            failureDetail = "frontend process exited after automatic restart";
            statusConsumer.accept(status());
            return;
        }
        recoveryUsed = true;
        recoveryInProgress.set(true);
        statusConsumer.accept(status());
        Consumer<String> sink = output;
        if (sink != null) {
            sink.accept("[frontend] process exited unexpectedly; restarting once");
        }
        Thread.ofPlatform().daemon(true).name("fluxzero-dev-frontend-restart").start(() -> {
            sleep(RESTART_DELAY.toMillis());
            try {
                launchProcess(sink == null ? ignored -> {
                } : sink);
            } catch (IOException e) {
                recoveryInProgress.set(false);
                failureDetail = "failed to restart frontend: " + e.getMessage();
                statusConsumer.accept(status());
            }
        });
    }

    private void recoverIfPersistentlyUnavailable(Process observedProcess, boolean observedReady,
                                                   Consumer<String> output) {
        if (!observedReady && everReady && !ready.get() && unavailableSinceNanos < 0
            && !recoveryInProgress.get()) {
            unavailableSinceNanos = System.nanoTime();
        }
        if (config.mode() != FrontendConfig.Mode.COMMAND || observedReady || !everReady || ready.get()
            || unavailableSinceNanos < 0 || observedProcess == null || process != observedProcess) {
            return;
        }
        long unavailableNanos = System.nanoTime() - unavailableSinceNanos;
        if (unavailableNanos < devConfig.startupTimeout().toNanos()) {
            return;
        }
        if (recoveryUsed) {
            if (failureDetail == null) {
                failureDetail = "frontend remained unavailable after automatic restart";
                statusConsumer.accept(status());
            }
            return;
        }
        recoveryUsed = true;
        recoveryInProgress.set(true);
        process = null;
        unavailableSinceNanos = -1;
        statusConsumer.accept(status());
        output.accept("[frontend] remained unavailable for " + formatDuration(unavailableNanos)
                      + "; restarting once");
        ProcessUtils.forceStopTree(observedProcess);
        try {
            launchProcess(output);
        } catch (IOException e) {
            recoveryInProgress.set(false);
            failureDetail = "failed to restart frontend: " + e.getMessage();
            statusConsumer.accept(status());
        }
    }

    private boolean probe() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(internalUrl).toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout((int) PROBE_TIMEOUT.toMillis());
            connection.setReadTimeout((int) PROBE_TIMEOUT.toMillis());
            connection.setRequestMethod("GET");
            int statusCode = connection.getResponseCode();
            probeFailure = "HTTP " + statusCode;
            return statusCode < 500;
        } catch (Exception e) {
            probeFailure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String waitingDetail(String message) {
        return probeFailure == null ? message : message + " (" + probeFailure + ")";
    }

    private Path workingDirectory() {
        Path configured = config.directory() == null ? devConfig.projectDirectory() : Path.of(config.directory());
        Path result = configured.isAbsolute()
                ? configured.normalize() : devConfig.projectDirectory().resolve(configured).normalize();
        if (!Files.isDirectory(result)) {
            throw new IllegalStateException("Frontend directory does not exist: " + result);
        }
        return result;
    }

    private Map<String, String> environment() {
        int port = port(internalUrl);
        Map<String, String> result = new HashMap<>();
        result.put("PORT", Integer.toString(port));
        result.put("FLUXZERO_FRONTEND_PORT", Integer.toString(port));
        result.put("FLUXZERO_PROXY_URL", DevGateway.BACKEND_PREFIX);
        result.put("FLUXZERO_DEV_BACKEND_PATH", DevGateway.BACKEND_PREFIX);
        return result;
    }

    private static String formatDuration(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1_000_000_000d);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private static Integer port(String url) {
        int port = URI.create(url).getPort();
        return port < 0 ? null : port;
    }

    private static List<String> shellCommand(String command, String projectDirectory) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String marker = "fluxzero-dev-project=" + projectDirectory;
        return windows
                ? List.of("cmd", "/d", "/s", "/c", command + " & rem " + marker)
                : List.of("sh", "-c", command, marker);
    }
}
