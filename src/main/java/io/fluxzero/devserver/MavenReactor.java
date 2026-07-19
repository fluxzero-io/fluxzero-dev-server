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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MavenReactor {
    static final Path CLASSPATH_FILE = Path.of("target/fluxzero-dev/runtime-classpath.txt");
    static final Path TEST_CLASSPATH_FILE = Path.of("target/fluxzero-dev/test-classpath.txt");

    private final Path root;
    private final List<Module> modules;
    private final Map<String, Module> modulesByArtifactId;

    private MavenReactor(Path root, List<Module> modules) {
        this.root = root;
        this.modules = List.copyOf(modules);
        Map<String, Module> byArtifactId = new LinkedHashMap<>();
        modules.stream().filter(module -> module.artifactId() != null)
                .forEach(module -> byArtifactId.putIfAbsent(module.artifactId(), module));
        this.modulesByArtifactId = Map.copyOf(byArtifactId);
    }

    static MavenReactor load(Path root) {
        try {
            List<Module> modules = new ArrayList<>();
            readModule(root.toAbsolutePath().normalize(), root.toAbsolutePath().normalize(), modules,
                       new LinkedHashSet<>());
            if (modules.isEmpty()) {
                Path normalized = root.toAbsolutePath().normalize();
                Path fileName = normalized.getFileName();
                modules.add(new Module(normalized, Path.of(""), fileName == null ? "app" : fileName.toString(),
                                       List.of(), null));
            }
            return new MavenReactor(root.toAbsolutePath().normalize(), modules);
        } catch (Exception e) {
            throw new IllegalStateException("Could not inspect Maven reactor: " + e.getMessage(), e);
        }
    }

    boolean multiModule() {
        return modules.size() > 1;
    }

    List<ApplicationBuild> applications(DevServerConfig config) {
        try {
            List<Candidate> candidates = new ArrayList<>();
            for (Module module : modules) {
                List<String> detected = MainClassDetector.candidates(module.classesDirectory());
                String configured = module.configuredMainClass();
                if (configured != null && detected.contains(configured)) {
                    candidates.add(new Candidate(module, configured));
                } else if (detected.size() == 1) {
                    candidates.add(new Candidate(module, detected.getFirst()));
                } else {
                    detected.forEach(mainClass -> candidates.add(new Candidate(module, mainClass)));
                }
            }
            if ((config.mainClass() != null && !config.mainClass().isBlank()) || !config.applications().isEmpty()) {
                for (Module module : modules) {
                    MainClassDetector.testCandidates(module.testClassesDirectory()).stream()
                            .map(mainClass -> new Candidate(module, mainClass, true))
                            .forEach(candidates::add);
                }
            }
            if (config.mainClass() != null && !config.mainClass().isBlank()) {
                candidates.removeIf(candidate -> !config.mainClass().equals(candidate.mainClass()));
                if (candidates.isEmpty()) {
                    throw new IllegalStateException("Configured main class " + config.mainClass()
                                                    + " was not found in compiled reactor modules");
                }
            }
            List<SelectedCandidate> selectedCandidates = selectCandidates(candidates, config);
            List<Candidate> activeCandidates = selectedCandidates.isEmpty() ? List.copyOf(candidates)
                    : selectedCandidates.stream().map(SelectedCandidate::candidate).distinct().toList();
            if (activeCandidates.isEmpty()) {
                if (multiModule()) {
                    throw new IllegalStateException("No application main classes found in Maven reactor");
                }
                Module module = modules.getFirst();
                return List.of(new ApplicationBuild(config.applicationName(), module.relativeName(), null,
                                                    List.of(module.classesDirectory()),
                                                    readClasspath(module.classpathFile())));
            }

            boolean single = activeCandidates.size() == 1 && !multiModule();
            Set<String> usedNames = new LinkedHashSet<>();
            Map<Candidate, ApplicationBuild> discovered = new LinkedHashMap<>();
            for (Candidate candidate : activeCandidates) {
                List<Path> classes = dependencyClosure(candidate.module()).stream()
                        .map(Module::classesDirectory).filter(Files::isDirectory).toList();
                if (candidate.testApplication() && Files.isDirectory(candidate.module().testClassesDirectory())) {
                    List<Path> testClasses = new ArrayList<>();
                    testClasses.add(candidate.module().testClassesDirectory());
                    testClasses.addAll(classes);
                    classes = List.copyOf(testClasses);
                }
                String baseName = candidate.testApplication() ? simpleName(candidate.mainClass())
                        : candidate.module().artifactId();
                String applicationName = single && !candidate.testApplication() ? config.applicationName()
                        : uniqueName(baseName, candidate.module().relativeName(), usedNames);
                usedNames.add(applicationName);
                discovered.put(candidate, new ApplicationBuild(
                        applicationName, candidate.module().relativeName(), candidate.mainClass(), classes,
                        readClasspath(candidate.testApplication()
                                              ? candidate.module().testClasspathFile()
                                              : candidate.module().classpathFile()),
                        candidate.testApplication()));
            }
            List<ApplicationBuild> result = selectedCandidates.isEmpty()
                    ? new ArrayList<>(discovered.values())
                    : selectedCandidates.stream().map(selected -> configuredBuild(
                            discovered.get(selected.candidate()), selected.selection())).toList();
            return List.copyOf(result);
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Could not discover applications in Maven reactor: " + e.getMessage(), e);
        }
    }

    private static List<SelectedCandidate> selectCandidates(List<Candidate> candidates, DevServerConfig config) {
        if (config.applications().isEmpty()) {
            return List.of();
        }
        List<SelectedCandidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            for (DevServerConfig.ApplicationSelection selection : config.applicationSelections()) {
                if (matches(selection.selector(), candidate)) {
                    selected.add(new SelectedCandidate(candidate, selection));
                }
            }
        }
        for (DevServerConfig.ApplicationSelection selection : config.applicationSelections()) {
            long count = selected.stream().filter(item -> item.selection().equals(selection)).count();
            if (count == 0) {
                String available = candidates.stream().map(MavenReactor::candidateName)
                        .distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException("Selected application configuration " + selection.id()
                                                + " targets " + selection.selector()
                                                + ", which was not found. Available applications: " + available);
            }
            if (count > 1) {
                throw new IllegalStateException("Selected application configuration " + selection.id()
                                                + " targets ambiguous selector " + selection.selector());
            }
        }
        return List.copyOf(selected);
    }

    private static ApplicationBuild configuredBuild(ApplicationBuild discovered,
                                                     DevServerConfig.ApplicationSelection selection) {
        String applicationName = selection.applicationName() == null
                ? discovered.applicationName() : selection.applicationName();
        return new ApplicationBuild(applicationName, discovered.module(), discovered.mainClass(),
                                    discovered.classesDirectories(), discovered.runtimeClasspath(),
                                    discovered.testApplication(), selection.id(), selection.env(),
                                    selection.secrets());
    }

    Collection<Module> modules() {
        return modules;
    }

    private static boolean matches(String selector, Candidate candidate) {
        String simpleName = simpleName(candidate.mainClass());
        if (selector.equals(candidate.mainClass()) || selector.equalsIgnoreCase(simpleName)
            || selector.equalsIgnoreCase(candidate.module().relativeName() + ":" + simpleName)
            || (candidate.module().artifactId() != null
                && selector.equalsIgnoreCase(candidate.module().artifactId() + ":" + simpleName))) {
            return true;
        }
        return !candidate.testApplication()
               && (selector.equals(candidate.module().artifactId())
                   || selector.equals(candidate.module().relativeName()));
    }

    private static String candidateName(Candidate candidate) {
        if (candidate.testApplication()) {
            return simpleName(candidate.mainClass()) + " (test app in " + candidate.module().relativeName() + ")";
        }
        return candidate.module().artifactId() == null
                ? candidate.module().relativeName() : candidate.module().artifactId();
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private List<Module> dependencyClosure(Module application) {
        LinkedHashSet<Module> result = new LinkedHashSet<>();
        collectDependencies(application, result);
        return List.copyOf(result);
    }

    private void collectDependencies(Module module, Set<Module> result) {
        if (!result.add(module)) {
            return;
        }
        for (String dependency : module.dependencyArtifactIds()) {
            Module dependencyModule = modulesByArtifactId.get(dependency);
            if (dependencyModule != null) {
                collectDependencies(dependencyModule, result);
            }
        }
    }

    private static void readModule(Path root, Path directory, List<Module> modules, Set<Path> visited)
            throws Exception {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            return;
        }
        Path pom = normalized.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());
        Element project = document.getDocumentElement();
        String artifactId = childText(project, "artifactId");
        List<String> dependencies = dependencyArtifactIds(project);
        String configuredMain = configuredMainClass(project);
        Module module = new Module(normalized, root.relativize(normalized), artifactId, dependencies, configuredMain);
        modules.add(module);
        Element modulesElement = child(project, "modules");
        if (modulesElement != null) {
            for (Element moduleElement : children(modulesElement, "module")) {
                readModule(root, normalized.resolve(moduleElement.getTextContent().strip()), modules, visited);
            }
        }
    }

    private static List<String> dependencyArtifactIds(Element project) {
        Element dependencies = child(project, "dependencies");
        if (dependencies == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Element dependency : children(dependencies, "dependency")) {
            String artifactId = childText(dependency, "artifactId");
            if (artifactId != null) {
                result.add(artifactId);
            }
        }
        return List.copyOf(result);
    }

    private static String configuredMainClass(Element project) {
        NodeList nodes = project.getElementsByTagName("mainClass");
        for (int index = 0; index < nodes.getLength(); index++) {
            String value = nodes.item(index).getTextContent();
            if (value != null && !value.isBlank() && !value.contains("${")) {
                return value.strip();
            }
        }
        return null;
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element child = child(parent, name);
        if (child == null || child.getTextContent() == null || child.getTextContent().isBlank()) {
            return null;
        }
        return child.getTextContent().strip();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Path> readClasspath(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            String raw = Files.readString(file).strip();
            if (raw.isBlank()) {
                return List.of();
            }
            String separator = System.getProperty("path.separator");
            List<Path> result = new ArrayList<>();
            for (String entry : raw.split(java.util.regex.Pattern.quote(separator))) {
                if (!entry.isBlank()) {
                    result.add(Path.of(entry));
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String uniqueName(String artifactId, String relativeName, Set<String> usedNames) {
        String base = artifactId == null || artifactId.isBlank() ? relativeName.replace('/', '-') : artifactId;
        if (!usedNames.contains(base)) {
            return base;
        }
        return relativeName.replace('/', '-');
    }

    record Module(Path directory, Path relativeDirectory, String artifactId, List<String> dependencyArtifactIds,
                  String configuredMainClass) {
        Path classesDirectory() {
            return directory.resolve("target/classes");
        }

        Path testClassesDirectory() {
            return directory.resolve("target/test-classes");
        }

        Path classpathFile() {
            return directory.resolve(CLASSPATH_FILE);
        }

        Path testClasspathFile() {
            return directory.resolve(TEST_CLASSPATH_FILE);
        }

        String relativeName() {
            String value = relativeDirectory.toString().replace('\\', '/');
            return value.isBlank() ? "." : value;
        }
    }

    private record Candidate(Module module, String mainClass, boolean testApplication) {
        private Candidate(Module module, String mainClass) {
            this(module, mainClass, false);
        }
    }

    private record SelectedCandidate(Candidate candidate, DevServerConfig.ApplicationSelection selection) {
    }
}
