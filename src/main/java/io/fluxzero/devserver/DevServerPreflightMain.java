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

import java.io.Console;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Performs terminal-dependent checks before the dev server is detached. */
public final class DevServerPreflightMain {
    static final int USE_DYNAMIC_PORT_EXIT_CODE = 75;
    static final int CANCEL_STARTUP_EXIT_CODE = 76;
    private static final String INPUT_CLOSED = "\u0000-input-closed";

    private DevServerPreflightMain() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(DevServerConfig.fromArgs(args), DevServerPreflightMain::choosePortConflict,
                           PortListenerProcess::stop);
        } catch (DevServerStartupException | IllegalArgumentException e) {
            System.err.println("Fluxzero dev could not start: " + e.getMessage());
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(DevServerConfig config, IntFunction<PortConflictChoice> conflictChoice,
                   Consumer<PortListenerProcess> processStopper) {
        if (config.gatewayPort() == 0) {
            return 0;
        }
        if (config.frontend().mode() == FrontendConfig.Mode.NONE) {
            throw new DevServerStartupException("--port requires a frontend command or frontend URL");
        }
        try {
            DevGateway.requireAvailablePort(config.gatewayPort());
            return 0;
        } catch (DevServerStartupException e) {
            PortConflictChoice choice = conflictChoice.apply(config.gatewayPort());
            if (choice.action() == PortConflictAction.STOP_PROCESS) {
                if (choice.listener() == null) {
                    throw new DevServerStartupException(
                            "Could not identify the process using port " + config.gatewayPort(), e);
                }
                processStopper.accept(choice.listener());
                DevGateway.requireAvailablePort(config.gatewayPort());
                System.err.printf("Stopped PID %d. Port %d is now available.%n",
                                  choice.listener().pid(), config.gatewayPort());
                return 0;
            }
            if (choice.action() == PortConflictAction.USE_DYNAMIC_PORT) {
                System.err.println("Using a random free public port instead.");
                return USE_DYNAMIC_PORT_EXIT_CODE;
            }
            if (choice.action() == PortConflictAction.CANCEL) {
                System.err.println("Fluxzero dev startup cancelled.");
                return CANCEL_STARTUP_EXIT_CODE;
            }
            throw e;
        }
    }

    private static PortConflictChoice choosePortConflict(int port) {
        Console console = System.console();
        if (console == null) {
            return PortConflictChoice.fail();
        }
        Optional<PortListenerProcess> listener = PortListenerProcess.find(port);
        PrintStream output = System.err;
        output.printf("Port %d is already in use.%n", port);
        listener.ifPresent(process -> {
            output.printf("  PID      %d%n", process.pid());
            output.printf("  Process  %s%n", process.displayCommand());
        });
        output.println();
        output.println("What should happen?");
        output.println();
        List<MenuOption> options = new ArrayList<>();
        listener.ifPresent(process -> options.add(new MenuOption(
                "Stop existing process and use port " + port,
                PortConflictChoice.stop(process), "k")));
        options.add(new MenuOption("Use a random free port", PortConflictChoice.dynamic(), "r"));
        options.add(new MenuOption("Cancel startup", PortConflictChoice.cancel(), "c"));
        try {
            return arrowMenu(options, output);
        } catch (Exception ignored) {
            return lineMenu(options, console, output);
        }
    }

    private static PortConflictChoice arrowMenu(List<MenuOption> options, PrintStream output) throws Exception {
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        try (TerminalKeyReader reader = TerminalKeyReader.open(commands)) {
            reader.enterMenuMode();
            reader.start(() -> commands.offer(INPUT_CLOSED));
            int selected = 0;
            printMenu(options, selected, false, output);
            while (true) {
                String command = commands.take();
                switch (command) {
                    case TerminalKeyReader.UP -> {
                        selected = (selected + options.size() - 1) % options.size();
                        printMenu(options, selected, true, output);
                    }
                    case TerminalKeyReader.DOWN -> {
                        selected = (selected + 1) % options.size();
                        printMenu(options, selected, true, output);
                    }
                    case TerminalKeyReader.ENTER -> {
                        output.println();
                        return options.get(selected).choice();
                    }
                    case "k", "K", "r", "R", "c", "C" -> {
                        String shortcut = command.toLowerCase(Locale.ROOT);
                        Optional<MenuOption> chosen = options.stream()
                                .filter(option -> option.shortcut().equals(shortcut)).findFirst();
                        if (chosen.isPresent()) {
                            output.println();
                            return chosen.get().choice();
                        }
                    }
                    case TerminalKeyReader.INTERRUPT, TerminalKeyReader.ESCAPE, INPUT_CLOSED -> {
                        output.println();
                        return PortConflictChoice.cancel();
                    }
                    default -> { }
                }
            }
        }
    }

    private static PortConflictChoice lineMenu(List<MenuOption> options, Console console, PrintStream output) {
        for (MenuOption option : options) {
            output.printf("  [%s] %s%n", option.shortcut(), option.label());
        }
        String defaultShortcut = options.getFirst().shortcut();
        while (true) {
            String answer = console.readLine("Choice [%s]: ", defaultShortcut);
            String normalized = answer == null || answer.isBlank()
                    ? defaultShortcut : answer.strip().toLowerCase(Locale.ROOT);
            Optional<MenuOption> selected = options.stream().filter(option ->
                    option.shortcut().equals(normalized) || option.label().toLowerCase(Locale.ROOT)
                            .startsWith(normalized)).findFirst();
            if (selected.isPresent()) {
                return selected.get().choice();
            }
            output.printf("Choose %s.%n", String.join(", ", options.stream().map(MenuOption::shortcut).toList()));
        }
    }

    private static void printMenu(List<MenuOption> options, int selected, boolean redraw, PrintStream output) {
        if (redraw) {
            output.printf("\u001b[%dA", options.size());
        }
        for (int index = 0; index < options.size(); index++) {
            if (redraw) {
                output.print("\r\u001b[2K");
            }
            output.println(index == selected
                                   ? "\u001b[36m› " + options.get(index).label() + "\u001b[0m"
                                   : "  " + options.get(index).label());
        }
        output.flush();
    }

    enum PortConflictAction {
        STOP_PROCESS,
        USE_DYNAMIC_PORT,
        CANCEL,
        FAIL
    }

    record PortConflictChoice(PortConflictAction action, PortListenerProcess listener) {
        static PortConflictChoice stop(PortListenerProcess listener) {
            return new PortConflictChoice(PortConflictAction.STOP_PROCESS, listener);
        }

        static PortConflictChoice dynamic() {
            return new PortConflictChoice(PortConflictAction.USE_DYNAMIC_PORT, null);
        }

        static PortConflictChoice cancel() {
            return new PortConflictChoice(PortConflictAction.CANCEL, null);
        }

        static PortConflictChoice fail() {
            return new PortConflictChoice(PortConflictAction.FAIL, null);
        }
    }

    private record MenuOption(String label, PortConflictChoice choice, String shortcut) {
    }
}
