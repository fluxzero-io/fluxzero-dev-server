/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServerPreflightMainTest {

    @Test
    void acceptsAvailableConfiguredPort(@TempDir Path project) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        assertEquals(0, DevServerPreflightMain.run(
                config(project, port), ignored -> DevServerPreflightMain.PortConflictChoice.cancel(), ignored -> { }));
    }

    @Test
    void requestsDynamicPortWhenConfiguredPortIsOccupied(@TempDir Path project) throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(DevServerPreflightMain.USE_DYNAMIC_PORT_EXIT_CODE,
                         DevServerPreflightMain.run(
                                 config(project, socket.getLocalPort()),
                                 ignored -> DevServerPreflightMain.PortConflictChoice.dynamic(), ignored -> { }));
        }
    }

    @Test
    void stopsListenerAndReusesConfiguredPort(@TempDir Path project) throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = socket.getLocalPort();
            PortListenerProcess listener = new PortListenerProcess(123L, "ng serve --port " + port);

            assertEquals(0, DevServerPreflightMain.run(
                    config(project, port), ignored -> DevServerPreflightMain.PortConflictChoice.stop(listener),
                    ignored -> {
                        stopped.set(true);
                        try {
                            socket.close();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }));
        }
        assertEquals(true, stopped.get());
    }

    @Test
    void preservesPortConflictWhenDynamicPortIsRejected(@TempDir Path project) throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            DevServerStartupException failure = assertThrows(DevServerStartupException.class,
                    () -> DevServerPreflightMain.run(
                            config(project, socket.getLocalPort()),
                            ignored -> DevServerPreflightMain.PortConflictChoice.fail(), ignored -> { }));

            assertEquals("Port " + socket.getLocalPort()
                         + " is already in use. Stop the process using it or configure a different port.",
                         failure.getMessage());
        }
    }

    @Test
    void cancellationStopsPreflightWithoutReportingStartupFailure(@TempDir Path project) throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(DevServerPreflightMain.CANCEL_STARTUP_EXIT_CODE,
                         DevServerPreflightMain.run(
                                 config(project, socket.getLocalPort()),
                                 ignored -> DevServerPreflightMain.PortConflictChoice.cancel(),
                                 ignored -> stopped.set(true)));
        }
        assertEquals(false, stopped.get());
    }

    private static DevServerConfig config(Path project, int port) {
        return DevServerConfig.fromArgs(new String[]{
                "--project-dir", project.toString(),
                "--frontend-command", "frontend --port {port}",
                "--port", Integer.toString(port)
        });
    }
}
