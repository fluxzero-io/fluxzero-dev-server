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
import static org.junit.jupiter.api.Assertions.*;

class McpDisconnectClassificationTest {
    @Test void configuredConsoleOmitsExpectedClosureButKeepsRealFailures() {
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var console = root.getAppender("STDOUT");
        assertNotNull(console);
        var expected = new ch.qos.logback.classic.spi.LoggingEvent();
        expected.setLoggerName("io.modelcontextprotocol.spec.McpTransport");
        expected.setLevel(ch.qos.logback.classic.Level.ERROR);
        expected.setMessage("Failed to send message to session abc: Client disconnected");
        assertEquals(ch.qos.logback.core.spi.FilterReply.DENY, console.getFilterChainDecision(expected));
        var failure = new ch.qos.logback.classic.spi.LoggingEvent();
        failure.setLoggerName("io.modelcontextprotocol.spec.McpTransport");
        failure.setLevel(ch.qos.logback.classic.Level.ERROR);
        failure.setMessage("Failed to send message to session abc: invalid protocol");
        assertEquals(ch.qos.logback.core.spi.FilterReply.NEUTRAL, console.getFilterChainDecision(failure));
    }

    @Test void onlyRecognizesKnownClientClosureMessages() {
        String logger = "io.modelcontextprotocol.spec.McpServerSession";
        assertTrue(EmbeddedLogCapture.expectedClientDisconnect(logger, "Failed to send message to session abc: Client disconnected"));
        assertTrue(EmbeddedLogCapture.expectedClientDisconnect(logger, "Failed to send keep-alive ping to session abc: Stream closed"));
        assertFalse(EmbeddedLogCapture.expectedClientDisconnect(logger, "Failed to send message to session abc: invalid protocol"));
        assertFalse(EmbeddedLogCapture.expectedClientDisconnect(logger, "Failed to initialize transport: Client disconnected"));
        assertFalse(EmbeddedLogCapture.expectedClientDisconnect("customer.Handler", "Failed to send message to session abc: Client disconnected"));
        assertFalse(EmbeddedLogCapture.expectedClientDisconnect(logger, null));
    }
}
