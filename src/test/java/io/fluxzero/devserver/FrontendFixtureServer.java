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

/** Test child process used to verify dynamic frontend port injection. */
public class FrontendFixtureServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            while (true) {
                var socket = server.accept();
                Thread.startVirtualThread(() -> {
                    try (socket;
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        while (!reader.readLine().isEmpty()) {
                            // Consume request headers.
                        }
                        String body = "port=" + port + ";backend=" + System.getenv("FLUXZERO_PROXY_URL");
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                                                        + bytes.length + "\r\nConnection: close\r\n\r\n")
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
