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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SurefireFailures {
    private SurefireFailures() {
    }

    static void clear(Path projectDirectory) {
        reportDirectories(projectDirectory).forEach(SurefireFailures::clearReportDirectory);
    }

    private static void clearReportDirectory(Path reports) {
        if (!Files.isDirectory(reports)) {
            return;
        }
        try (var files = Files.list(reports)) {
            files.filter(SurefireFailures::isXmlReport).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // A stale report is filtered by timestamp when deletion is not possible.
                }
            });
        } catch (Exception ignored) {
            // Missing reports result in a conservative module-level retry.
        }
    }

    static Result read(Path projectDirectory, long startedAt) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        java.util.ArrayList<String> summaries = new java.util.ArrayList<>();
        reportDirectories(projectDirectory).forEach(
                reports -> readReportDirectory(reports, startedAt, result, summaries));
        return new Result(Set.copyOf(result), summaries.isEmpty() ? null : summaries.getFirst());
    }

    private static void readReportDirectory(Path reports, long startedAt, Set<String> result,
                                            List<String> summaries) {
        if (!Files.isDirectory(reports)) {
            return;
        }
        try (var files = Files.list(reports)) {
            files.filter(SurefireFailures::isXmlReport).filter(file -> modifiedAfter(file, startedAt))
                    .sorted().forEach(file -> readReport(file, result, summaries));
        } catch (Exception ignored) {
            // Missing reports result in a conservative module-level retry.
        }
    }

    private static Set<Path> reportDirectories(Path projectDirectory) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        try {
            MavenReactor.load(projectDirectory).modules().stream()
                    .map(MavenReactor.Module::directory)
                    .map(directory -> directory.resolve("target/surefire-reports"))
                    .forEach(result::add);
        } catch (Exception ignored) {
            result.add(projectDirectory.resolve("target/surefire-reports"));
        }
        return result;
    }

    private static void readReport(Path file, Set<String> result, List<String> summaries) {
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
                String className = testCase.getAttribute("classname");
                String methodName = selectorMethod(testCase.getAttribute("name"));
                if (!className.isBlank()) {
                    String selector = methodName == null ? className : className + "#" + methodName;
                    result.add(selector);
                    if (summaries.isEmpty()) {
                        summaries.add(failureSummary(testCase, selector));
                    }
                }
            }
        } catch (Exception ignored) {
            // A malformed report is handled by the caller's module-level retry fallback.
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

    record Result(Set<String> selectors, String firstFailure) {
    }
}
