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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DashboardWorkspaceTest {
    @TempDir Path directory;
    private DevSession session() { return DevSession.empty(DevServerConfig.defaults(directory)).withStatus("running"); }
    private DevSession.ServiceStatus status(String state) { return DevSession.ServiceStatus.stopped("service").withState(state, "private technical detail"); }

    @Test void usesTheSelectedSdkWithoutGuessingUnknownVersions() {
        var session = session();
        assertEquals(Map.of("devServer", session.devServerVersion()), DashboardWorkspace.versions(session));
        assertEquals("2.0.0-RC1", DashboardWorkspace.versions(session.withRuntime(status("running")
                .withMetadata(Map.of("runtimeSdkVersion", "2.0.0-RC1")))).get("fluxzero"));
    }

    @Test void staysQuietDuringNormalWorkAndKeepsTestFailuresSeparate() {
        var session = session().withApp(status("starting")).withCompile(status("running"))
                .withFrontend(status("degraded")).withTests(status("failed"));
        assertEquals("", DashboardWorkspace.issue(session));
        assertEquals("", DashboardWorkspace.issue(session.withApp(status("degraded")
                .withMetadata(Map.of("project.one.state", "running", "project.two.state", "starting")))));
    }

    @Test void distinguishesFailedUpdatesFromAnAppThatCannotStartAndClearsOnRecovery() {
        var failed = session().withCompile(status("failed"));
        assertEquals("Your app could not start.", DashboardWorkspace.issue(failed));
        assertEquals("Your latest changes could not be applied. A previous version is still running.",
                DashboardWorkspace.issue(failed.withApp(status("running").withMetadata(Map.of("application.old.failure", "candidate failed", "project.one.state", "running")))));
        assertEquals("", DashboardWorkspace.issue(failed.withCompile(status("succeeded")).withApp(status("running"))));
        assertFalse(DashboardWorkspace.issue(failed).contains("private"));
    }

    @Test void reportsPartialAppFailuresAndSupportingServiceFailures() {
        assertEquals("Part of your app is unavailable.", DashboardWorkspace.issue(session().withApp(status("degraded")
                .withMetadata(Map.of("project.one.state", "running", "project.two.state", "failed")))));
        assertEquals("Part of your app is unavailable.", DashboardWorkspace.issue(session().withFrontend(status("exited"))));
        assertEquals("Part of your workspace is unavailable.", DashboardWorkspace.issue(session()
                .withServices(Map.of("mail", status("failed")))));
        assertEquals("Your workspace could not finish getting ready.", DashboardWorkspace.issue(session().withCommands(status("failed"))));
    }

    @Test void intentionalStopsDoNotRetainProblemIndicators() {
        for (var state : new String[]{"idle", "stopping", "stopped", "shutdown"}) {
            assertEquals("", DashboardWorkspace.issue(session().withCompile(status("failed")).withStatus(state)));
        }
    }
}
