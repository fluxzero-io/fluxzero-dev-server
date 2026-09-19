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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DevMonitoringResourcesTest {
    @TempDir Path project;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<String> auditBody = new AtomicReference<>("{\"heapUsedBytes\":123}");
    private final AtomicReference<String> metricsBody = new AtomicReference<>(metrics(32_000_000));
    private final AtomicInteger metricsStatus = new AtomicInteger(200);
    private final AtomicInteger metricsRequests = new AtomicInteger();
    private final AtomicReference<String> namespace = new AtomicReference<>();
    private final CountDownLatch auditEntered = new CountDownLatch(1);
    private final AtomicReference<CountDownLatch> auditBlocked = new AtomicReference<>();
    private HttpServer audit, database;
    private String proxyUrl, storageUrl;
    private Path data;
    private HttpClient http;
    private DevMonitoring monitoring;

    @BeforeEach void setUp() throws Exception {
        data = Files.createDirectories(project.resolve(".fluxzero/dev/monitoring/victorialogs"));
        Files.write(data.resolve("data.bin"), new byte[128]);
        var config = new DevMonitoringConfig("auditlog.jar", "ui", "victorialogs", null, null, null, null, null, 1024L);
        monitoring = new DevMonitoring(config, project, "resources-test", (id, state) -> {}, line -> {});
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        audit = server();
        audit.createContext("/logs/local/status", exchange -> {
            try (exchange) {
                namespace.set(exchange.getRequestHeaders().getFirst(DevNamespaceHeader.NAME));
                auditEntered.countDown();
                CountDownLatch blocked = auditBlocked.get();
                if (blocked != null) {
                    try { blocked.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                byte[] bytes = auditBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        audit.start();
        proxyUrl = "http://127.0.0.1:" + audit.getAddress().getPort();
        database = server();
        database.createContext("/metrics", exchange -> {
            try (exchange) {
                metricsRequests.incrementAndGet();
                byte[] bytes = metricsBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(metricsStatus.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        database.start();
        storageUrl = "http://127.0.0.1:" + database.getAddress().getPort();
    }

    private HttpServer server() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        return server;
    }

    @AfterEach void tearDown() {
        CountDownLatch blocked = auditBlocked.get();
        if (blocked != null) blocked.countDown();
        if (audit != null) audit.stop(0);
        if (database != null) database.stop(0);
        if (http != null) http.close();
        executor.shutdownNow();
    }

    @Test void keepsFreshDatabaseAndDiskMeasurementsWhenAuditlogStops() throws Exception {
        Map<String, Object> healthy = sample();
        assertFalse(healthy.containsKey("error"));
        assertEquals(DevConsole.NAMESPACE, namespace.get());
        assertEquals(28_000_000L, healthy.get("storageMemoryUsedBytes"));
        audit.stop(0); audit = null;
        metricsBody.set(metrics(40_000_000));
        Files.write(data.resolve("more.bin"), new byte[256]);

        Map<String, Object> stopped = sample();
        assertFalse(stopped.containsKey("auditlog"));
        assertEquals(36_000_000L, stopped.get("storageMemoryUsedBytes"));
        assertEquals(134_217_728L, stopped.get("storageMemoryMaxBytes"));
        assertEquals(99_000_000L, stopped.get("storageRssBytes"));
        assertEquals(384L, stopped.get("storageDiskBytes"));
        assertEquals(1024L, stopped.get("diskRetentionThresholdBytes"));
        assertEquals(false, stopped.get("diskThresholdExceeded"));
        assertNotNull(stopped.get("sampledAt"));
        assertEquals("Monitoring application resource measurement unavailable", stopped.get("error"));
    }

    @Test void keepsDatabaseAndDiskMeasurementsAfterAuditlogTimesOut() throws Exception {
        auditBlocked.set(new CountDownLatch(1));
        Map<String, Object> result;
        try { result = sample(); }
        finally { auditBlocked.get().countDown(); }
        assertEquals(0, auditEntered.getCount());
        assertEquals(28_000_000L, result.get("storageMemoryUsedBytes"));
        assertEquals(128L, result.get("storageDiskBytes"));
        assertEquals("Monitoring application resource measurement unavailable", result.get("error"));
    }

    @Test void isolatesInvalidAuditlogResponses() throws Exception {
        for (String invalid : new String[]{"invalid-json", "null"}) {
            auditBody.set(invalid);
            Map<String, Object> result = sample();
            assertFalse(result.containsKey("auditlog"));
            assertEquals(28_000_000L, result.get("storageMemoryUsedBytes"));
            assertEquals(128L, result.get("storageDiskBytes"));
            assertNotNull(result.get("error"));
        }
    }

    @Test void keepsAuditlogAndDiskMeasurementsWhenDatabaseMetricsFail() throws Exception {
        assertNotNull(sample().get("storageMemoryUsedBytes"));
        metricsStatus.set(503); // Even a parseable error response must not become a memory sample.
        Map<String, Object> unavailable = sample();
        assertEquals("Monitoring database resource status returned HTTP 503", unavailable.get("error"));
        assertEquals(Map.of("heapUsedBytes", 123), unavailable.get("auditlog"));
        assertFalse(unavailable.containsKey("storageMemoryUsedBytes"));
        assertEquals(128L, unavailable.get("storageDiskBytes"));

        database.stop(0); database = null;
        Files.write(data.resolve("more.bin"), new byte[1024]);
        Map<String, Object> stopped = sample();
        assertEquals(Map.of("heapUsedBytes", 123), stopped.get("auditlog"));
        assertFalse(stopped.containsKey("storageMemoryUsedBytes"));
        assertEquals(1152L, stopped.get("storageDiskBytes"));
        assertEquals(true, stopped.get("diskThresholdExceeded"));
    }

    @Test void keepsNetworkMeasurementsAndConfiguredLimitWhenDiskMeasurementFails() throws Exception {
        Files.delete(data.resolve("data.bin")); Files.delete(data);
        Map<String, Object> result = sample();
        assertNotNull(result.get("auditlog"));
        assertEquals(28_000_000L, result.get("storageMemoryUsedBytes"));
        assertEquals(1024L, result.get("diskRetentionThresholdBytes"));
        assertFalse(result.containsKey("storageDiskBytes"));
        assertFalse(result.containsKey("diskThresholdExceeded"));
        assertEquals("Monitoring storage resource measurement unavailable", result.get("error"));
    }

    @Test void skipsExternalDatabaseMeasurementsForTestserverStorage() throws Exception {
        var result = monitoring.collectResources(http, proxyUrl, null);
        assertNotNull(result.get("auditlog"));
        assertFalse(result.containsKey("storageDiskBytes"));
        assertFalse(result.containsKey("error"));
        assertEquals(0, metricsRequests.get());
    }

    @Test void propagatesInterruptionInsteadOfContinuingAfterShutdown() throws Exception {
        auditBlocked.set(new CountDownLatch(1));
        var failure = new AtomicReference<Throwable>();
        Thread sampler = Thread.ofVirtual().start(() -> {
            try { sample(); }
            catch (Throwable e) { failure.set(e); }
        });
        try {
            assertTrue(auditEntered.await(5, TimeUnit.SECONDS));
            sampler.interrupt(); sampler.join(5000);
            assertFalse(sampler.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertEquals(0, metricsRequests.get());
        } finally { sampler.interrupt(); auditBlocked.get().countDown(); }
    }

    private Map<String, Object> sample() throws InterruptedException {
        return monitoring.collectResources(http, proxyUrl, storageUrl);
    }

    private static String metrics(long sys) {
        return "process_resident_memory_bytes 99000000\n"
                + "go_memstats_sys_bytes " + sys + "\n"
                + "go_memstats_heap_released_bytes 4000000\n"
                + "go_memlimit_bytes 134217728\n";
    }
}
