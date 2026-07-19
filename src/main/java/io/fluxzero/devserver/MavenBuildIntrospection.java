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
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Maven-derived build information used by the conservative fast compiler path.
 */
record MavenBuildIntrospection(
        Path projectDirectory,
        Path classesDirectory,
        Path runtimeClasspathFile,
        List<Path> runtimeClasspath,
        Path generatedSourcesDirectory,
        Path compileClasspathFile,
        List<Path> compileClasspath,
        String compilerRelease
) {
    private static final String DEFAULT_COMPILER_RELEASE = "21";

    static MavenBuildIntrospection load(DevServerConfig config) {
        Path projectDirectory = config.projectDirectory();
        Path devDirectory = projectDirectory.resolve("target/fluxzero-dev");
        Path compileClasspathFile = devDirectory.resolve("compile-classpath.txt");
        return new MavenBuildIntrospection(
                projectDirectory,
                projectDirectory.resolve("target/classes"),
                devDirectory.resolve("runtime-classpath.txt"),
                readClasspath(devDirectory.resolve("runtime-classpath.txt")),
                projectDirectory.resolve("target/generated-sources/annotations"),
                compileClasspathFile,
                readClasspath(compileClasspathFile),
                compilerRelease(projectDirectory));
    }

    boolean fastMetadataAvailable() {
        return Files.isRegularFile(compileClasspathFile);
    }

    boolean runtimeMetadataAvailable() {
        return Files.isRegularFile(runtimeClasspathFile);
    }

    Set<Path> mainJavaSources() throws IOException {
        Path sourceDirectory = projectDirectory.resolve("src/main/java");
        if (!Files.isDirectory(sourceDirectory)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
    }

    void refreshCompileClasspath(Consumer<String> output) throws IOException, InterruptedException {
        Files.createDirectories(compileClasspathFile.getParent());
        List<String> command = new ArrayList<>(MavenCommand.command(
                projectDirectory,
                "dependency:build-classpath",
                "-DincludeScope=compile",
                "-Dmdep.outputFile=" + compileClasspathFile.toAbsolutePath()));
        output.accept("[compile] refreshing Maven compile classpath");
        ProcessUtils.ProcessResult result = ProcessUtils.run(
                command,
                projectDirectory,
                MavenCommand.environment(),
                line -> output.accept("[compile] " + line));
        if (!result.success()) {
            throw new IllegalStateException("failed to refresh compile classpath"
                                            + System.lineSeparator() + result.tail(20));
        }
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
            List<Path> entries = new ArrayList<>();
            for (String entry : raw.split(java.util.regex.Pattern.quote(separator))) {
                if (!entry.isBlank()) {
                    entries.add(Path.of(entry));
                }
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String compilerRelease(Path projectDirectory) {
        Path pom = projectDirectory.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return DEFAULT_COMPILER_RELEASE;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            Optional<String> direct = firstText(document, "maven.compiler.release")
                    .or(() -> firstText(document, "release"));
            Optional<String> resolved = direct.map(value -> resolveProperty(document, value));
            return resolved.filter(MavenBuildIntrospection::isJavaRelease)
                    .or(() -> firstText(document, "java.version").filter(MavenBuildIntrospection::isJavaRelease))
                    .orElse(DEFAULT_COMPILER_RELEASE);
        } catch (IOException | ParserConfigurationException | SAXException e) {
            return DEFAULT_COMPILER_RELEASE;
        }
    }

    private static Optional<String> firstText(Document document, String elementName) {
        NodeList nodes = document.getElementsByTagName(elementName);
        if (nodes.getLength() == 0) {
            return Optional.empty();
        }
        String text = nodes.item(0).getTextContent();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.strip());
    }

    private static String resolveProperty(Document document, String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        return firstText(document, value.substring(2, value.length() - 1)).orElse(value);
    }

    private static boolean isJavaRelease(String value) {
        return value != null && value.matches("\\d+");
    }
}
