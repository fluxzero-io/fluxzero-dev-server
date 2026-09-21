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
import java.util.concurrent.CountDownLatch;

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
        if ("log".equals(args[0])) {
            var output = "stderr".equals(args[1]) ? System.err : System.out;
            Path control = Path.of(args[2]);
            output.println("fixture started");
            if ("delayed".equals(args[3])) {
                awaitFile(control.resolve("ready"));
            }
            output.println("\u001b]8;;https://example.invalid\u001b\\\u001b[32mRea\u001b[0mdy! whsec_\u001b[31mFake123\u001b[0m token_456\u001b]8;;\u0007");
            output.println("ERROR fixture diagnostic whsec_Fake123 token_456");
            output.flush();
            Files.writeString(control.resolve("ready-emitted"), "output flushed");
            awaitFile(control.resolve("exit"));
            return;
        }
        if ("stop-log".equals(args[0])) {
            System.out.println("Ready! whsec_Stop123");
            System.err.println("Ready! whsec_Stop456");
            Files.writeString(Path.of(args[1]).resolve("ready"), "release late startup output");
            return;
        }
        if ("stop-fail".equals(args[0])) {
            System.err.println("fixture cleanup failed");
            System.exit(9);
        }
        if ("graceful".equals(args[0])) {
            Path started = Path.of(args[1]);
            Path cleaned = Path.of(args[2]);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.writeString(cleaned, "cleaned");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            Files.writeString(started, "started");
            System.out.println("READY");
            System.out.flush();
            new CountDownLatch(1).await();
            return;
        }
        if ("graceful-port".equals(args[0])) {
            int port = Integer.parseInt(args[1]);
            Path resource = Path.of(args[2]);
            Path cleaned = Path.of(args[3]);
            Files.createFile(resource);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(resource);
                    Files.writeString(cleaned, "cleaned");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            try (ServerSocket server = new ServerSocket()) {
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                System.out.println("READY");
                System.out.flush();
                new CountDownLatch(1).await();
            }
            return;
        }
        if ("exit-gate".equals(args[0])) {
            Path control = Path.of(args[1]);
            Files.writeString(control.resolve("started"), "started");
            awaitFile(control.resolve("exit"));
            System.exit(17);
        }
        if ("update-output".equals(args[0])) {
            System.out.print(args[2]);
            System.err.print(args[3]);
            System.out.flush();
            System.err.flush();
            System.exit(Integer.parseInt(args[1]));
        }
        if ("update-large".equals(args[0])) {
            System.out.print("x".repeat(Integer.parseInt(args[1])));
            return;
        }
        if ("update-sleep".equals(args[0])) {
            Thread.sleep(Long.parseLong(args[1]));
            return;
        }
        if ("update-context".equals(args[0])) {
            boolean matches = args[1].equals(System.getProperty(DevServerUpdates.ATTEMPT_PROPERTY))
                              && "starting-new".equals(System.getProperty(DevServerUpdates.PHASE_PROPERTY));
            if (!matches) {
                System.err.println("missing update context");
                System.exit(21);
            }
            return;
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

    private static void awaitFile(Path path) throws Exception {
        while (!Files.exists(path)) {
            Thread.sleep(10);
        }
    }
}
