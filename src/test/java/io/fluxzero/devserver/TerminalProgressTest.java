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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalProgressTest {

    @Test
    void animatesAndSafelyInterleavesTerminalOutput() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TerminalProgress progress = new TerminalProgress(
                true, new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            progress.start("Compiling application");
            Thread.sleep(125);
            progress.println("compile detail");
            progress.update("Waiting for application readiness");
            progress.printReady("Fluxzero dev server ready in 1.2s", "Open in browser: http://localhost:4200");
            progress.printFailure("Fluxzero dev could not start", List.of("Compile: failed"));
            progress.stop();
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Compiling application"), output);
        assertTrue(output.contains("Waiting for application readiness"), output);
        assertTrue(output.contains("compile detail"), output);
        assertTrue(output.contains("Time            "), output);
        assertTrue(output.contains("Open in browser http://localhost:4200"), output);
        assertTrue(output.contains("Fluxzero dev could not start"), output);
        assertTrue(output.contains("total)"), output);
        assertTrue(output.contains("\033[2K"), output);
        assertTrue(output.contains("\033[1;32m"), output);
        assertTrue(output.contains("\033[1;31m"), output);
    }

    @Test
    void rendersBuildHeadlineAndChangingDetailOnSeparateLines() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TerminalProgress progress = new TerminalProgress(
                true, new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            progress.start("Building");
            progress.update("Building: Backend: app: compiling 42 Java sources");
            progress.update("Building: Backend: app: resolving dependencies");
            progress.stop();
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(" Building "), output);
        assertTrue(output.contains("\n\r\033[2K  \033[2mBackend: app: compiling 42 Java sources"), output);
        assertTrue(output.matches("(?s).*Java sources \\(\\d+\\.\\ds\\).*"), output);
        assertTrue(output.contains("Backend: app: resolving dependencies"), output);
        assertTrue(output.contains("\033[1A"), output);
    }

    @Test
    void rendersConcurrentFrontendProgressWithoutReplacingBackendProgress() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TerminalProgress progress = new TerminalProgress(
                true, new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            progress.start("Building");
            progress.update("Building: Backend: app: compiling Java sources");
            progress.updateTask("frontend", "Frontend", "starting dev server");
            progress.updateTask("frontend", "Frontend", "confirming readiness");
            progress.removeTask("frontend");
            progress.stop();
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Backend: app: compiling Java sources"), output);
        assertTrue(output.contains("Frontend: starting dev server"), output);
        assertTrue(output.contains("Frontend: confirming readiness"), output);
        assertTrue(output.matches("(?s).*Frontend: confirming readiness \\(\\d+\\.\\ds\\).*"), output);
    }

    @Test
    void emitsNoControlSequencesWhenAnimationIsDisabled() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TerminalProgress progress = new TerminalProgress(
                false, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                Clock.fixed(Instant.parse("2026-07-12T12:34:56Z"), ZoneOffset.UTC))) {
            progress.start("Compiling application");
            progress.println("ordinary output");
            progress.printActivity("Backend change detected", List.of("Changed: src/main/java/Handler.java"));
            progress.printSuccess("Backend ready", List.of("Compile: maven-compile in 1.2s"));
            progress.printReady("Fluxzero dev server ready", "Backend: http://localhost:4200");
            progress.stop();
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("ordinary output"), output);
        assertTrue(output.contains("Backend change detected"), output);
        assertTrue(output.contains("Changed       src/main/java/Handler.java"), output);
        assertTrue(output.contains("Backend ready"), output);
        assertTrue(output.contains("Fluxzero dev server ready"), output);
        assertTrue(output.contains("Time          12:34:56.000"), output);
        assertTrue(output.contains("Compile       maven-compile in 1.2s"), output);
        assertTrue(output.contains("Backend       http://localhost:4200"), output);
        assertFalse(output.contains("Compiling application"), output);
        assertFalse(output.contains("\033["), output);
    }

    @Test
    void restoresSemanticColorsWhenReplayingDetachedOutput() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TerminalProgress progress = new TerminalProgress(
                true, new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            progress.printReplayedLine("Fluxzero dev server ready in 12.3s");
            progress.printReplayedLine("  Open in browser http://localhost:4200");
            progress.printReplayedLine("Tests started");
            progress.printReplayedLine("Tests failed");
            progress.printControlHints();
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\033[1;32mFluxzero dev server ready in 12.3s\033[0m"), output);
        assertTrue(output.contains("\033[36m  Open in browser http://localhost:4200\033[0m"), output);
        assertTrue(output.contains("\033[36mTests started\033[0m"), output);
        assertTrue(output.contains("\033[1;31mTests failed\033[0m"), output);
        assertTrue(output.contains("\033[36m[q]\033[0m quit   \033[36m[d]\033[0m detach   "
                                   + "\033[36m[Ctrl+C]\033[0m stop" + System.lineSeparator().repeat(2)), output);
    }

    @Test
    void linksExactLogLocationsWhenReplayingInteractiveOutput() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Path projectDirectory = Path.of("/tmp/example project");
        try (TerminalProgress progress = new TerminalProgress(
                true, new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            progress.printReplayedLine("  Details       .fluxzero/dev/logs/session/dev.log:42",
                                       projectDirectory);
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        String target = projectDirectory.resolve(".fluxzero/dev/logs/session/dev.log")
                .toAbsolutePath().normalize().toUri().toASCIIString() + "#42";
        assertTrue(output.contains("\033]8;;" + target),
                   output);
        assertTrue(output.contains(".fluxzero/dev/logs/session/dev.log:42"), output);
        assertTrue(output.contains("\033[36m"), output);
    }

    @Test
    void usesIntellijFileLocationSyntaxInJetBrainsTerminals() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Path projectDirectory = Path.of("/tmp/example project");
        try (TerminalProgress progress = new TerminalProgress(
                true, new PrintStream(bytes, true, StandardCharsets.UTF_8), Clock.systemUTC(), true)) {
            progress.printReplayedLine("  Details       .fluxzero/dev/logs/session/dev.log:42",
                                       projectDirectory);
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        String target = projectDirectory.resolve(".fluxzero/dev/logs/session/dev.log")
                .toAbsolutePath().normalize().toUri().toASCIIString() + ":42:1";
        assertTrue(output.contains(target), output);
        assertFalse(output.contains("\033]8;;"), output);
    }

    @Test
    void ignoresOutputAfterClose() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalProgress progress = new TerminalProgress(
                false, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        progress.close();
        progress.println("late output");
        progress.printBlock(List.of("late block"));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("late output"), output);
        assertFalse(output.contains("late block"), output);
    }
}
