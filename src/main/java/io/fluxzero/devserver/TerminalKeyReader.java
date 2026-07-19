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
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalKeyReader implements AutoCloseable {
    static final String UP = "\u0000-up";
    static final String DOWN = "\u0000-down";
    static final String ENTER = "\u0000-enter";
    static final String ESCAPE = "\u0000-escape";
    static final String INTERRUPT = "\u0000-interrupt";
    static final String BACKSPACE = "\u0000-backspace";
    static final String CHARACTER = "\u0000-character";

    private final Terminal terminal;
    private final Attributes originalAttributes;
    private final BlockingQueue<String> commands;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean menuMode = new AtomicBoolean();
    private final LineBuffer lineBuffer = new LineBuffer();

    private TerminalKeyReader(Terminal terminal, BlockingQueue<String> commands) {
        this.terminal = terminal;
        this.commands = commands;
        this.originalAttributes = terminal.enterRawMode();
    }

    static TerminalKeyReader open(BlockingQueue<String> commands) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).provider("jni").dumb(false).build();
        try {
            return new TerminalKeyReader(terminal, commands);
        } catch (RuntimeException e) {
            terminal.close();
            throw e;
        }
    }

    void start(Runnable onClosed) {
        Thread.ofPlatform().daemon(true).name("fluxzero-dev-attach-keys").start(() -> {
            try {
                BindingReader reader = new BindingReader(terminal.reader());
                KeyMap<String> keys = keyMap(terminal);
                String command;
                while (!closed.get() && (command = reader.readBinding(keys)) != null) {
                    if (menuMode.get()) {
                        commands.offer(command);
                        continue;
                    }
                    if (INTERRUPT.equals(command)) {
                        commands.offer(command);
                        continue;
                    }
                    LineInput input = lineBuffer.accept(command, reader.getLastBinding());
                    if (!input.echo().isEmpty()) {
                        terminal.writer().print(input.echo());
                        terminal.writer().flush();
                    }
                    if (input.command() != null) {
                        String normalized = input.command().toLowerCase(Locale.ROOT);
                        if (normalized.equals("q") || normalized.equals("quit")) {
                            menuMode.set(true);
                        }
                        commands.offer(input.command());
                    }
                }
            } catch (RuntimeException ignored) {
                // Closing the attachment also closes its reader.
            } finally {
                if (!closed.get()) {
                    onClosed.run();
                }
            }
        });
    }

    static KeyMap<String> keyMap(Terminal terminal) {
        KeyMap<String> keys = new KeyMap<>();
        keys.bind("q", "q", "Q");
        keys.bind("d", "d", "D");
        keys.bind("s", "s", "S");
        keys.bind("c", "c", "C");
        keys.bind("k", "k", "K");
        keys.bind("r", "r", "R");
        keys.bind(ENTER, "\r", "\n");
        keys.bind(ESCAPE, "\033");
        keys.bind(INTERRUPT, KeyMap.ctrl('C'));
        keys.bind(BACKSPACE, "\b", "\177");
        bind(keys, UP, KeyMap.key(terminal, InfoCmp.Capability.key_up), "\033[A", "\033OA");
        bind(keys, DOWN, KeyMap.key(terminal, InfoCmp.Capability.key_down), "\033[B", "\033OB");
        keys.setUnicode(CHARACTER);
        return keys;
    }

    void resumeLineInput() {
        lineBuffer.clear();
        menuMode.set(false);
    }

    void enterMenuMode() {
        lineBuffer.clear();
        menuMode.set(true);
    }

    private static void bind(KeyMap<String> keys, String operation, String... sequences) {
        for (String sequence : sequences) {
            if (sequence != null && !sequence.isEmpty()) {
                keys.bind(operation, sequence);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            terminal.setAttributes(originalAttributes);
        } finally {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // The process is exiting; terminal restoration above is the important part.
            }
        }
    }

    static final class LineBuffer {
        private final StringBuilder value = new StringBuilder();

        LineInput accept(String operation, String binding) {
            return switch (operation) {
                case ENTER -> {
                    String command = value.toString().strip();
                    value.setLength(0);
                    yield new LineInput(System.lineSeparator(), command);
                }
                case BACKSPACE -> {
                    if (value.isEmpty()) {
                        yield LineInput.none();
                    }
                    value.deleteCharAt(value.length() - 1);
                    yield new LineInput("\b \b", null);
                }
                case "q", "d", "s", "c", "k", "r" -> append(binding);
                case CHARACTER -> append(binding);
                default -> LineInput.none();
            };
        }

        private LineInput append(String text) {
            if (text == null || text.codePoints().anyMatch(Character::isISOControl)) {
                return LineInput.none();
            }
            value.append(text);
            return new LineInput(text, null);
        }

        void clear() {
            value.setLength(0);
        }
    }

    record LineInput(String echo, String command) {
        static LineInput none() {
            return new LineInput("", null);
        }
    }
}
