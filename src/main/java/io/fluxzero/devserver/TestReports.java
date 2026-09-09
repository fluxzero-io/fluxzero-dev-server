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

import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TestReports {
    private TestReports() {
    }

    static void clear(Path projectDirectory, BuildTool buildTool) {
        reportDirectories(projectDirectory, buildTool).forEach(TestReports::clearReportDirectory);
    }

    static Result read(Path projectDirectory, BuildTool buildTool, long startedAt) {
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        List<String> summaries = new ArrayList<>();
        boolean[] failureFound = {false};
        reportDirectories(projectDirectory, buildTool).forEach(
                reports -> readReportDirectory(reports, startedAt, selectors, summaries, failureFound));
        return new Result(Set.copyOf(selectors), summaries.isEmpty() ? null : summaries.getFirst(), failureFound[0]);
    }

    private static void clearReportDirectory(Path reports) {
        if (!Files.isDirectory(reports)) {
            return;
        }
        try (var files = Files.walk(reports, 4)) {
            files.filter(TestReports::isXmlReport).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // A stale report is filtered by timestamp when deletion is not possible.
                }
            });
        } catch (Exception ignored) {
            // Missing reports are handled as an incomplete test command when it exits unsuccessfully.
        }
    }

    private static void readReportDirectory(Path reports, long startedAt, Set<String> selectors,
                                            List<String> summaries, boolean[] failureFound) {
        if (!Files.isDirectory(reports)) {
            return;
        }
        try (var files = Files.walk(reports, 4)) {
            files.filter(TestReports::isXmlReport).filter(file -> modifiedAfter(file, startedAt))
                    .sorted().forEach(file -> readReport(file, selectors, summaries, failureFound));
        } catch (Exception ignored) {
            // An absent or unreadable report cannot prove that a test failed.
        }
    }

    private static Set<Path> reportDirectories(Path projectDirectory, BuildTool buildTool) {
        return buildTool == BuildTool.MAVEN
                ? mavenReportDirectories(projectDirectory) : gradleReportDirectories(projectDirectory);
    }

    private static Set<Path> mavenReportDirectories(Path projectDirectory) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        try {
            MavenReactor.load(projectDirectory).modules().stream()
                    .map(MavenReactor.Module::directory)
                    .forEach(directory -> {
                        result.add(directory.resolve("target/surefire-reports"));
                        result.add(directory.resolve("target/failsafe-reports"));
                    });
        } catch (Exception ignored) {
            result.add(projectDirectory.resolve("target/surefire-reports"));
            result.add(projectDirectory.resolve("target/failsafe-reports"));
        }
        return result;
    }

    private static Set<Path> gradleReportDirectories(Path projectDirectory) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        result.add(projectDirectory.resolve("build/test-results"));
        try {
            GradleBuildMetadata.load(projectDirectory).modules().stream()
                    .map(GradleBuildMetadata.Module::path)
                    .map(path -> gradleModuleDirectory(projectDirectory, path))
                    .map(directory -> directory.resolve("build/test-results"))
                    .forEach(result::add);
        } catch (Exception ignored) {
            // Root-project reports still cover single-project builds and early metadata failures.
        }
        return result;
    }

    private static Path gradleModuleDirectory(Path projectDirectory, String projectPath) {
        if (projectPath == null || projectPath.isBlank() || ".".equals(projectPath) || ":".equals(projectPath)) {
            return projectDirectory;
        }
        Path result = projectDirectory;
        for (String segment : projectPath.split(":")) {
            if (!segment.isBlank()) {
                result = result.resolve(segment);
            }
        }
        return result;
    }

    private static void readReport(Path file, Set<String> selectors, List<String> summaries,
                                   boolean[] failureFound) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var testCases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                Element testCase = (Element) testCases.item(i);
                if (testCase.getElementsByTagName("failure").getLength() == 0
                    && testCase.getElementsByTagName("error").getLength() == 0) {
                    continue;
                }
                failureFound[0] = true;
                String className = testCase.getAttribute("classname");
                String methodName = selectorMethod(testCase.getAttribute("name"));
                if (!className.isBlank()) {
                    String selector = methodName == null ? className : className + "#" + methodName;
                    selectors.add(selector);
                    if (summaries.isEmpty()) {
                        summaries.add(failureSummary(testCase, selector));
                    }
                }
            }
        } catch (Exception ignored) {
            // A malformed report cannot prove that a test failed.
        }
    }

    private static String failureSummary(Element testCase, String selector) {
        Element problem = testCase.getElementsByTagName("failure").getLength() > 0
                ? (Element) testCase.getElementsByTagName("failure").item(0)
                : (Element) testCase.getElementsByTagName("error").item(0);
        String message = problem.getAttribute("message");
        if (message == null || message.isBlank()) {
            message = problem.getTextContent();
        }
        message = message == null ? "test failed" : message.replaceAll("\\s+", " ").strip();
        if (message.length() > 240) {
            message = message.substring(0, 237) + "...";
        }
        return selector + ": " + message;
    }

    private static String selectorMethod(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int parameterStart = name.indexOf('(');
        int invocationStart = name.indexOf('[');
        int end = parameterStart < 0 ? invocationStart
                : invocationStart < 0 ? parameterStart : Math.min(parameterStart, invocationStart);
        String candidate = (end < 0 ? name : name.substring(0, end)).strip();
        return candidate.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? candidate : null;
    }

    private static boolean isXmlReport(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && name.startsWith("TEST-") && name.endsWith(".xml");
    }

    private static boolean modifiedAfter(Path file, long startedAt) {
        try {
            return Files.getLastModifiedTime(file).toMillis() >= startedAt - 1_000;
        } catch (Exception e) {
            return false;
        }
    }

    record Result(Set<String> failingSelectors, String firstFailure, boolean failureFound) {
        static Result empty() {
            return new Result(Set.of(), null, false);
        }
    }
}
