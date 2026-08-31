/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class FluxzeroSdkVersionDetector {
    static final String VERSION_OVERRIDE_PROPERTY = "fluxzero.dev.runtime.version";
    static final String VERSION_OVERRIDE_ENV = "FLUXZERO_DEV_RUNTIME_VERSION";

    private static final Pattern SDK_JAR = Pattern.compile("^sdk-(.+)\\.jar$");
    private static final Pattern GRADLE_COORDINATE = Pattern.compile(
            "io\\.fluxzero:(?:sdk|fluxzero-bom):([^'\"\\s)]+)");
    private static final Pattern GRADLE_VERSION_ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(?:fluxzero(?:Sdk)?Version|fluxzero(?:\\.sdk)?\\.version|fluxzero)\\s*[=:]\\s*['\"]?([^'\"\\s]+)");
    private static final Pattern TOML_FLUXZERO_VERSION = Pattern.compile(
            "(?m)^\\s*fluxzero(?:-sdk)?\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FluxzeroSdkVersionDetector() {
    }

    static Selection detect(DevServerConfig config) {
        String override = firstNonBlank(System.getProperty(VERSION_OVERRIDE_PROPERTY), System.getenv(VERSION_OVERRIDE_ENV));
        if (override != null) {
            return new Selection(override, Map.of("override", override), true, Set.of());
        }

        Map<String, String> projectVersions = new LinkedHashMap<>();
        Set<String> fallbackProjects = new LinkedHashSet<>();
        for (DevBuildProject project : config.projects()) {
            Set<String> detected = detect(project.directory());
            if (detected.isEmpty()) {
                projectVersions.put(project.id(), DevServerVersion.sdkVersion());
                fallbackProjects.add(project.id());
            } else {
                projectVersions.put(project.id(), compatibleVersion(project.id(), detected));
            }
        }
        if (projectVersions.isEmpty()) {
            return new Selection(DevServerVersion.sdkVersion(), Map.of(), false, Set.of());
        }
        String selected = compatibleVersion("development environment", new LinkedHashSet<>(projectVersions.values()));
        return new Selection(selected, projectVersions, false, fallbackProjects);
    }

    static Set<String> detect(Path projectDirectory) {
        LinkedHashSet<String> declaredVersions = new LinkedHashSet<>();
        if (Files.isRegularFile(projectDirectory.resolve("pom.xml"))) {
            detectMavenProject(projectDirectory.resolve("pom.xml"), declaredVersions, new LinkedHashSet<>());
        } else {
            detectGradleProject(projectDirectory, declaredVersions);
        }
        if (!declaredVersions.isEmpty()) {
            return Set.copyOf(declaredVersions);
        }
        LinkedHashSet<String> runtimeVersions = new LinkedHashSet<>();
        detectClasspathMetadata(projectDirectory, runtimeVersions);
        return Set.copyOf(runtimeVersions);
    }

    private static void detectClasspathMetadata(Path projectDirectory, Set<String> versions) {
        readClasspath(projectDirectory.resolve("target/fluxzero-dev/runtime-classpath.txt"), versions);
        Path gradleMetadata = projectDirectory.resolve(GradleBuildMetadata.FILE);
        if (Files.isRegularFile(gradleMetadata)) {
            try {
                collectJarVersions(MAPPER.readTree(gradleMetadata.toFile()), versions);
            } catch (Exception ignored) {
                // Build metadata is only a fast-path. Build configuration remains the source of truth.
            }
        }
    }

    private static void readClasspath(Path path, Set<String> versions) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String entry : Files.readString(path).split(Pattern.quote(System.getProperty("path.separator")))) {
                addJarVersion(entry, versions);
            }
        } catch (IOException ignored) {
            // Build configuration remains available when stale classpath metadata cannot be read.
        }
    }

    private static void collectJarVersions(JsonNode node, Set<String> versions) {
        if (node.isTextual()) {
            addJarVersion(node.asText(), versions);
        } else if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectJarVersions(child, versions));
        }
    }

    private static void addJarVersion(String value, Set<String> versions) {
        try {
            Path path = Path.of(value);
            Path fileName = path.getFileName();
            if (fileName == null) {
                return;
            }
            Matcher matcher = SDK_JAR.matcher(fileName.toString());
            if (matcher.matches() && path.toString().replace('\\', '/').contains("/io/fluxzero/sdk/")) {
                versions.add(matcher.group(1));
            }
        } catch (RuntimeException ignored) {
            // Ignore non-path strings in generic Gradle metadata traversal.
        }
    }

    private static void detectMavenProject(Path pom, Set<String> versions, Set<Path> visited) {
        Path normalized = pom.toAbsolutePath().normalize();
        if (!visited.add(normalized) || !Files.isRegularFile(normalized)) {
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(normalized.toFile());
            Element project = document.getDocumentElement();
            Map<String, String> properties = mavenProperties(project);
            String projectVersion = childText(project, "version").orElseGet(() -> child(project, "parent")
                    .flatMap(parent -> childText(parent, "version")).orElse(null));
            properties.put("project.version", projectVersion);
            properties.put("pom.version", projectVersion);

            Stream.of("fluxzero.sdk.version", "fluxzero.version")
                    .map(properties::get).filter(FluxzeroSdkVersionDetector::concreteVersion).forEach(versions::add);
            descendants(project, "dependency").forEach(dependency -> {
                String groupId = childText(dependency, "groupId").orElse("");
                String artifactId = childText(dependency, "artifactId").orElse("");
                if ("io.fluxzero".equals(groupId) && ("sdk".equals(artifactId) || "fluxzero-bom".equals(artifactId))) {
                    childText(dependency, "version").map(value -> resolve(value, properties))
                            .filter(FluxzeroSdkVersionDetector::concreteVersion).ifPresent(versions::add);
                }
            });

            child(project, "modules").ifPresent(modules -> children(modules, "module").forEach(module -> {
                String value = module.getTextContent() == null ? "" : module.getTextContent().strip();
                if (!value.isBlank()) {
                    detectMavenProject(normalized.getParent().resolve(value).resolve("pom.xml"), versions, visited);
                }
            }));
            child(project, "parent").ifPresent(parent -> {
                String relative = childText(parent, "relativePath").orElse("../pom.xml");
                if (!relative.isBlank()) {
                    detectMavenProject(normalized.getParent().resolve(relative), versions, visited);
                }
            });
        } catch (Exception ignored) {
            // A Maven command or explicit override can handle projects that require non-local model resolution.
        }
    }

    private static Map<String, String> mavenProperties(Element project) {
        Map<String, String> result = new LinkedHashMap<>();
        child(project, "properties").ifPresent(properties -> {
            for (Node node = properties.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && element.getTextContent() != null) {
                    result.put(element.getTagName(), element.getTextContent().strip());
                }
            }
        });
        return result;
    }

    private static String resolve(String value, Map<String, String> properties) {
        String current = value == null ? null : value.strip();
        for (int i = 0; current != null && current.startsWith("${") && current.endsWith("}") && i < 8; i++) {
            current = properties.get(current.substring(2, current.length() - 1));
        }
        return current;
    }

    private static void detectGradleProject(Path directory, Set<String> versions) {
        Map<String, String> properties = gradleProperties(directory.resolve("gradle.properties"));
        List<Path> scripts = new ArrayList<>();
        Stream.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .map(directory::resolve).filter(Files::isRegularFile).forEach(scripts::add);
        Path catalog = directory.resolve("gradle/libs.versions.toml");
        if (Files.isRegularFile(catalog)) {
            scripts.add(catalog);
        }
        for (Path script : scripts) {
            try {
                String source = Files.readString(script);
                Matcher assignment = GRADLE_VERSION_ASSIGNMENT.matcher(source);
                while (assignment.find()) {
                    properties.putIfAbsent("fluxzeroVersion", assignment.group(1));
                }
                Matcher toml = TOML_FLUXZERO_VERSION.matcher(source);
                while (toml.find()) {
                    properties.putIfAbsent("fluxzeroVersion", toml.group(1));
                }
                Matcher coordinate = GRADLE_COORDINATE.matcher(source);
                while (coordinate.find()) {
                    String version = resolveGradle(coordinate.group(1), properties);
                    if (concreteVersion(version)) {
                        versions.add(version);
                    }
                }
            } catch (IOException ignored) {
                // Continue with other project metadata.
            }
        }
        Stream.of("fluxzeroVersion", "fluxzeroSdkVersion", "fluxzero.version", "fluxzero.sdk.version")
                .map(properties::get).filter(FluxzeroSdkVersionDetector::concreteVersion).forEach(versions::add);
    }

    private static Map<String, String> gradleProperties(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try (var input = Files.newInputStream(file)) {
            Properties properties = new Properties();
            properties.load(input);
            properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        } catch (IOException ignored) {
            // Build scripts may still contain a concrete version.
        }
        return result;
    }

    private static String resolveGradle(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String candidate = value.replace("${", "").replace("}", "");
        if (candidate.startsWith("$")) {
            candidate = candidate.substring(1);
        }
        return properties.getOrDefault(candidate, value);
    }

    static String compatibleVersion(String owner, Set<String> versions) {
        Map<String, List<String>> byMajor = new LinkedHashMap<>();
        versions.forEach(version -> byMajor.computeIfAbsent(major(version), ignored -> new ArrayList<>()).add(version));
        if (byMajor.size() > 1) {
            throw new DevServerStartupException(
                    "Selected applications use incompatible Fluxzero SDK generations for " + owner + ": "
                    + String.join(", ", versions) + ". Run them in separate dev environments.");
        }
        return versions.stream().max((left, right) -> new ComparableVersion(left).compareTo(new ComparableVersion(right)))
                .orElseThrow();
    }

    private static String major(String version) {
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }

    private static boolean concreteVersion(String value) {
        return value != null && !value.isBlank() && !value.contains("${") && !value.contains("$")
               && value.matches("[0-9][A-Za-z0-9._+-]*");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static Optional<Element> child(Element parent, String name) {
        return children(parent, name).stream().findFirst();
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

    private static Optional<String> childText(Element parent, String name) {
        return child(parent, name).map(Element::getTextContent).map(String::strip).filter(value -> !value.isBlank());
    }

    private static List<Element> descendants(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        var nodes = parent.getElementsByTagName(name);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    record Selection(String version, Map<String, String> projectVersions, boolean overridden,
                     Set<String> fallbackProjects) {
        Selection {
            projectVersions = Map.copyOf(projectVersions);
            fallbackProjects = Set.copyOf(fallbackProjects);
        }
    }
}
