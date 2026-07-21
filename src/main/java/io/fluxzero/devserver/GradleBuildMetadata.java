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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record GradleBuildMetadata(List<Module> modules) {
    static final Path FILE = DevSessionStore.DEV_DIRECTORY.resolve("gradle-metadata.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    GradleBuildMetadata {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    static GradleBuildMetadata load(Path projectDirectory) {
        Path file = projectDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "Gradle did not produce " + FILE + ". Apply the Fluxzero Gradle plugin to the root project.");
        }
        try {
            return MAPPER.readValue(file.toFile(), GradleBuildMetadata.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read Gradle dev metadata: " + e.getMessage(), e);
        }
    }

    List<ApplicationBuild> applications(DevServerConfig config) {
        List<Candidate> candidates = new ArrayList<>();
        Map<Module, List<String>> ambiguousModules = new LinkedHashMap<>();
        boolean explicitSelection = (config.mainClass() != null && !config.mainClass().isBlank())
                                    || !config.applications().isEmpty();
        for (Module module : modules) {
            List<String> detected = mainCandidates(module.mainClasses(), false);
            if (explicitSelection || detected.size() <= 1) {
                detected.forEach(mainClass -> candidates.add(new Candidate(module, mainClass, false)));
            } else {
                ambiguousModules.put(module, detected);
            }
        }
        if ((config.mainClass() != null && !config.mainClass().isBlank()) || !config.applications().isEmpty()) {
            for (Module module : modules) {
                mainCandidates(module.testClasses(), true).forEach(
                        mainClass -> candidates.add(new Candidate(module, mainClass, true)));
            }
        }
        if (config.mainClass() != null && !config.mainClass().isBlank()) {
            candidates.removeIf(candidate -> !config.mainClass().equals(candidate.mainClass()));
            if (candidates.isEmpty()) {
                throw new IllegalStateException(
                        "Configured main class " + config.mainClass() + " was not found in compiled Gradle projects");
            }
        }
        List<Selected> selected = select(candidates, config);
        List<Candidate> active = selected.isEmpty() ? List.copyOf(candidates)
                : selected.stream().map(Selected::candidate).distinct().toList();
        if (active.isEmpty()) {
            if (!ambiguousModules.isEmpty()) {
                throw new IllegalStateException(ambiguousMainClasses(ambiguousModules));
            }
            throw new IllegalStateException("No application main classes found in Gradle projects");
        }

        boolean single = active.size() == 1 && modules.size() == 1;
        Set<String> usedNames = new LinkedHashSet<>();
        Map<Candidate, ApplicationBuild> discovered = new LinkedHashMap<>();
        for (Candidate candidate : active) {
            Module module = candidate.module();
            List<Path> classes = new ArrayList<>(candidate.testApplication()
                                                         ? module.testRuntimeDirectories()
                                                         : module.runtimeDirectories());
            if (classes.isEmpty()) {
                if (candidate.testApplication()) {
                    classes.addAll(module.testClasses());
                }
                classes.addAll(module.mainClasses());
            }
            String baseName = candidate.testApplication() ? simpleName(candidate.mainClass()) : module.name();
            String applicationName = single && !candidate.testApplication()
                    ? config.applicationName() : uniqueName(baseName, module.path(), usedNames);
            usedNames.add(applicationName);
            discovered.put(candidate, new ApplicationBuild(
                    applicationName, module.path(), candidate.mainClass(), classes,
                    candidate.testApplication() ? module.testRuntimeClasspath() : module.runtimeClasspath(),
                    candidate.testApplication()));
        }
        return selected.isEmpty() ? List.copyOf(discovered.values()) : selected.stream()
                .map(item -> configured(discovered.get(item.candidate()), item.selection())).toList();
    }

    private static List<String> mainCandidates(List<Path> directories, boolean test) {
        return directories.stream().filter(Files::isDirectory)
                .flatMap(directory -> candidates(directory, test).stream())
                .distinct().toList();
    }

    private static List<String> candidates(Path directory, boolean test) {
        try {
            return test ? MainClassDetector.testCandidates(directory) : MainClassDetector.candidates(directory);
        } catch (Exception e) {
            throw new IllegalStateException("Could not inspect Gradle classes in " + directory, e);
        }
    }

    private static List<Selected> select(List<Candidate> candidates, DevServerConfig config) {
        if (config.applications().isEmpty()) {
            return List.of();
        }
        List<Selected> selected = new ArrayList<>();
        for (DevServerConfig.ApplicationSelection selection : config.applicationSelections()) {
            List<Candidate> matches = candidates.stream()
                    .filter(candidate -> matches(selection.selector(), candidate)).toList();
            if (matches.isEmpty()) {
                String available = candidates.stream().map(GradleBuildMetadata::candidateName)
                        .distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException("Selected application configuration " + selection.id()
                                                + " targets " + selection.selector()
                                                + ", which was not found. Available applications: " + available);
            }
            if (matches.size() > 1) {
                throw new IllegalStateException("Selected application configuration " + selection.id()
                                                + " targets ambiguous selector " + selection.selector());
            }
            selected.add(new Selected(matches.getFirst(), selection));
        }
        return List.copyOf(selected);
    }

    private static boolean matches(String selector, Candidate candidate) {
        String simple = simpleName(candidate.mainClass());
        String module = candidate.module().path();
        return selector.equals(candidate.mainClass()) || selector.equalsIgnoreCase(simple)
               || selector.equalsIgnoreCase(module + ":" + simple)
               || selector.equalsIgnoreCase(candidate.module().name() + ":" + simple)
               || (!candidate.testApplication()
                   && (selector.equals(module) || selector.equals(candidate.module().name())));
    }

    private static String candidateName(Candidate candidate) {
        return candidate.testApplication()
                ? simpleName(candidate.mainClass()) + " (test app in " + candidate.module().path() + ")"
                : candidate.module().name();
    }

    private static String ambiguousMainClasses(Map<Module, List<String>> modules) {
        String details = modules.entrySet().stream()
                .map(entry -> entry.getKey().name() + ": " + String.join(", ", entry.getValue()))
                .collect(java.util.stream.Collectors.joining("; "));
        return "Multiple main classes found in unconfigured Gradle modules (" + details
               + "). Configure an application or select one with --app <main-class>.";
    }

    private static ApplicationBuild configured(ApplicationBuild discovered,
                                               DevServerConfig.ApplicationSelection selection) {
        String applicationName = selection.applicationName() == null
                ? discovered.applicationName() : selection.applicationName();
        return new ApplicationBuild(applicationName, discovered.module(), discovered.mainClass(),
                                    discovered.classesDirectories(), discovered.runtimeClasspath(),
                                    discovered.testApplication(), selection.id(), selection.env(),
                                    selection.secrets());
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static String uniqueName(String base, String path, Set<String> used) {
        return used.contains(base) ? path.replace(':', '-').replace('/', '-') : base;
    }

    record Module(String path, String name, List<Path> mainClasses, List<Path> testClasses,
                  List<Path> runtimeDirectories, List<Path> testRuntimeDirectories,
                  List<Path> runtimeClasspath, List<Path> testRuntimeClasspath) {
        Module {
            path = path == null || path.isBlank() ? "." : path;
            name = name == null || name.isBlank() ? path : name;
            mainClasses = mainClasses == null ? List.of() : List.copyOf(mainClasses);
            testClasses = testClasses == null ? List.of() : List.copyOf(testClasses);
            runtimeDirectories = runtimeDirectories == null ? List.of() : List.copyOf(runtimeDirectories);
            testRuntimeDirectories = testRuntimeDirectories == null ? List.of() : List.copyOf(testRuntimeDirectories);
            runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
            testRuntimeClasspath = testRuntimeClasspath == null ? List.of() : List.copyOf(testRuntimeClasspath);
        }
    }

    private record Candidate(Module module, String mainClass, boolean testApplication) {
    }

    private record Selected(Candidate candidate, DevServerConfig.ApplicationSelection selection) {
    }
}
