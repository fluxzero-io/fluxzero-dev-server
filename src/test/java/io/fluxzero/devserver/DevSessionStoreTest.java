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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevSessionStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesSessionJsonAtomically(@TempDir Path projectDirectory) throws Exception {
        DevServerConfig config = DevServerConfig.defaults(projectDirectory);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        DevSession session = DevSession.empty(config)
                .withStatus("running")
                .withRuntime(DevSession.ServiceStatus.running("runtime", "ws://localhost:1234", 1234, null, null));

        store.writeSession(session);

        Path sessionFile = store.directory().resolve(DevSessionStore.SESSION_FILE);
        JsonNode json = objectMapper.readTree(sessionFile.toFile());
        assertEquals("running", json.path("status").asText());
        assertEquals("ws://localhost:1234", json.path("runtime").path("url").asText());
        assertTrue(json.path("observability").path("combinedLog").asText().contains(session.sessionId()));
        assertTrue(json.path("observability").path("diagnostics").asText().endsWith("diagnostics.json"));
        assertFalse(hasTemporaryFiles(store.directory()));
        assertEquals("*\n", Files.readString(store.directory().resolve(DevSessionStore.GITIGNORE_FILE)));
        assertTrue(store.readSession().isPresent());
        assertEquals(session.sessionId(), store.readSession().orElseThrow().sessionId());
    }

    @Test
    void writesTestStatusJson(@TempDir Path projectDirectory) throws Exception {
        DevSessionStore store = new DevSessionStore(projectDirectory);

        store.writeTestStatus(TestStatus.completed(List.of("OrderTest"), "changed test class", 0, "ok"));

        Path statusFile = store.directory().resolve(DevSessionStore.TEST_STATUS_FILE);
        JsonNode json = objectMapper.readTree(statusFile.toFile());
        assertEquals("passed", json.path("state").asText());
        assertEquals("changed test class", json.path("reason").asText());
        assertTrue(json.path("selectors").toString().contains("OrderTest"));
        assertEquals("passed", store.readTestStatus().orElseThrow().state());
        assertEquals("OrderTest", store.readTestStatus().orElseThrow().selectors().getFirst());
    }

    @Test
    void writesAndReadsTestInputSnapshot(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>");
        DevSessionStore store = new DevSessionStore(projectDirectory);
        TestInputSnapshot snapshot = TestInputSnapshot.capture(projectDirectory);

        store.writeTestInputs(snapshot);

        Path inputsFile = store.directory().resolve(DevSessionStore.TEST_INPUTS_FILE);
        JsonNode json = objectMapper.readTree(inputsFile.toFile());
        assertEquals(1, json.path("version").asInt());
        assertTrue(json.path("files").has("pom.xml"));
        assertEquals(snapshot, store.readTestInputs().orElseThrow());
        assertFalse(hasTemporaryFiles(store.directory()));
    }

    @Test
    void writesAndReadsCommandStatusJson(@TempDir Path projectDirectory) throws Exception {
        DevSessionStore store = new DevSessionStore(projectDirectory);
        DevCommandStatus status = new DevCommandStatus(
                "failed", "session-1", 1, 0, 1, 0, 0,
                List.of(new DevCommandStatus.Entry(
                        "src/test/resources/fluxzero/dev/commands/user.json",
                        "abc", "com.example.CreateUser", "failed", "handler missing", 123L)),
                456L);

        store.writeCommandStatus(status);

        Path statusFile = store.directory().resolve(DevSessionStore.COMMAND_STATUS_FILE);
        JsonNode json = objectMapper.readTree(statusFile.toFile());
        assertEquals("failed", json.path("state").asText());
        assertEquals("com.example.CreateUser", json.path("commands").get(0).path("type").asText());
        assertEquals("abc", store.readCommandStatus().orElseThrow().commands().getFirst().hash());
    }

    @Test
    void lockPreventsSecondActiveSession(@TempDir Path projectDirectory) throws Exception {
        DevSessionStore store = new DevSessionStore(projectDirectory);

        try (DevSessionStore.DevSessionLock ignored = store.acquireLock()) {
            assertThrows(IllegalStateException.class, store::acquireLock);
        }

        try (DevSessionStore.DevSessionLock ignored = store.acquireLock()) {
            assertTrue(Files.exists(store.directory().resolve(DevSessionStore.SESSION_LOCK_FILE)));
        }
    }

    @Test
    void reconcilesUnexpectedStopAndInvalidatesCommands(@TempDir Path projectDirectory) {
        DevSessionStore store = new DevSessionStore(projectDirectory);
        DevSession original = withPid(DevSession.empty(DevServerConfig.defaults(projectDirectory))
                                              .withStatus("running")
                                              .withRuntime(DevSession.ServiceStatus.running(
                                                      "runtime", "ws://localhost:1234", 1234, null, null)),
                                      Long.MAX_VALUE);
        store.writeSession(original);
        store.writeCommandStatus(new DevCommandStatus(
                "succeeded", original.sessionId(), 1, 1, 0, 0, 0,
                List.of(new DevCommandStatus.Entry(
                        "src/test/resources/fluxzero/dev/commands/user.json",
                        "abc", "com.example.CreateUser", "succeeded", null, 123L)),
                456L));

        DevSession reconciled = store.reconcileUnexpectedStop().orElseThrow();

        assertEquals("stopped-unexpectedly", reconciled.status());
        assertEquals("stopped", reconciled.runtime().state());
        DevCommandStatus commands = store.readCommandStatus().orElseThrow();
        assertEquals("stale", commands.state());
        assertEquals(1, commands.pending());
        assertEquals("stale", commands.commands().getFirst().state());
        assertTrue(commands.commands().getFirst().detail().contains("next session"));
    }

    @Test
    void invalidatesCommandsAfterControlledStop(@TempDir Path projectDirectory) {
        DevSessionStore store = new DevSessionStore(projectDirectory);
        DevSession stopped = withPid(DevSession.empty(DevServerConfig.defaults(projectDirectory))
                                             .withStatus("stopped"), Long.MAX_VALUE);
        store.writeSession(stopped);
        store.writeCommandStatus(new DevCommandStatus(
                "succeeded", stopped.sessionId(), 1, 1, 0, 0, 0,
                List.of(new DevCommandStatus.Entry("command.json", "abc", "Command", "succeeded", null, 123L)),
                456L));

        store.invalidateCommandStatus(stopped.sessionId(),
                                      "runtime session stopped; command will run again in the next session");

        assertEquals("stopped", store.reconcileUnexpectedStop().orElseThrow().status());
        assertEquals("stale", store.readCommandStatus().orElseThrow().state());
    }

    private static DevSession withPid(DevSession session, long pid) {
        return new DevSession(session.sessionId(), pid, session.projectDirectory(), session.observability(),
                              session.status(), session.runtime(), session.proxy(), session.gateway(), session.idp(),
                              session.app(), session.reload(), session.compile(), session.tests(), session.commands(),
                              session.frontend(), session.mcp(), session.startedAt(), session.heartbeatAt(),
                              session.updatedAt());
    }

    private static boolean hasTemporaryFiles(Path directory) throws Exception {
        try (var stream = Files.list(directory)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }
}
