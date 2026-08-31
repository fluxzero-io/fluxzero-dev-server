/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class VersionAlignedDevRuntime implements AutoCloseable {
    private static final Duration CONTROLLED_STOP_TIMEOUT = Duration.ofMillis(250);

    private final String version;
    private final DevRuntimeArtifactResolver.ResolvedRuntime artifacts;
    private final Consumer<String> registrationConsumer;
    private final Consumer<ProcessUtils.ProcessOutput> outputConsumer;
    private final Consumer<String> failureConsumer;
    private final CompletableFuture<Ready> ready = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Process process;

    static VersionAlignedDevRuntime start(
            DevServerConfig config,
            String sessionId,
            FluxzeroSdkVersionDetector.Selection selection,
            int proxyPort,
            Consumer<String> registrationConsumer,
            Consumer<ProcessUtils.ProcessOutput> outputConsumer,
            Consumer<String> failureConsumer
    ) {
        DevRuntimeArtifactResolver.ResolvedRuntime artifacts = new DevRuntimeArtifactResolver().resolve(
                selection.version());
        VersionAlignedDevRuntime runtime = new VersionAlignedDevRuntime(
                selection.version(), artifacts, registrationConsumer, outputConsumer, failureConsumer);
        runtime.startProcess(config, sessionId, proxyPort);
        return runtime;
    }

    VersionAlignedDevRuntime(
            String version,
            DevRuntimeArtifactResolver.ResolvedRuntime artifacts,
            Consumer<String> registrationConsumer,
            Consumer<ProcessUtils.ProcessOutput> outputConsumer,
            Consumer<String> failureConsumer
    ) {
        this.version = version;
        this.artifacts = artifacts;
        this.registrationConsumer = registrationConsumer;
        this.outputConsumer = outputConsumer;
        this.failureConsumer = failureConsumer;
    }

    Ready awaitReady(Duration timeout) {
        try {
            return ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            close();
            Throwable failure = e.getCause() == null ? e : e.getCause();
            throw new DevServerStartupException(
                    "Fluxzero runtime " + version + " did not become ready: " + oneLine(failure.getMessage()), failure);
        }
    }

    long pid() {
        Process current = process;
        return current == null ? -1 : current.pid();
    }

    boolean cached() {
        return artifacts.cached();
    }

    Map<String, String> metadata() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("sdkVersion", version);
        result.put("mode", "isolated");
        result.put("artifactCache", artifacts.cached() ? "hit" : "miss");
        ProcessUtils.startedAt(process).ifPresent(startedAt -> result.put(
                ProcessUtils.PROCESS_STARTED_AT, Long.toString(startedAt)));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) {
            return;
        }
        try {
            BufferedWriter writer = current.outputWriter(StandardCharsets.UTF_8);
            writer.write("stop");
            writer.newLine();
            writer.flush();
            if (!current.waitFor(CONTROLLED_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessUtils.forceStopTree(current);
            }
        } catch (Exception e) {
            ProcessUtils.forceStopTree(current);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startProcess(DevServerConfig config, String sessionId, int proxyPort) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dfluxzero.dev.session=" + sessionId);
        command.add("-Dfluxzero.dev.project=" + config.projectDirectory());
        command.add("-cp");
        command.add(bootstrapClasspath());
        command.add(DevRuntimeProcessMain.class.getName());
        command.add("--classpath-file");
        command.add(artifacts.classpathFile().toString());
        command.add("--parent-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add("--proxy-port");
        command.add(Integer.toString(proxyPort));
        command.add("--lookback-millis");
        command.add("10000");
        command.add("--version");
        command.add(version);
        if (config.namespace() != null && !config.namespace().isBlank()) {
            command.add("--namespace");
            command.add(config.namespace());
        }
        try {
            process = ProcessUtils.startWithStreams(command, config.projectDirectory(), Map.of(), this::processOutput);
            Process started = process;
            started.onExit().thenAccept(ignored -> {
                if (!closed.get()) {
                    String detail = "runtime process exited with code " + started.exitValue();
                    if (!ready.completeExceptionally(new IllegalStateException(detail))) {
                        failureConsumer.accept(detail);
                    }
                }
            });
        } catch (IOException e) {
            throw new DevServerStartupException("Could not start Fluxzero runtime " + version + ": " + e.getMessage(), e);
        }
    }

    private void processOutput(ProcessUtils.ProcessOutput output) {
        String line = output.line();
        if ("stdout".equals(output.stream()) && line.startsWith(DevRuntimeProcessMain.CONTROL_PREFIX)) {
            consumeControl(line.substring(DevRuntimeProcessMain.CONTROL_PREFIX.length()));
            return;
        }
        outputConsumer.accept(output);
    }

    private void consumeControl(String control) {
        String[] fields = control.split("\\t", -1);
        try {
            switch (fields[0]) {
                case "READY" -> ready.complete(new Ready(
                        Integer.parseInt(DevRuntimeProcessMain.decode(fields[1])),
                        Integer.parseInt(DevRuntimeProcessMain.decode(fields[2])),
                        DevRuntimeProcessMain.decode(fields[3])));
                case "CONNECT" -> registrationConsumer.accept(DevRuntimeProcessMain.decode(fields[1]));
                case "WARNING" -> outputConsumer.accept(new ProcessUtils.ProcessOutput(
                        "stderr", DevRuntimeProcessMain.decode(fields[1])));
                case "ERROR" -> {
                    String detail = DevRuntimeProcessMain.decode(fields[1]);
                    if (!ready.completeExceptionally(new IllegalStateException(detail))) {
                        failureConsumer.accept(detail);
                    }
                }
                default -> outputConsumer.accept(new ProcessUtils.ProcessOutput(
                        "stderr", "Unknown runtime control event " + fields[0]));
            }
        } catch (RuntimeException e) {
            String detail = "Invalid runtime control event " + fields[0];
            if (!ready.completeExceptionally(new IllegalStateException(detail, e))) {
                failureConsumer.accept(detail);
            }
        }
    }

    private static String bootstrapClasspath() {
        try {
            return Path.of(DevRuntimeProcessMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate Dev Server runtime bootstrap", e);
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                       System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private static String oneLine(String value) {
        return value == null || value.isBlank() ? "unknown startup failure"
                : value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    record Ready(int runtimePort, int proxyPort, String version) {
    }
}
