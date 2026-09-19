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

package io.fluxzero.devserver.listener;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Dependency-free, Java 8 compatible telemetry client; reporting never fails a customer's tests. */
public final class TestEventClient implements AutoCloseable {
    private final ArrayBlockingQueue<String[]> queue = new ArrayBlockingQueue<String[]>(4096);
    private volatile boolean closed;
    private volatile Socket socket;
    private final Thread writer;

    public TestEventClient(final String source, final String module) {
        writer = new Thread(() -> transmit(source, module), "fluxzero-test-events");
        writer.setDaemon(true);
        writer.start();
    }

    public void event(String type, String id) {
        if (!closed && !queue.offer(new String[]{type, id.length() > 16000 ? id.substring(0,16000) : id})) {
            closed = true; // A missing stream end makes the server fall back to XML.
            disconnect();
        }
    }

    private void transmit(String source, String module) {
        try {
            String port = System.getenv("FLUXZERO_DEV_TEST_PORT");
            String token = System.getenv("FLUXZERO_DEV_TEST_TOKEN");
            if (port == null || token == null) return;
            Socket connection = new Socket();
            socket = connection;
            connection.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 500);
            connection.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.writeUTF("fz-test-v1"); out.writeUTF(token); out.writeUTF(UUID.randomUUID().toString());
            out.writeUTF(source); out.writeUTF(module.length() > 4096 ? module.substring(0,4096) : module);
            while (!closed || !queue.isEmpty()) {
                String[] event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {out.writeUTF(event[0]);out.writeUTF(event[1]);out.flush();}
            }
        } catch (Exception ignored) { /* XML remains available when telemetry cannot be delivered. */ }
        finally {closed = true;disconnect();}
    }

    private void disconnect() {try {if(socket!=null)socket.close();}catch(Exception ignored){}}
    @Override public void close() {
        closed = true;
        try {writer.join(1000);} catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        disconnect();
    }
}
