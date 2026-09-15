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
            var registration = new ObjectMapper().readTree(registrations.filter(java.nio.file.Files::isRegularFile).findFirst().orElseThrow().toFile());
            assertFalse(registration.has("url"));
            assertFalse(registration.has("applications"));
            assertFalse(registration.has("mcp"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        registry.unregister(session);

        assertTrue(registry.list().isEmpty());
        assertEquals(1, registry.listKnown().size(), "UI history survives CLI unregister");
    }

    @Test
    void overviewRetainsStoppedProjectsAndLastPortWithoutMutatingSessions(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("orders");
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        DevSession session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                .withGateway(DevSession.ServiceStatus.running("gateway", "http://localhost:4200", 4200, null, "public"));
        var store = new DevSessionStore(project);
        store.writeSession(session);
        registry.register(session);
        assertEquals("http://localhost:4200/_fluxzero/dev/", registry.listKnown().getFirst().consoleUrl());
        store.writeSession(session.withStatus("stopped"));
        registry.unregister(session);
        String before = java.nio.file.Files.readString(project.resolve(".fluxzero/dev/session.json"));
        var stopped = registry.listKnown().getFirst();
        assertEquals("stopped", stopped.status());
        assertEquals(4200, stopped.port());
        assertEquals(null, stopped.consoleUrl());
        assertEquals(before, java.nio.file.Files.readString(project.resolve(".fluxzero/dev/session.json")));

        var restarted = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                .withGateway(DevSession.ServiceStatus.running("gateway", "http://localhost:4300", 4300, null, "public"));
        store.writeSession(restarted);
        registry.register(restarted);
        assertEquals(1, registry.listKnown().size());
        assertEquals(4300, registry.listKnown().getFirst().port());
        registry.unregister(restarted);
        java.nio.file.Files.delete(project.resolve(".fluxzero/dev/session.json"));
        assertEquals("stopped", registry.listKnown().getFirst().status());
        assertEquals(4300, registry.listKnown().getFirst().port());
    }

    @Test
    void overviewSeparatesSameNamedFoldersAndDoesNotExposeUntrustedUrls(@TempDir Path directory) {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        for (String parent : java.util.List.of("first", "second")) {
            Path project = directory.resolve(parent).resolve("orders");
            var session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                    .withGateway(DevSession.ServiceStatus.running("gateway", "http://example.com:4200", 4200, null, "public"));
            new DevSessionStore(project).writeSession(session);
            registry.register(session);
        }
        assertEquals(2, registry.listKnown().size());
        assertTrue(registry.listKnown().stream().allMatch(e -> e.consoleUrl() == null));
    }

    @Test
    void overviewNeverOffersLinkToDeadOrUnresponsiveEnvironment(@TempDir Path directory) {
        Path project = directory.resolve("orders");
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running")
                .withGateway(DevSession.ServiceStatus.running("gateway", "http://localhost:4200", 4200, null, "public"));
        var store = new DevSessionStore(project);
        store.writeSession(withProcess(session, session.pid(), session.startedAt(), Instant.now().minusSeconds(30).toEpochMilli()));
        registry.register(session);
        assertEquals("running", registry.listKnown().getFirst().status());
        assertEquals("Not responding", registry.listKnown().getFirst().detail());
        assertEquals(null, registry.listKnown().getFirst().consoleUrl());
        store.writeSession(withProcess(session, Long.MAX_VALUE, session.startedAt(), session.heartbeatAt()));
        assertEquals("stopped", registry.listKnown().getFirst().status());
        assertEquals(null, registry.listKnown().getFirst().consoleUrl());
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

    @Test
    void forgetsOnlyStoppedProjectsWithoutChangingFilesAndRestoresOnRestart(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("orders");
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        var store = new DevSessionStore(project);
        var session = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running");
        store.writeSession(session);
        registry.register(session);
        String id = registry.listKnown().getFirst().id();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> registry.forget(id));
        store.writeSession(session.withStatus("stopped"));
        String before = java.nio.file.Files.readString(project.resolve(".fluxzero/dev/session.json"));
        registry.forget(id);
        assertTrue(registry.listKnown().isEmpty());
        assertEquals(before, java.nio.file.Files.readString(project.resolve(".fluxzero/dev/session.json")));
        assertEquals(1, registry.list().size(), "Forgetting does not erase CLI ownership records");
        var otherProject = directory.resolve("other");
        var other = DevSession.empty(DevServerConfig.defaults(otherProject)).withStatus("running");
        new DevSessionStore(otherProject).writeSession(other);
        registry.register(other);
        assertTrue(registry.findKnown(id).isEmpty(), "Importing legacy registrations must not resurrect a forgotten project");
        var restarted = DevSession.empty(DevServerConfig.defaults(project)).withStatus("running");
        store.writeSession(restarted);
        registry.register(restarted);
        assertTrue(registry.findKnown(id).isPresent());
        assertTrue(registry.findKnown(id).orElseThrow().directoryExists());
    }

    @Test
    void forgetsMissingFoldersAndPersistsAcrossRegistryInstances(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("gone");
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        registry.register(DevSession.empty(DevServerConfig.defaults(project)).withStatus("stopped"));
        var known = registry.listKnown().getFirst();
        assertFalse(known.directoryExists());
        registry.forget(known.id());
        assertTrue(new DevEnvironmentRegistry(directory.resolve("registry")).listKnown().isEmpty());
    }

    @Test
    void namesDistinguishEqualFolderNamesAndSurviveRegistrationAndRestart(@TempDir Path directory) throws Exception {
        var registry = new DevEnvironmentRegistry(directory.resolve("registry"));
        Path first = directory.resolve("one/orders"), second = directory.resolve("two/orders");
        var session = DevSession.empty(DevServerConfig.defaults(first)).withStatus("stopped");
        for (Path project : java.util.List.of(first, second)) {
            var stopped = DevSession.empty(DevServerConfig.defaults(project)).withStatus("stopped");
            new DevSessionStore(project).writeSession(stopped);
            registry.register(stopped);
        }
        String firstPath = first.toRealPath().toString(), secondPath = second.toRealPath().toString();
        var original = registry.listKnown().stream().filter(e -> e.projectDirectory().equals(firstPath)).findFirst().orElseThrow();
        assertTrue(registry.listKnown().stream().allMatch(e -> e.projectName().equals("orders")));
        String sessionBefore = java.nio.file.Files.readString(first.resolve(".fluxzero/dev/session.json"));
        assertEquals("Orders feature branch", registry.rename(original.id(), "  Orders feature branch  ").projectName());
        assertEquals(sessionBefore, java.nio.file.Files.readString(first.resolve(".fluxzero/dev/session.json")));
        registry.unregister(session);
        registry.register(session);
        var otherInstance = new DevEnvironmentRegistry(directory.resolve("registry"));
        assertEquals("Orders feature branch", otherInstance.findKnown(original.id()).orElseThrow().projectName());
        assertEquals("orders", otherInstance.listKnown().stream().filter(e -> e.projectDirectory().equals(secondPath)).findFirst().orElseThrow().projectName());
        registry.forget(original.id());
        registry.register(session);
        assertEquals("Orders feature branch", registry.findKnown(original.id()).orElseThrow().projectName());
        assertEquals("orders", registry.rename(original.id(), "").projectName());
        assertEquals("orders", otherInstance.findKnown(original.id()).orElseThrow().projectName());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> registry.rename(original.id(), "x".repeat(101)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> registry.rename(original.id(), "line\nbreak"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> registry.rename("0".repeat(64), "Unknown"));
    }

    private static DevSession withProcess(DevSession session, long pid, long startedAt, long heartbeatAt) {
        return new DevSession(session.sessionId(), pid, session.devServerVersion(), session.projectDirectory(),
                              session.observability(), session.status(), session.runtime(), session.proxy(),
                              session.gateway(), session.idp(), session.app(), session.reload(), session.compile(),
                              session.tests(), session.commands(), session.frontend(), session.mcp(), startedAt,
                              heartbeatAt, session.updatedAt());
    }
}
