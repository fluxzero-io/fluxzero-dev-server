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

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** A generic child-process reverse proxy: readiness traverses the gateway to the frontend. */
public class IngressFixtureServer {
    public static void main(String[] args) throws Exception {
        if ("setup".equals(args[0])) {
            // The endpoint must already be held while a service prepares its configuration.
            try (var socket = new ServerSocket()) {
                try {
                    socket.bind(new InetSocketAddress("127.0.0.1", URI.create(args[2]).getPort()));
                    throw new IllegalStateException("gateway port is not reserved");
                } catch (java.net.BindException expected) { }
            }
            Files.writeString(Path.of(args[1]), args[2]);
            return;
        }
        String upstream = Files.readString(Path.of(args[1]));
        var client = HttpClient.newHttpClient();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                var request = HttpRequest.newBuilder(URI.create(upstream + exchange.getRequestURI()));
                String accept = exchange.getRequestHeaders().getFirst("Accept");
                if (accept != null) request.header("Accept", accept);
                var result = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                result.headers().firstValue("location").ifPresent(v -> exchange.getResponseHeaders().set("Location", v));
                result.headers().firstValue("content-type").ifPresent(v -> exchange.getResponseHeaders().set("Content-Type", v));
                result.headers().firstValue("cache-control").ifPresent(v -> exchange.getResponseHeaders().set("Cache-Control", v));
                byte[] bytes = result.body();
                exchange.sendResponseHeaders(result.statusCode(), bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }
}
