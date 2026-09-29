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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Ask Maven for the mediated dependency graph of explicit application selections, before compilation. */
final class SelectedMavenSdkVersions {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TREE_GOAL = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree";

    static boolean applies(DevServerConfig config) {
        return Files.isRegularFile(config.projectDirectory().resolve("pom.xml"))
               && (!config.applications().isEmpty() || config.mainClass() != null);
    }

    static Map<String, String> detect(DevServerConfig config) {
        List<MavenReactor.Module> modules = List.copyOf(MavenReactor.load(config.projectDirectory()).modules());
        List<SelectedModule> selected = select(config, modules);
        Map<String, String> versions = new LinkedHashMap<>();
        // Separate test applications because their effective launch classpath includes test dependencies.
        for (boolean test : List.of(false, true)) {
            List<SelectedModule> group = selected.stream().filter(module -> module.test() == test).toList();
            if (group.isEmpty()) continue;
            String output = "target/fluxzero-dev/sdk-selection-" + UUID.randomUUID() + ".json";
            List<String> command = new ArrayList<>(MavenCommand.command(config.projectDirectory(), TREE_GOAL,
                    "-pl", String.join(",", group.stream().map(s -> s.module().relativeName()).distinct().toList()),
                    "-am", "-DoutputType=json", "-DoutputFile=" + output, "-DappendOutput=false",
                    "-Dincludes=io.fluxzero:sdk", "-Dverbose=false", "-Dscope=" + (test ? "test" : "runtime")));
            try (InspectionProcess inspection = new InspectionProcess()) {
                Process process = inspection.start(command, config.projectDirectory());
                if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Maven dependency inspection timed out after two minutes");
                }
                if (process.exitValue() != 0) throw new IllegalStateException("Maven dependency inspection exited " + process.exitValue());
                for (SelectedModule application : group) {
                    Path tree = application.module().directory().resolve(output);
                    if (!Files.isRegularFile(tree)) throw new IllegalStateException("Maven did not produce " + tree);
                    collect(MAPPER.readTree(tree.toFile()), application.id() + " (" + application.module().relativeName() + ")", versions);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new DevServerStartupException("Could not determine the selected Maven applications' SDK versions in "
                        + config.projectDirectory() + ": " + e.getMessage() + ". Set "
                        + FluxzeroSdkVersionDetector.VERSION_OVERRIDE_ENV + " for a custom build model.", e);
            } finally {
                for (MavenReactor.Module module : modules) {
                    try { Files.deleteIfExists(module.directory().resolve(output)); } catch (Exception ignored) { }
                }
            }
        }
        return versions;
    }

    static void collect(JsonNode node, String path, Map<String, String> versions) {
        String coordinate = node.path("groupId").asText() + ":" + node.path("artifactId").asText()
                + ":" + node.path("version").asText();
        String current = path + " -> " + coordinate;
        if ("io.fluxzero".equals(node.path("groupId").asText()) && "sdk".equals(node.path("artifactId").asText())) {
            String version = node.path("version").asText();
            if (version.isBlank() || version.contains("${")) throw new IllegalArgumentException("Unresolved SDK version at " + current);
            versions.put(current, version);
        }
        node.path("children").forEach(child -> collect(child, current, versions));
    }

    static List<SelectedModule> select(DevServerConfig config, List<MavenReactor.Module> modules) {
        List<DevServerConfig.ApplicationSelection> selections = config.applicationSelections();
        if (selections.isEmpty()) selections = List.of(new DevServerConfig.ApplicationSelection(
                config.mainClass(), config.mainClass(), null, null, Map.of(), Map.of()));
        List<SelectedModule> result = new ArrayList<>();
        for (var selection : selections) {
            String selector = selection.selector();
            List<SelectedModule> matches = new ArrayList<>();
            for (var module : modules) {
                String classSelector = selector;
                int separator = selector.lastIndexOf(':');
                if (separator >= 0) {
                    String moduleSelector = selector.substring(0, separator);
                    if (!moduleSelector.equalsIgnoreCase(module.artifactId()) && !moduleSelector.equalsIgnoreCase(module.relativeName())) continue;
                    classSelector = selector.substring(separator + 1);
                } else if ((selector.equals(module.artifactId()) || selector.equals(module.relativeName())) && config.mainClass() == null) {
                    matches.add(new SelectedModule(selection.id(), module, false));
                    continue;
                }
                if (config.mainClass() != null) classSelector = config.mainClass();
                boolean main = hasClass(module, classSelector, false);
                boolean test = hasClass(module, classSelector, true);
                if (main || test) matches.add(new SelectedModule(selection.id(), module, !main && test));
            }
            if (matches.size() != 1) throw new DevServerStartupException("Selected application " + selection.id()
                    + " (" + selector + ") matches " + matches.size() + " Maven modules before compilation. "
                    + "Select an unambiguous module or main class, or set " + FluxzeroSdkVersionDetector.VERSION_OVERRIDE_ENV + ".");
            result.add(matches.getFirst());
        }
        return List.copyOf(result);
    }

    private static boolean hasClass(MavenReactor.Module module, String selector, boolean test) {
        if (!test && selector.equals(module.configuredMainClass())) return true;
        String scope = test ? "test" : "main";
        for (String language : List.of("java", "kotlin")) {
            Path source = module.directory().resolve("src/" + scope + "/" + language);
            if (!Files.isDirectory(source)) continue;
            try (var paths = Files.walk(source)) {
                if (paths.filter(Files::isRegularFile).anyMatch(path -> {
                    String relative = source.relativize(path).toString().replace('\\', '.').replace('/', '.');
                    return relative.equals(selector + ".java") || relative.equals(selector + ".kt")
                            || path.getFileName().toString().equalsIgnoreCase(selector + ".java")
                            || path.getFileName().toString().equalsIgnoreCase(selector + ".kt")
                            || relative.equals(selector.replaceFirst("Kt$", "") + ".kt");
                })) return true;
            } catch (Exception e) { throw new DevServerStartupException("Could not inspect " + source, e); }
        }
        try {
            return (test ? MainClassDetector.testCandidates(module.testClassesDirectory())
                    : MainClassDetector.candidates(module.classesDirectory())).stream()
                    .anyMatch(name -> name.equals(selector) || name.substring(name.lastIndexOf('.') + 1).equalsIgnoreCase(selector));
        } catch (Exception e) { throw new DevServerStartupException("Could not inspect main classes in " + module.directory(), e); }
    }

    /** Keep pre-compilation inspection owned during normal exit and JVM shutdown alike. */
    private static final class InspectionProcess implements AutoCloseable {
        private final Thread shutdown = Thread.ofPlatform().name("fluxzero-sdk-inspection-cleanup").unstarted(this::close);
        private Process process;
        private boolean closed;

        InspectionProcess() { Runtime.getRuntime().addShutdownHook(shutdown); }

        synchronized Process start(List<String> command, Path directory) throws java.io.IOException {
            if (closed) throw new IllegalStateException("SDK inspection was cancelled");
            return process = ProcessUtils.start(command, directory, MavenCommand.environment(), ignored -> {});
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (process != null && process.isAlive()) ProcessUtils.stopTree(process, Duration.ofSeconds(2));
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
    }

    record SelectedModule(String id, MavenReactor.Module module, boolean test) { }
}
