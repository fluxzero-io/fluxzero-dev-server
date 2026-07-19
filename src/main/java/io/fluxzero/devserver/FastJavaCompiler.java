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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class FastJavaCompiler {
    private FastJavaCompiler() {
    }

    static Result compile(Set<Path> sourceFiles, MavenBuildIntrospection build, Path classesDirectory,
                          Path generatedSourcesDirectory) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.failed("no system Java compiler available; run the dev server with a JDK");
        }
        List<Path> existingSources = sourceFiles.stream()
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
        if (existingSources.isEmpty()) {
            return Result.failed("no existing Java source files for fast compilation");
        }
        try {
            Files.createDirectories(classesDirectory);
            Files.createDirectories(generatedSourcesDirectory);
        } catch (IOException e) {
            return Result.failed(e.getMessage());
        }

        long started = System.nanoTime();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(existingSources);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    compilerOutput,
                    fileManager,
                    diagnostics,
                    options(build, classesDirectory, generatedSourcesDirectory),
                    null,
                    units);
            boolean success = Boolean.TRUE.equals(task.call());
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            String detail = diagnostics(diagnostics, compilerOutput.toString());
            return success ? Result.succeeded(elapsedMillis, detail) : Result.failed(elapsedMillis, detail);
        } catch (RuntimeException | IOException e) {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return Result.failed(elapsedMillis, e.getMessage());
        }
    }

    private static List<String> options(MavenBuildIntrospection build, Path classesDirectory,
                                        Path generatedSourcesDirectory) {
        List<String> classpath = new ArrayList<>();
        classpath.add(classesDirectory.toString());
        build.compileClasspath().stream()
                .map(Path::toString)
                .forEach(classpath::add);
        String classpathValue = String.join(System.getProperty("path.separator"), classpath);
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(build.compilerRelease());
        options.add("-parameters");
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-cp");
        options.add(classpathValue);
        options.add("-processorpath");
        options.add(classpathValue);
        options.add("-proc:full");
        options.add("-d");
        options.add(classesDirectory.toString());
        options.add("-s");
        options.add(generatedSourcesDirectory.toString());
        return options;
    }

    private static String diagnostics(DiagnosticCollector<JavaFileObject> diagnostics, String compilerOutput) {
        StringBuilder builder = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(diagnostic.getKind());
            if (diagnostic.getSource() != null) {
                builder.append(' ').append(Path.of(diagnostic.getSource().toUri()).getFileName());
                if (diagnostic.getLineNumber() > 0) {
                    builder.append(':').append(diagnostic.getLineNumber());
                }
            }
            builder.append(": ").append(diagnostic.getMessage(java.util.Locale.ROOT));
        }
        if (!compilerOutput.isBlank()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(compilerOutput.strip());
        }
        return builder.toString();
    }

    record Result(boolean success, long millis, String detail) {
        static Result succeeded(long millis, String detail) {
            return new Result(true, millis, detail);
        }

        static Result failed(String detail) {
            return new Result(false, -1, detail);
        }

        static Result failed(long millis, String detail) {
            return new Result(false, millis, detail);
        }
    }
}
