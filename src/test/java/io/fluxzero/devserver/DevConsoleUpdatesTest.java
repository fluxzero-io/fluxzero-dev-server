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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DevConsoleUpdatesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String,Object> status(long memory, long storage) {
        return Map.of("project", "example", "projectDirectory", "/example", "tests", "running",
                "components", List.of(Map.of("id", "devserver", "application", false, "state", "running", "memoryBytes", memory),
                        Map.of("id", "app", "application", true, "state", "stopped", "runningProcesses", 0)),
                "monitoring", Map.of("resources", Map.of("storageDiskBytes", storage, "diskRetentionThresholdBytes", 1L << 30)));
    }

    @Test void retainsUsageAndLimitsFromTheSameMeasurementInsteadOfRss() {
        var state = Map.of("components", List.of(
                Map.of("id", "devserver", "application", false, "state", "running", "memoryBytes", 900, "memoryUsedBytes", 100, "memoryMaxBytes", 400),
                Map.of("id", "storage", "application", false, "state", "running", "memoryBytes", 800, "memoryUsedBytes", 50, "memoryMaxBytes", 200)));
        try (var updates = new DevConsoleUpdates(() -> state, List::of, () -> 0)) {
            JsonNode sample = updates.snapshot().path("status").path("resourceHistory").get(0);
            assertEquals(150, sample.path("devserverMemory").asLong());
            assertEquals(600, sample.path("devserverMemoryMax").asLong());
            assertEquals(100, sample.path("componentMemory").path("devserver").asLong());
            assertEquals(400, sample.path("componentMemoryMax").path("devserver").asLong());
            assertEquals(200, sample.path("componentMemoryMax").path("storage").asLong());
        }
    }

    @Test void includesManagedFrontendsInInfrastructureHistory() {
        var state = Map.of("components", List.of(
                Map.of("id", "app-orders", "application", true, "state", "running", "memoryUsedBytes", 100, "memoryMaxBytes", 400),
                Map.of("id", "frontend-store", "application", true, "state", "running", "memoryUsedBytes", 50, "memoryMaxBytes", 200),
                Map.of("id", "devserver", "application", false, "state", "running", "memoryUsedBytes", 25, "memoryMaxBytes", 100)));
        try (var updates = new DevConsoleUpdates(() -> state, List::of, () -> 0)) {
            JsonNode sample = updates.snapshot().path("status").path("resourceHistory").get(0);
            assertEquals(100, sample.path("applicationMemory").asLong());
            assertEquals(400, sample.path("applicationMemoryMax").asLong());
            assertEquals(75, sample.path("devserverMemory").asLong());
            assertEquals(300, sample.path("devserverMemoryMax").asLong());
            assertEquals(50, sample.path("componentMemory").path("frontend-store").asLong());
        }
    }

    @Test void collectsWithoutBrowsersBoundsHistoryAndPreservesGapsAndUnknownMemory() {
        AtomicLong now = new AtomicLong();
        AtomicReference<Map<String,Object>> state = new AtomicReference<>(status(100, 50));
        try (var updates = new DevConsoleUpdates(state::get, List::of, now::get)) {
            for (int i = 0; i < 100; i++) { now.set(i * 5000L); updates.refresh(); }
            JsonNode samples = updates.snapshot().path("status").path("resourceHistory");
            assertEquals(60, samples.size());
            assertEquals(200000, samples.get(0).path("at").asLong());
            assertEquals(100, samples.get(59).path("devserverMemory").asLong());
            assertEquals(0, samples.get(59).path("applicationMemory").asLong());
            assertEquals(100, samples.get(59).path("componentMemory").path("devserver").asLong());
            assertEquals(0, samples.get(59).path("componentMemory").path("app").asLong());
            updates.refresh();
            assertEquals(60, updates.snapshot().path("status").path("resourceHistory").size());
            now.set(900000);
            state.set(Map.of("components", List.of(Map.of("id","devserver","application",false,"runningProcesses",1)),
                    "monitoring", Map.of("resources",Map.of("storageDiskBytes",0))));
            updates.refresh();
            samples = updates.snapshot().path("status").path("resourceHistory");
            assertEquals(1,samples.size());
            assertTrue(samples.get(0).path("devserverMemory").isNull());
            assertTrue(samples.get(0).path("componentMemory").path("devserver").isNull());
            assertEquals(0,samples.get(0).path("monitoringStorage").asLong());
            assertTrue(samples.get(0).path("monitoringStorageMax").isNull());
        }
    }

    @Test void retainsIndependentComponentMeasurementsAcrossStopsAndRestarts() {
        AtomicLong now = new AtomicLong();
        AtomicReference<Map<String,Object>> state = new AtomicReference<>(status(100, 50));
        try (var updates = new DevConsoleUpdates(state::get, List::of, now::get)) {
            updates.refresh();
            now.set(5000);
            state.set(Map.of("components", List.of(
                    Map.of("id", "devserver", "state", "running", "memoryBytes", 150),
                    Map.of("id", "storage", "state", "running", "memoryBytes", 50))));
            updates.refresh();
            now.set(10000);
            state.set(Map.of("components", List.of(
                    Map.of("id", "devserver", "state", "stopped", "runningProcesses", 0),
                    Map.of("id", "storage", "state", "starting", "runningProcesses", 1))));
            updates.refresh();
            now.set(15000);
            state.set(status(80, 50));
            updates.refresh();
            JsonNode history = updates.snapshot().path("status").path("resourceHistory");
            assertEquals(100, history.get(0).path("componentMemory").path("devserver").asLong());
            assertEquals(150, history.get(1).path("componentMemory").path("devserver").asLong());
            assertEquals(50, history.get(1).path("componentMemory").path("storage").asLong());
            assertEquals(0, history.get(2).path("componentMemory").path("devserver").asLong());
            assertTrue(history.get(2).path("componentMemory").path("storage").isNull());
            assertEquals(80, history.get(3).path("componentMemory").path("devserver").asLong());
            assertFalse(history.get(3).path("componentMemory").has("storage"));
        }
    }

    @Test void pushesOnlyTheNewComponentSampleAndRestoresHistoryForAnotherConnection() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicReference<Map<String,Object>> state = new AtomicReference<>(status(100, 50));
        var sent = new java.util.ArrayList<String>();
        var session = (org.eclipse.jetty.websocket.api.Session) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.eclipse.jetty.websocket.api.Session.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendText")) {
                        sent.add((String) args[0]);
                        ((org.eclipse.jetty.websocket.api.Callback) args[1]).succeed();
                    }
                    return null;
                });
        try (var updates = new DevConsoleUpdates(state::get, List::of, now::get)) {
            updates.connection().onWebSocketOpen(session);
            now.set(5000);
            state.set(status(200, 50));
            updates.refresh();
            JsonNode delta = JSON.readTree(sent.getLast());
            assertEquals("update", delta.path("type").asText());
            assertFalse(delta.path("status").has("resourceHistory"));
            assertEquals(200, delta.path("sample").path("componentMemory").path("devserver").asLong());
            assertEquals(1L << 30, delta.path("sample").path("monitoringStorageMax").asLong());
            updates.connection().onWebSocketOpen(session);
            JsonNode history = JSON.readTree(sent.getLast()).path("status").path("resourceHistory");
            assertEquals(2, history.size());
            assertEquals(100, history.get(0).path("componentMemory").path("devserver").asLong());
            assertEquals(1L << 30, history.get(0).path("monitoringStorageMax").asLong());
            assertEquals(delta.path("sample"), history.get(1));
        }
    }

    @Test void pushesChangesReconnectsWithHistoryAndRejectsForeignOrigins(@TempDir Path directory) throws Exception {
        AtomicReference<Map<String,Object>> state = new AtomicReference<>(status(100,50));
        var console = new DevConsole(state::get, null, new DevEnvironmentRegistry(directory));
        try (var gateway = DevGateway.start(null, List.of(new DevGateway.FrontendRoute("app","/","http://localhost:1",()->false)),
                ()->false,List.of(),0,()->{},false,console); var client=HttpClient.newHttpClient()) {
            URI endpoint = URI.create(gateway.url().replace("http:","ws:") + DevConsole.UPDATES);
            for (String origin : List.of("http://evil.example", "null", "http://localhost:1")) {
                var failure=assertThrows(java.util.concurrent.ExecutionException.class, ()->client.newWebSocketBuilder().header("Origin",origin)
                        .buildAsync(endpoint,new Messages()).get(5,TimeUnit.SECONDS));
                assertEquals(403,assertInstanceOf(WebSocketHandshakeException.class,failure.getCause()).getResponse().statusCode());
            }
            var missingOrigin = assertThrows(java.util.concurrent.ExecutionException.class, ()->client.newWebSocketBuilder()
                    .buildAsync(endpoint,new Messages()).get(5,TimeUnit.SECONDS));
            assertInstanceOf(WebSocketHandshakeException.class,missingOrigin.getCause());
            Messages messages = new Messages();
            WebSocket socket=client.newWebSocketBuilder().header("Origin",gateway.url()).buildAsync(endpoint,messages).get(5,TimeUnit.SECONDS);
            JsonNode initial=messages.next();
            assertEquals("snapshot",initial.path("type").asText());
            assertEquals(1,initial.path("status").path("resourceHistory").size());
            assertEquals(100, initial.path("status").path("resourceHistory").get(0).path("componentMemory").path("devserver").asLong());
            state.set(status(200,70)); console.updates.refresh();
            JsonNode delta;
            do { delta=messages.next(); } while (delta.path("status").path("components").path(0).path("memoryBytes").asLong() != 200);
            assertEquals("update",delta.path("type").asText());
            assertTrue(delta.path("sequence").asLong()>initial.path("sequence").asLong());
            assertFalse(delta.path("status").has("projectDirectory"));
            assertFalse(delta.has("environments"));
            assertFalse(delta.path("status").has("resourceHistory"));
            assertEquals(200,delta.path("status").path("components").get(0).path("memoryBytes").asLong());
            socket.abort();
            Messages second=new Messages();
            WebSocket reconnected=client.newWebSocketBuilder().header("Origin",gateway.url()).buildAsync(endpoint,second).get(5,TimeUnit.SECONDS);
            JsonNode snapshot=second.next();
            assertEquals("snapshot",snapshot.path("type").asText());
            assertEquals(200,snapshot.path("status").path("components").get(0).path("memoryBytes").asLong());
            assertEquals(initial.path("status").path("resourceHistory").get(0),snapshot.path("status").path("resourceHistory").get(0));
            gateway.close();
            second.closed.get(5,TimeUnit.SECONDS);
            reconnected.abort();
        }
    }

    @Test void sendsOnlyNewOutputAndRestoresTheRetainedTailOnReconnect(@TempDir Path directory) throws Exception {
        TestOutput output = new TestOutput();
        output.add("app", "first");
        var console = new DevConsole(() -> Map.of("testOutput", output.snapshot()), null, new DevEnvironmentRegistry(directory))
                .withMaintenance(action -> {assertEquals("clear-test-output", action); output.clear();});
        try (var gateway = DevGateway.start(null, List.of(new DevGateway.FrontendRoute("app", "/", "http://localhost:1", () -> false)), () -> false, List.of(), 0, () -> {}, false, console);
             var client = HttpClient.newHttpClient()) {
            URI endpoint = URI.create(gateway.url().replace("http:", "ws:") + DevConsole.UPDATES);
            Messages messages = new Messages();
            WebSocket socket = client.newWebSocketBuilder().header("Origin", gateway.url()).buildAsync(endpoint, messages).get(5, TimeUnit.SECONDS);
            assertEquals("first", messages.next().path("status").path("testOutput").get(0).path("text").asText());
            output.add("app", "second"); console.updates.refresh();
            JsonNode delta;
            do { delta = messages.next(); } while (!delta.has("output"));
            assertFalse(delta.path("status").has("testOutput"));
            assertEquals(1, delta.path("output").path("lines").size());
            assertEquals("second", delta.path("output").path("lines").get(0).path("text").asText());
            socket.abort();
            for (int i = 0; i < 210; i++) output.add("app", "line " + i);
            console.updates.refresh();
            Messages reconnect = new Messages();
            WebSocket second = client.newWebSocketBuilder().header("Origin", gateway.url()).buildAsync(endpoint, reconnect).get(5, TimeUnit.SECONDS);
            JsonNode tail = reconnect.next().path("status").path("testOutput");
            assertEquals(200, tail.size()); assertEquals(13, tail.get(0).path("sequence").asLong());
            assertEquals("line 209", tail.get(199).path("text").asText());
            var response=client.send(java.net.http.HttpRequest.newBuilder(URI.create(gateway.url()+DevConsole.ROOT+"actions/clear-test-output"))
                    .header("Origin",gateway.url()).header("X-Fluxzero-Console","1")
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(202,response.statusCode());console.updates.refresh();
            JsonNode cleared;
            do {cleared=reconnect.next();} while(!cleared.has("output"));
            assertEquals(0,cleared.path("output").path("firstSequence").asLong());
            assertTrue(cleared.path("output").path("lines").isEmpty());
            assertTrue(console.updates.snapshot().path("status").path("testOutput").isEmpty());
            second.abort();
        }
    }

    @Test void slowSubscribersAreDisconnectedInsteadOfGrowingAnUnboundedQueue() {
        AtomicLong now=new AtomicLong();
        AtomicReference<Map<String,Object>> state=new AtomicReference<>(status(100,50));
        java.util.concurrent.atomic.AtomicBoolean disconnected=new java.util.concurrent.atomic.AtomicBoolean();
        var session=(org.eclipse.jetty.websocket.api.Session)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.eclipse.jetty.websocket.api.Session.class},(proxy,method,args)->{
                    if (method.getName().equals("disconnect")) disconnected.set(true);
                    return null; // sendText intentionally never completes: a stalled client.
                });
        try (var updates=new DevConsoleUpdates(state::get,List::of,now::get)) {
            updates.connection().onWebSocketOpen(session);
            for (int i=0;i<20;i++) {state.set(status(i,50));updates.refresh();}
            assertTrue(disconnected.get());
            assertNotNull(updates.snapshot());
        }
    }

    private static class Messages implements WebSocket.Listener {
        final LinkedBlockingQueue<JsonNode> received=new LinkedBlockingQueue<>();
        final java.util.concurrent.CompletableFuture<Void> closed=new java.util.concurrent.CompletableFuture<>();
        final StringBuilder text=new StringBuilder();
        @Override public void onOpen(WebSocket socket) {socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last) {
            text.append(data);
            if(last) {try {received.add(JSON.readTree(text.toString()));text.setLength(0);} catch(Exception e){throw new RuntimeException(e);}}
            socket.request(1);return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(null);return null;}
        @Override public void onError(WebSocket socket,Throwable failure){closed.complete(null);}
        JsonNode next() throws Exception {JsonNode message=received.poll(5,TimeUnit.SECONDS);assertNotNull(message,"Expected console update");return message;}
    }
}
