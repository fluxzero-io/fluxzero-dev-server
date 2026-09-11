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
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialStdioServerTransportProviderTest {
    @Test
    void aSubmissionQueuedBehindCloseStillTerminates() throws Exception {
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var input = new PipedInputStream(); var writer = new PipedOutputStream(input)) {
            var transport = new SerialStdioServerTransportProvider(
                    new JacksonMcpJsonMapper(new ObjectMapper()), input, new ByteArrayOutputStream(), executor);
            McpServer.sync(transport).serverInfo("close-race-test", "1").build();
            executor.submit(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var closing = transport.closeGracefully().toFuture();
            // A tool handler can finish after EOF has already queued the transport's close operation.
            var submission = transport.notifyClients("notifications/test", Map.of()).onErrorComplete().toFuture();
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            submission.get(2, TimeUnit.SECONDS);
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
