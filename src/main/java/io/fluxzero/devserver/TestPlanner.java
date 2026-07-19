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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

final class TestPlanner {
    private static final Pattern TEST_CLASS_NAME = Pattern.compile(
            ".*(?:Test|Tests|TestCase|IT|ITCase|Spec)");
    private static final List<String> TEST_SOURCE_MARKERS = List.of(
            "org.junit.", "org.testng.", "kotlin.test.", "io.kotest.", "spock.lang.");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path projectDirectory;
    private List<Path> sourceRoots;

    TestPlanner() {
        this(Path.of("").toAbsolutePath());
    }

    TestPlanner(Path projectDirectory) {
        this.projectDirectory = projectDirectory.toAbsolutePath().normalize();
    }

    TestPlan plan(Set<Path> changedFiles, Set<String> previouslyFailingTests) {
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> selectorReasons = new LinkedHashMap<>();
        previouslyFailingTests.stream().filter(this::isTestSelector).forEach(selector -> addSelector(
                selectors, selectorReasons, selector, "previously failed"));
        String reason = "previously failing tests";
        boolean changedTestClass = false;
        for (Path changedFile : changedFiles) {
            String path = changedFile.toString().replace('\\', '/');
            if (path.contains("/src/test/") && isSourceFile(path) && isTestClass(changedFile)) {
                addSelector(selectors, selectorReasons, className(changedFile).orElseGet(() -> testSelector(changedFile)),
                            "test source changed: " + relativePath(changedFile));
                reason = "changed test class";
                changedTestClass = true;
            }
        }
        if (changedTestClass) {
            return TestPlan.selected(selectors, reason, selectorReasons,
                                     ChangeSummary.of(projectDirectory, changedFiles).displayPaths());
        }
        Map<String, String> impactedTests = impactSelectors(changedFiles);
        if (!impactedTests.isEmpty()) {
            impactedTests.forEach((selector, impactReason) -> addSelector(
                    selectors, selectorReasons, selector, impactReason));
            return TestPlan.selected(
                    selectors,
                    previouslyFailingTests.isEmpty()
                            ? "test impact index" : "test impact index and previously failing tests",
                    selectorReasons,
                    ChangeSummary.of(projectDirectory, changedFiles).displayPaths());
        }
        if (!selectors.isEmpty()) {
            return TestPlan.selected(selectors, reason, selectorReasons, "retrying previous failures");
        }
        boolean appCodeChanged = changedFiles.stream().map(path -> path.toString().replace('\\', '/'))
                .anyMatch(path -> path.contains("/src/main/") && isSourceFile(path));
        if (appCodeChanged) {
            return TestPlan.module("changed app code fallback",
                                   "no observed test impact for "
                                   + ChangeSummary.of(projectDirectory, changedFiles).displayPaths());
        }
        boolean broadChange = changedFiles.stream().map(path -> path.getFileName().toString())
                .anyMatch(name -> name.equals("pom.xml") || name.equals("build.gradle")
                                  || name.equals("build.gradle.kts") || name.equals("settings.gradle")
                                  || name.equals("settings.gradle.kts") || name.endsWith(".properties")
                                  || name.endsWith(".xml") || name.equals("libs.versions.toml"));
        return broadChange ? TestPlan.module(
                "build/resource change fallback",
                "build or resource changed: " + ChangeSummary.of(projectDirectory, changedFiles).displayPaths())
                : TestPlan.none();
    }

    private static boolean isSourceFile(String path) {
        return path.endsWith(".java") || path.endsWith(".kt");
    }

    private boolean isTestClass(Path source) {
        if (TEST_CLASS_NAME.matcher(testSelector(source)).matches()) {
            return true;
        }
        Path absolute = source.isAbsolute() ? source : projectDirectory.resolve(source);
        if (!Files.isRegularFile(absolute)) {
            return false;
        }
        try {
            String content = Files.readString(absolute);
            return TEST_SOURCE_MARKERS.stream().anyMatch(content::contains);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isTestSelector(String selector) {
        String className = selector.contains("#") ? selector.substring(0, selector.indexOf('#')) : selector;
        if (className.contains("$")) {
            className = className.substring(0, className.indexOf('$'));
        }
        String relativeJava = className.replace('.', '/') + ".java";
        String relativeKotlin = className.replace('.', '/') + ".kt";
        for (Path root : testSourceRoots()) {
            Path source = root.resolve(relativeJava);
            if (Files.isRegularFile(source)) {
                return isTestClass(source);
            }
            source = root.resolve(relativeKotlin);
            if (Files.isRegularFile(source)) {
                return isTestClass(source);
            }
        }
        // A removed test must remain retryable so stale impact/selectors stay visible instead of being hidden.
        return true;
    }

    private List<Path> testSourceRoots() {
        if (sourceRoots != null) {
            return sourceRoots;
        }
        if (Files.isRegularFile(projectDirectory.resolve("pom.xml"))) {
            sourceRoots = MavenReactor.load(projectDirectory).modules().stream()
                    .flatMap(module -> java.util.stream.Stream.of(
                            module.directory().resolve("src/test/java"),
                            module.directory().resolve("src/test/kotlin")))
                    .toList();
        } else {
            sourceRoots = List.of(
                    projectDirectory.resolve("src/test/java"), projectDirectory.resolve("src/test/kotlin"));
        }
        return sourceRoots;
    }

    private Map<String, String> impactSelectors(Set<Path> changedFiles) {
        Set<String> changedClasses = changedFiles.stream()
                .flatMap(path -> className(path).stream())
                .collect(java.util.stream.Collectors.toSet());
        if (changedClasses.isEmpty()) {
            return Map.of();
        }
        Path impactFile = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY)
                .resolve(DevSessionStore.TEST_IMPACT_FILE);
        if (!Files.isRegularFile(impactFile)) {
            return Map.of();
        }
        try {
            JsonNode tests = objectMapper.readTree(impactFile.toFile()).path("tests");
            Map<String, String> selectors = new LinkedHashMap<>();
            tests.properties().forEach(entry -> {
                List<String> matches = changedClasses.stream().sorted()
                        .filter(changedClass -> referencesChangedClass(entry.getValue(), Set.of(changedClass)))
                        .map(TestPlanner::simpleName)
                        .toList();
                if (!matches.isEmpty()) {
                    selectors.put(entry.getKey(), "observed " + String.join(", ", matches));
                }
            });
            return selectors;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static void addSelector(Set<String> selectors, Map<String, LinkedHashSet<String>> reasons,
                                    String selector, String reason) {
        selectors.add(selector);
        reasons.computeIfAbsent(selector, ignored -> new LinkedHashSet<>()).add(reason);
    }

    private String relativePath(Path path) {
        return ChangeSummary.of(projectDirectory, Set.of(path)).displayPaths();
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private Optional<String> className(Path changedFile) {
        Path absolute = changedFile.isAbsolute()
                ? changedFile.toAbsolutePath().normalize()
                : projectDirectory.resolve(changedFile).toAbsolutePath().normalize();
        Path relative;
        try {
            relative = projectDirectory.relativize(absolute);
        } catch (IllegalArgumentException e) {
            relative = absolute;
        }
        String path = relative.toString().replace('\\', '/');
        for (String root : List.of("src/main/java/", "src/main/kotlin/", "src/test/java/", "src/test/kotlin/")) {
            int rootIndex = path.indexOf(root);
            if (rootIndex >= 0 && (rootIndex == 0 || path.charAt(rootIndex - 1) == '/') && isSourceFile(path)) {
                String classPath = path.substring(rootIndex + root.length(), path.lastIndexOf('.'));
                return Optional.of(classPath.replace('/', '.'));
            }
        }
        return Optional.empty();
    }

    private static boolean referencesChangedClass(JsonNode impact, Set<String> changedClasses) {
        return containsClassReference(impact.path("handlers"), changedClasses, true)
               || containsClassReference(impact.path("payloads"), changedClasses, false)
               || containsClassReference(impact.path("schedulePayloads"), changedClasses, false)
               || containsUsagePayload(impact.path("messages"), changedClasses)
               || containsUsagePayload(impact.path("web"), changedClasses);
    }

    private static boolean containsClassReference(JsonNode array, Set<String> changedClasses, boolean handler) {
        if (!array.isArray()) {
            return false;
        }
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .map(value -> handler && value.contains("#") ? value.substring(0, value.indexOf('#')) : value)
                .anyMatch(changedClasses::contains);
    }

    private static boolean containsUsagePayload(JsonNode array, Set<String> changedClasses) {
        if (!array.isArray()) {
            return false;
        }
        return StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.path("payloadClass").asText(null))
                .anyMatch(changedClasses::contains);
    }

    private static String testSelector(Path file) {
        String name = file.getFileName().toString();
        int extension = name.lastIndexOf('.');
        return extension < 0 ? name : name.substring(0, extension);
    }

    record TestPlan(List<String> selectors, String reason, boolean runModule,
                    Map<String, String> selectorReasons, String explanation) {
        TestPlan {
            selectors = List.copyOf(selectors);
            selectorReasons = Collections.unmodifiableMap(new LinkedHashMap<>(selectorReasons));
        }

        static TestPlan none() {
            return new TestPlan(List.of(), "no affected tests", false, Map.of(), "no affected tests");
        }

        static TestPlan module(String reason) {
            return module(reason, reason);
        }

        static TestPlan module(String reason, String explanation) {
            return new TestPlan(List.of(), reason, true, Map.of(), explanation);
        }

        static TestPlan selected(Set<String> selectors, String reason,
                                 Map<String, LinkedHashSet<String>> reasons, String explanation) {
            Map<String, String> flattened = new LinkedHashMap<>();
            selectors.stream().sorted().forEach(selector -> flattened.put(
                    selector, String.join("; ", reasons.getOrDefault(selector, new LinkedHashSet<>()))));
            return new TestPlan(List.copyOf(selectors), reason, false, flattened, explanation);
        }

        boolean shouldRun() {
            return runModule || !selectors.isEmpty();
        }

        List<String> stableSelectors() {
            return selectors.stream().sorted(Comparator.naturalOrder()).toList();
        }
    }
}
