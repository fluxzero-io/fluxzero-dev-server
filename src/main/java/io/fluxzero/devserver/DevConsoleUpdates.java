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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One bounded, server-owned projection shared by all console connections. */
public final class DevConsoleUpdates implements AutoCloseable {
    static final long SAMPLE_INTERVAL = 5_000;
    static final int HISTORY_BUCKETS = 60;
    static final int MAX_CLIENTS = 32;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<?> statusSupplier;
    private final Supplier<?> environmentsSupplier;
    private final LongSupplier clock;
    private final ArrayDeque<ObjectNode> history = new ArrayDeque<>();
    private final Set<Connection> connections = new HashSet<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("dev-console-updates").factory());
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private ObjectNode status;
    private JsonNode environments;
    private long sequence;
    private long lastHeartbeat;
    private boolean closed;

    DevConsoleUpdates(Supplier<?> status, Supplier<?> environments) {
        this(status, environments, System::currentTimeMillis);
    }

    DevConsoleUpdates(Supplier<?> status, Supplier<?> environments, LongSupplier clock) {
        this.statusSupplier = status;
        this.environmentsSupplier = environments;
        this.clock = clock;
    }

    void start() { worker.scheduleWithFixedDelay(this::refreshSafely, 0, 1, TimeUnit.SECONDS); }

    void refreshSoon() {
        if (!worker.isShutdown() && refreshQueued.compareAndSet(false, true)) {
            try { worker.schedule(() -> { refreshQueued.set(false); refreshSafely(); }, 100, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { refreshQueued.set(false); }
        }
    }

    private void refreshSafely() {
        try { refresh(); }
        catch (Exception ignored) {
            // Do not claim a healthy live view when sampling fails. Reconnect gets a new coherent snapshot.
            synchronized (this) { for (Connection connection : Set.copyOf(connections)) connection.stop(); }
        }
    }

    synchronized void refresh() {
        if (closed) return;
        ObjectNode next = mapper.valueToTree(statusSupplier.get());
        JsonNode known = mapper.valueToTree(environmentsSupplier.get());
        long now = clock.getAsLong();
        ObjectNode changes = mapper.createObjectNode();
        next.properties().forEach(entry -> {
            if (!entry.getKey().equals("testOutput") && (status == null || !entry.getValue().equals(status.get(entry.getKey())))) changes.set(entry.getKey(), entry.getValue());
        });
        if (status != null) status.fieldNames().forEachRemaining(key -> { if (!next.has(key)) changes.putNull(key); });
        boolean knownChanged = !known.equals(environments);
        long bucket = Math.floorDiv(now, SAMPLE_INTERVAL);
        history.removeIf(sample -> Math.floorDiv(sample.path("at").asLong(), SAMPLE_INTERVAL) <= bucket - HISTORY_BUCKETS
                || sample.path("at").asLong() > now);
        ObjectNode sample = null;
        if (history.isEmpty() || Math.floorDiv(history.getLast().path("at").asLong(), SAMPLE_INTERVAL) < bucket) {
            sample = mapper.createObjectNode().put("at", now);
            sample.set("applicationMemory", totalMemory(next.path("components"), true, "memoryUsedBytes"));
            sample.set("devserverMemory", totalMemory(next.path("components"), false, "memoryUsedBytes"));
            sample.set("componentMemory", componentMemory(next.path("components"), "memoryUsedBytes"));
            sample.set("applicationMemoryMax", totalMemory(next.path("components"), true, "memoryMaxBytes"));
            sample.set("devserverMemoryMax", totalMemory(next.path("components"), false, "memoryMaxBytes"));
            sample.set("componentMemoryMax", componentMemory(next.path("components"), "memoryMaxBytes"));
            sample.set("monitoringStorage", next.path("monitoring").path("resources").path("storageDiskBytes").isNumber()
                    ? next.path("monitoring").path("resources").get("storageDiskBytes") : mapper.nullNode());
            sample.set("monitoringStorageMax", next.path("monitoring").path("resources").path("diskRetentionThresholdBytes").isNumber()
                    ? next.path("monitoring").path("resources").get("diskRetentionThresholdBytes") : mapper.nullNode());
            history.add(sample);
            while (history.size() > HISTORY_BUCKETS) history.removeFirst();
        }
        ObjectNode output = null;
        if (next.has("testOutput") && (status == null || !next.get("testOutput").equals(status.get("testOutput")))) {
            JsonNode lines = next.get("testOutput");
            JsonNode previous = status == null ? mapper.createArrayNode() : status.path("testOutput");
            long after = previous.isEmpty() ? 0 : previous.path(previous.size()-1).path("sequence").asLong();
            var added = mapper.createArrayNode();
            lines.forEach(line -> {if(line.path("sequence").asLong()>after)added.add(line);});
            output = mapper.createObjectNode().put("firstSequence", lines.isEmpty()?0:lines.path(0).path("sequence").asLong());
            output.set("lines",added);
        }
        status = next;
        environments = known;
        if (!changes.isEmpty() || knownChanged || sample != null || output != null) {
            ObjectNode update = message("update", ++sequence);
            if (!changes.isEmpty()) update.set("status", changes);
            if (knownChanged) update.set("environments", known);
            if (sample != null) update.set("sample", sample);
            if (output != null) update.set("output", output);
            broadcast(update);
        } else if (now - lastHeartbeat >= 10_000) {
            broadcast(message("heartbeat", sequence));
        }
        for (Connection connection : Set.copyOf(connections)) connection.checkSlow(now);
    }

    private JsonNode totalMemory(JsonNode components, boolean application, String field) {
        long total = 0;
        boolean found = false;
        for (JsonNode component : components) {
            if (component.path("application").asBoolean() != application) continue;
            found = true;
            JsonNode memory = memory(component, field);
            if (memory.isNull()) return memory;
            total += memory.asLong();
        }
        return found ? mapper.valueToTree(total) : mapper.nullNode();
    }

    private ObjectNode componentMemory(JsonNode components, String field) {
        ObjectNode result = mapper.createObjectNode();
        for (JsonNode component : components) {
            String id = component.path("id").asText("");
            if (!id.isBlank()) result.set(id, memory(component, field));
        }
        return result;
    }

    private JsonNode memory(JsonNode component, String field) {
        JsonNode value = "memoryUsedBytes".equals(field) && !component.has(field) ? component.path("memoryBytes") : component.path(field);
        if (value.isNumber()) return value;
        return component.path("runningProcesses").asInt("running".equals(component.path("state").asText()) ? 1 : 0) > 0
                ? mapper.nullNode() : mapper.valueToTree(0L);
    }

    synchronized ObjectNode snapshot() {
        if (status == null) refresh();
        ObjectNode result = message("snapshot", sequence);
        ObjectNode current = status.deepCopy();
        current.set("resourceHistory", mapper.valueToTree(history));
        result.set("status", current);
        result.set("environments", environments.deepCopy());
        return result;
    }

    private ObjectNode message(String type, long sequence) {
        return mapper.createObjectNode().put("type", type).put("version", 1).put("sequence", sequence);
    }

    private void broadcast(ObjectNode message) {
        lastHeartbeat = clock.getAsLong();
        String json = message.toString();
        for (Connection connection : Set.copyOf(connections)) connection.offer(json);
    }

    Connection connection() { return new Connection(); }

    @Override public synchronized void close() {
        closed = true;
        worker.shutdownNow();
        for (Connection connection : Set.copyOf(connections)) connection.stop();
        history.clear();
    }

    public final class Connection extends Session.Listener.AbstractAutoDemanding {
        private final ArrayDeque<String> outgoing = new ArrayDeque<>();
        private Session session;
        private boolean sending;
        private boolean stopped;
        private long sendStarted;
        private int queuedBytes;

        @Override public void onWebSocketOpen(Session session) {
            synchronized (DevConsoleUpdates.this) {
                this.session = session;
                session.setMaxTextMessageSize(1024);
                session.setMaxBinaryMessageSize(1024);
                session.setIdleTimeout(Duration.ofSeconds(30));
                if (closed || connections.size() >= MAX_CLIENTS) { stop(); return; }
                try {
                    // Registration and snapshot are atomic relative to subsequent deltas.
                    String initial = snapshot().toString();
                    connections.add(this);
                    offer(initial);
                } catch (Exception ignored) { stop(); }
            }
        }

        private void offer(String json) {
            if (stopped) return;
            if (outgoing.size() >= 16 || queuedBytes + json.length() > 1_048_576) { stop(); return; }
            outgoing.add(json);
            queuedBytes += json.length();
            sendNext();
        }

        private void sendNext() {
            if (sending || stopped || outgoing.isEmpty()) return;
            sending = true;
            sendStarted = clock.getAsLong();
            String json = outgoing.getFirst();
            try {
                session.sendText(json, Callback.from(() -> {
                    synchronized (DevConsoleUpdates.this) {
                        if (stopped) return;
                        queuedBytes -= outgoing.removeFirst().length();
                        sending = false;
                        sendNext();
                    }
                }, failure -> { synchronized (DevConsoleUpdates.this) { stop(); } }));
            } catch (Exception ignored) { stop(); }
        }

        private void checkSlow(long now) { if (sending && now - sendStarted > 10_000) stop(); }
        private void stop() {
            if (stopped) return;
            stopped = true;
            connections.remove(this);
            outgoing.clear();
            queuedBytes = 0;
            if (session != null) session.disconnect();
        }
        @Override public void onWebSocketText(String ignored) { synchronized (DevConsoleUpdates.this) { stop(); } }
        @Override public void onWebSocketBinary(java.nio.ByteBuffer ignored, Callback callback) {
            callback.succeed();
            synchronized (DevConsoleUpdates.this) { stop(); }
        }
        @Override public void onWebSocketClose(int code, String reason, Callback callback) {
            synchronized (DevConsoleUpdates.this) { stop(); }
            callback.succeed();
        }
        @Override public void onWebSocketError(Throwable cause) { synchronized (DevConsoleUpdates.this) { stop(); } }
    }
}
