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

import org.jline.keymap.BindingReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlocking;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerminalKeyReaderTest {

    @Test
    void mapsNavigationSelectionAndControlKeys() throws Exception {
        String input = "\033[A\033[B\rqdkr";
        try (Terminal terminal = TerminalBuilder.builder().system(false)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).dumb(true).build()) {
            BindingReader reader = new BindingReader(NonBlocking.nonBlocking("terminal-key-test", new StringReader(input)));
            var keys = TerminalKeyReader.keyMap(terminal);

            assertEquals(TerminalKeyReader.UP, reader.readBinding(keys));
            assertEquals(TerminalKeyReader.DOWN, reader.readBinding(keys));
            assertEquals(TerminalKeyReader.ENTER, reader.readBinding(keys));
            assertEquals("q", reader.readBinding(keys));
            assertEquals("d", reader.readBinding(keys));
            assertEquals("k", reader.readBinding(keys));
            assertEquals("r", reader.readBinding(keys));
            assertEquals(TerminalKeyReader.INTERRUPT, keys.getBound("\003"));
        }
    }

    @Test
    void lineCommandsAreOnlySubmittedAfterEnter() {
        TerminalKeyReader.LineBuffer buffer = new TerminalKeyReader.LineBuffer();

        assertNull(buffer.accept("q", "q").command());
        assertEquals("q", buffer.accept(TerminalKeyReader.ENTER, "\r").command());
        assertNull(buffer.accept("d", "d").command());
        assertEquals("d", buffer.accept(TerminalKeyReader.ENTER, "\r").command());
    }
}
