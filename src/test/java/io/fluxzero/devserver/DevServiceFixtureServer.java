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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Child process used to verify managed support-service lifecycle and environment injection. */
public class DevServiceFixtureServer {
    public static void main(String[] args) throws Exception {
        if ("stop".equals(args[0])) {
            Files.writeString(Path.of(args[1]), "stopped");
            return;
        }
        if ("exit".equals(args[0])) {
            System.exit(7);
        }
        int port = Integer.parseInt(args[0]);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            while (true) {
                var socket = server.accept();
                Thread.startVirtualThread(() -> {
                    try (socket;
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // Consume request headers.
                        }
                        String body = String.join(";",
                                                  "port=" + port,
                                                  "service=" + System.getenv("FLUXZERO_SERVICE_ID"),
                                                  "servicePort=" + System.getenv("FLUXZERO_SERVICE_PORT_HTTP"),
                                                  "session=" + System.getenv("FLUXZERO_DEV_SESSION_ID"),
                                                  "configured=" + System.getenv("FIXTURE_VALUE"));
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                                                        + "Content-Length: " + bytes.length
                                                        + "\r\nConnection: close\r\n\r\n")
                                                               .getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().write(bytes);
                    } catch (Exception ignored) {
                        // The parent process owns lifecycle and may close connections during shutdown.
                    }
                });
            }
        }
    }
}
