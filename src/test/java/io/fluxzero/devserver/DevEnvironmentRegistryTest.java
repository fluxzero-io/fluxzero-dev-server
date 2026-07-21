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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevEnvironmentRegistryTest {

    @Test
    void registersAndUnregistersRunningEnvironment(@TempDir Path directory) {
        Path project = directory.resolve("orders");
        DevSession session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                .withGateway(DevSession.ServiceStatus.running(
                        "gateway", "http://localhost:4200", 4200, null, "public"))
                .withApp(DevSession.ServiceStatus.running("app", null, null, null, "running")
                                 .withMetadata(Map.of("application.orders.pid", "123")));
        new DevSessionStore(project).writeSession(session);
        DevEnvironmentRegistry registry = new DevEnvironmentRegistry(directory.resolve("registry"));

        registry.register(session);

        DevEnvironmentRegistry.Environment environment = registry.list().getFirst();
        assertEquals("running", environment.status());
        assertTrue(environment.active());
        assertEquals("orders", environment.projectName());
        assertEquals(java.util.List.of("orders"), environment.applications());
        assertEquals("http://localhost:4200", environment.url());
        try (var registrations = java.nio.file.Files.list(directory.resolve("registry"))) {
            var registration = new ObjectMapper().readTree(registrations.findFirst().orElseThrow().toFile());
            assertFalse(registration.has("url"));
            assertFalse(registration.has("applications"));
            assertFalse(registration.has("mcp"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        registry.unregister(session);

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void marksDeadProcessAsStaleAndReconcilesProjectSession(@TempDir Path directory) {
        Path project = directory.resolve("billing");
        DevSession session = withProcess(
                DevSession.empty(DevServerConfig.defaults(project)).withStatus("running"), Long.MAX_VALUE,
                Instant.now().toEpochMilli(), Instant.now().toEpochMilli());
        DevSessionStore store = new DevSessionStore(project);
        store.writeSession(session);
        DevEnvironmentRegistry registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        registry.register(session);

        DevEnvironmentRegistry.Environment environment = registry.list().getFirst();

        assertEquals("stale", environment.status());
        assertFalse(environment.active());
        assertTrue(environment.detail().contains("not running"));
        assertEquals("stopped-unexpectedly", store.readSession().orElseThrow().status());
    }

    @Test
    void marksLiveProcessWithExpiredHeartbeatAsUnresponsive(@TempDir Path directory) {
        Path project = directory.resolve("reporting");
        DevSession base = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running");
        DevSession session = withProcess(base, base.pid(), base.startedAt(),
                                         Instant.now().minusSeconds(30).toEpochMilli());
        new DevSessionStore(project).writeSession(session);
        DevEnvironmentRegistry registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        registry.register(session);

        DevEnvironmentRegistry.Environment environment = registry.list().getFirst();

        assertEquals("unresponsive", environment.status());
        assertFalse(environment.active());
        assertTrue(environment.detail().contains("heartbeat"));
    }

    private static DevSession withProcess(DevSession session, long pid, long startedAt, long heartbeatAt) {
        return new DevSession(session.sessionId(), pid, session.devServerVersion(), session.projectDirectory(),
                              session.observability(), session.status(), session.runtime(), session.proxy(),
                              session.gateway(), session.idp(), session.app(), session.reload(), session.compile(),
                              session.tests(), session.commands(), session.frontend(), session.mcp(), startedAt,
                              heartbeatAt, session.updatedAt());
    }
}
