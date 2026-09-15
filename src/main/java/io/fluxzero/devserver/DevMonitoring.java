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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Owns local Auditlog and its optional native storage until the enclosing dev session stops. */
final class DevMonitoring implements AutoCloseable {
    private final DevMonitoringConfig config;
    private final Path project;
    private final String sessionId;
    private final BiConsumer<String, DevSession.ServiceStatus> statuses;
    private final Consumer<String> log;
    private final Map<String, Process> children = new ConcurrentHashMap<>();
    private final java.util.concurrent.ArrayBlockingQueue<Map<String, Object>> logs = new java.util.concurrent.ArrayBlockingQueue<>(1000);
    private final java.util.concurrent.atomic.AtomicLong droppedLogs = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;
    private volatile Thread logWorker;
    private volatile Thread resourceWorker;
    private volatile Map<String, Object> resources = Map.of();
    private String storageUrl;
    private Long runtimePid;
    private volatile String state = "starting";

    DevMonitoring(DevMonitoringConfig config, Path project, String sessionId,
                  BiConsumer<String, DevSession.ServiceStatus> statuses, Consumer<String> log) {
        this.config = config; this.project = project; this.sessionId = sessionId;
        this.statuses = statuses; this.log = log;
    }

    String storage() { return config.storage(); }

    void start(String runtimeUrl, String proxyUrl, List<String> sourceNamespaces, Long runtimePid) {
        this.runtimePid = runtimePid;
        try {
            Path jar = config.resolve(project, config.auditlogJar());
            if (!Files.isRegularFile(jar) || !Files.isRegularFile(assets().resolve("index.html"))) {
                throw new IllegalArgumentException("Build monitoring.auditlogJar and monitoring.uiDirectory before starting monitoring");
            }
            if (sourceNamespaces.contains(DevConsole.NAMESPACE)) {
                throw new IllegalArgumentException("Application namespace conflicts with isolated monitoring namespace");
            }
            Map<String, String> env = new LinkedHashMap<>();
            env.put("ENVIRONMENT", "local"); env.put("FLUXZERO_BASE_URL", runtimeUrl);
            env.put("FLUXZERO_NAMESPACE", DevConsole.NAMESPACE); env.put("FLUXZERO_APPLICATION_NAME", "dev-auditlog");
            env.put("FLUXZERO_CLIENT_ID", sessionId + "-monitoring");
            env.put("AUDITLOG_LOCAL", "true"); env.put("AUDITLOG_SEED_LOCAL_ISSUES", "false");
            env.put("AUDITLOG_PUBLISHER", config.storage());
            env.put("TARGET_NAMESPACE", sourceNamespaces.getFirst());
            env.put("AUDITLOG_SOURCE_NAMESPACES", String.join(",", sourceNamespaces));
            env.put("CONSUMER_NAME_PREFIX", "dev-auditlog");
            env.put("AUDITLOG_MAX_RECORDS", config.maxRecords().toString());
            env.put("AUDITLOG_MAX_BYTES", config.maxBytes().toString());
            env.put("AUDITLOG_RETENTION", config.retention());
            env.put("AUDITLOG_MONITORING_CLIENT_PREFIX", sessionId + "-monitoring");
            if (config.storage().equals("victorialogs")) {
                Path binary = config.victoriaLogsBinary() == null ? VictoriaLogsArtifact.resolve(log)
                        : config.resolve(project, config.victoriaLogsBinary());
                Path data = project.resolve(".fluxzero/dev/monitoring/victorialogs"); Files.createDirectories(data);
                int port = ProcessUtils.availablePort();
                String url = "http://127.0.0.1:" + port;
                storageUrl = url;
                startProcess("monitoring-storage", List.of(binary.toString(), "-httpListenAddr=127.0.0.1:" + port,
                        "-storageDataPath=" + data, "-retentionPeriod=" + Duration.parse(config.retention()).toSeconds() + "s",
                        "-retention.maxDiskSpaceUsageBytes=" + config.maxDiskBytes(),
                        "-memory.allowedBytes=64MiB", "-search.maxConcurrentRequests=2"),
                        Map.of("GOMAXPROCS", "2", "GOMEMLIMIT", "128MiB"), url);
                await("monitoring-storage", url + "/health", false);
                env.put("VL_INGEST_URL", url); env.put("VL_QUERY_URL", url);
                env.put("VL_INGEST_FORCE_FLUSH_ALLOWED", "true");
            }
            String java = config.javaExecutable() == null ? Path.of(System.getProperty("java.home"), "bin", ProcessUtils.isWindows() ? "java.exe" : "java").toString()
                    : config.javaExecutable();
            startProcess("monitoring-auditlog", List.of(java, JvmHeapMemory.LOCAL_JMX_OPTION,
                    "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                    "-Xms32m", "-Xmx384m", "-jar", jar.toString()), env, null);
            await("monitoring-auditlog", proxyUrl + "/api/health", true);
            state = "running";
            resourceWorker = Thread.ofVirtual().name("monitoring-resources").start(() -> sampleResources(proxyUrl));
            logWorker = Thread.ofVirtual().name("monitoring-application-logs").start(() -> forwardLogs(proxyUrl));
        } catch (Exception e) {
            state = "failed"; close();
            throw new DevServerStartupException("Local monitoring could not start: " + e.getMessage(), e);
        }
    }

    private synchronized void startProcess(String id, List<String> args, Map<String, String> env, String url) throws Exception {
        if (closed) throw new IllegalStateException("Monitoring is stopped.");
        // Keep the marked shell alive so stale-session cleanup can identify the owned process tree.
        String command = launchCommand(args, ProcessUtils.isWindows());
        Process child = ProcessUtils.start(ProcessUtils.shellCommand(command, sessionId + "-service-" + id), project, env,
                                          line -> log.accept("[" + id + "] " + line));
        children.put(id, child);
        publish(id, child, "starting", url);
        child.onExit().thenRun(() -> { if (!closed) { state = "failed"; publish(id, child, "failed", url); } });
    }

    private void await(String id, String url, boolean namespace) throws Exception {
        Process child = children.get(id);
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            while (!closed && child.isAlive() && System.nanoTime() < deadline) {
                try {
                    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2));
                    if (namespace) request.header(DevNamespaceHeader.NAME, DevConsole.NAMESPACE);
                    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && (!namespace || response.body().contains("healthy"))) {
                        publish(id, child, "running", namespace ? null : url.replace("/health", "")); return;
                    }
                } catch (java.io.IOException ignored) { }
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException(id + " did not become ready");
    }

    private void publish(String id, Process child, String state, String url) {
        statuses.accept(id, new DevSession.ServiceStatus(id, state, url, null, child.pid(), "local monitoring " + state,
                Map.of("mode", "managed", ProcessUtils.PROCESS_STARTED_AT,
                       Long.toString(ProcessUtils.startedAt(child).orElse(0L)))));
    }

    Path assets() { return config.resolve(project, config.uiDirectory()); }
    int serviceCount() { return config.storage().equals("victorialogs") ? 2 : 1; }
    Map<String, Object> status() {
        return Map.of("enabled", true, "state", state, "storage", config.storage(), "retention", config.retention(),
                "droppedLogLines", droppedLogs.get(), "resources", resources, "detail", config.storage() + " · retention " + config.retention());
    }
    JvmHeapMemory.Usage memoryUsage() {
        Object used = resources.get("storageMemoryUsedBytes"), max = resources.get("storageMemoryMaxBytes");
        return used instanceof Number value ? new JvmHeapMemory.Usage(value.longValue(),
                max instanceof Number limit ? limit.longValue() : null) : null;
    }

    static JvmHeapMemory.Usage goMemory(String metrics) {
        Map<String, Long> values = new HashMap<>();
        metrics.lines().forEach(line -> {
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 2 || !Set.of("go_memstats_sys_bytes", "go_memstats_heap_released_bytes", "go_memlimit_bytes").contains(parts[0])) return;
            try {
                double value = Double.parseDouble(parts[1]);
                if (Double.isFinite(value) && value >= 0 && value < Long.MAX_VALUE) values.put(parts[0], (long) value);
            } catch (NumberFormatException ignored) {}
        });
        Long sys = values.get("go_memstats_sys_bytes"), released = values.get("go_memstats_heap_released_bytes");
        Long max = values.get("go_memlimit_bytes");
        if (sys == null || released == null || released > sys) return null;
        return new JvmHeapMemory.Usage(sys - released, max != null && max > 0 ? max : null);
    }

    static String launchCommand(List<String> args, boolean windows) {
        if (windows) {
            // cmd keeps the ownership marker while the quoted executable runs synchronously.
            if (args.stream().anyMatch(a -> a.contains("\"") || a.contains("%") || a.contains("\n") || a.contains("\r"))) {
                throw new IllegalArgumentException("Unsupported character in monitoring process argument");
            }
            return args.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining(" "));
        }
        return args.stream().map(DevMonitoring::quote).collect(Collectors.joining(" ")) + " & wait $!";
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    void applicationLog(String application, String instance, String stream, String line) {
        String clipped = line.substring(0, Math.min(4096, line.length()));
        if (!logs.offer(Map.of("application", application, "instance", instance, "stream", stream,
                               "line", clipped, "timestamp", java.time.Instant.now().toString()))) droppedLogs.incrementAndGet();
    }

    private void forwardLogs(String proxyUrl) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            while (!closed) {
                var first = logs.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (first == null) continue;
                var batch = new java.util.ArrayList<Map<String, Object>>(); batch.add(first); logs.drainTo(batch, 99);
                try {
                    var request = HttpRequest.newBuilder(URI.create(proxyUrl + "/logs/local/application-logs"))
                            .timeout(Duration.ofSeconds(3)).header(DevNamespaceHeader.NAME, DevConsole.NAMESPACE)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(batch))).build();
                    var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() >= 300) droppedLogs.addAndGet(batch.size());
                } catch (java.io.IOException e) { droppedLogs.addAndGet(batch.size()); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }


    private static Long residentBytes(Long pid) throws InterruptedException {
        if (pid == null) return null;
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).start();
            if (!ps.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { ps.destroyForcibly(); return null; }
            if (ps.exitValue() != 0) return null;
            return Long.parseLong(new String(ps.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim()) * 1024;
        } catch (java.io.IOException | NumberFormatException ignored) { return null; }
    }

    private void sampleResources(String proxyUrl) {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            while (!closed) {
                resources = collectResources(http, proxyUrl, storageUrl);
                Thread.sleep(5000);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    Map<String, Object> collectResources(HttpClient http, String proxyUrl, String storageUrl) throws InterruptedException {
        Map<String, Object> sample = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        // Each source has its own failure boundary. Never carry an old value into a new sample.
        measureResource(errors, "Monitoring application", () -> {
            var response = http.send(HttpRequest.newBuilder(URI.create(proxyUrl + "/logs/local/status"))
                    .header(DevNamespaceHeader.NAME, DevConsole.NAMESPACE).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                errors.add("Monitoring application resource status returned HTTP " + response.statusCode());
                return;
            }
            var status = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
            if (status == null) throw new IOException("Missing resource status");
            sample.put("auditlog", status);
        });
        if (storageUrl != null) {
            measureResource(errors, "Monitoring database", () -> {
                var response = http.send(HttpRequest.newBuilder(URI.create(storageUrl + "/metrics"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    errors.add("Monitoring database resource status returned HTTP " + response.statusCode());
                    return;
                }
                String metrics = response.body();
                JvmHeapMemory.Usage memory = goMemory(metrics);
                if (memory == null) throw new IOException("Missing memory metrics");
                sample.put("storageMemoryUsedBytes", memory.used());
                if (memory.max() != null) sample.put("storageMemoryMaxBytes", memory.max());
                metrics.lines().filter(l -> l.startsWith("process_resident_memory_bytes ")).findFirst()
                        .ifPresent(l -> sample.put("storageRssBytes", (long) Double.parseDouble(l.substring(l.indexOf(' ') + 1))));
            });
            sample.put("diskRetentionThresholdBytes", config.maxDiskBytes());
            measureResource(errors, "Monitoring storage", () -> {
                try (var files = Files.walk(project.resolve(".fluxzero/dev/monitoring/victorialogs"))) {
                    long bytes = files.filter(Files::isRegularFile).mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }).sum();
                    sample.put("storageDiskBytes", bytes);
                    sample.put("diskThresholdExceeded", bytes > config.maxDiskBytes());
                }
            });
        }
        if (!ProcessUtils.isWindows()) {
            measureResource(errors, "Testserver process", () -> {
                Long rss = residentBytes(runtimePid);
                if (rss != null) sample.put("runtimeRssBytes", rss);
            });
            measureResource(errors, "Monitoring database process", () -> {
                Process storage = children.get("monitoring-storage");
                if (storage != null && !sample.containsKey("storageRssBytes")) {
                    Long rss = residentBytes(storage.children().findFirst().map(ProcessHandle::pid).orElse(null));
                    if (rss != null) sample.put("storageRssBytes", rss);
                }
            });
        }
        sample.put("sampledAt", java.time.Instant.now().toString());
        if (!errors.isEmpty()) sample.put("error", String.join("; ", errors));
        return Map.copyOf(sample);
    }

    private static void measureResource(List<String> errors, String source, ResourceMeasurement measurement)
            throws InterruptedException {
        try { measurement.collect(); }
        catch (IOException | RuntimeException e) { errors.add(source + " resource measurement unavailable"); }
    }

    @FunctionalInterface
    private interface ResourceMeasurement {
        void collect() throws IOException, InterruptedException;
    }

    @Override public synchronized void close() {
        closed = true;
        if (!"failed".equals(state)) state = "stopped";
        if (logWorker != null) logWorker.interrupt();
        if (resourceWorker != null) resourceWorker.interrupt();
        for (String id : List.of("monitoring-auditlog", "monitoring-storage")) {
            Process child = children.get(id);
            if (child != null) {
                ProcessUtils.stopTree(child, Duration.ofSeconds(5));
                if (child.isAlive()) throw new IllegalStateException("Could not stop " + id + "; storage has not been cleared.");
                children.remove(id, child);
                publish(id, child, "stopped", null);
            }
        }
    }
}
