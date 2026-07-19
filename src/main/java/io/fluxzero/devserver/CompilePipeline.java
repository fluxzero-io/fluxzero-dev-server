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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class CompilePipeline {
    private final DevServerConfig config;
    private final MavenBuildCoordinator coordinator;
    private final Consumer<String> output;
    private final AtomicLong buildSequence = new AtomicLong();
    private volatile BuildSnapshot activeSnapshot;

    CompilePipeline(DevServerConfig config, Consumer<String> output) {
        this(config, new MavenBuildCoordinator(), output);
    }

    CompilePipeline(DevServerConfig config, MavenBuildCoordinator coordinator, Consumer<String> output) {
        this.config = config;
        this.coordinator = coordinator;
        this.output = output;
    }

    CompileResult compile(Set<Path> changedFiles) {
        return compile(plan(changedFiles), changedFiles);
    }

    CompilePlan plan(Set<Path> changedFiles) {
        if (BuildTool.detect(config.projectDirectory()) == BuildTool.GRADLE) {
            boolean testApplication = activeSnapshot != null && activeSnapshot.applications().stream()
                    .anyMatch(ApplicationBuild::testApplication);
            return CompilePlan.fromGradle(config.projectDirectory(), testApplication, changedFiles);
        }
        MavenBuildIntrospection build = MavenBuildIntrospection.load(config);
        boolean testApplication = activeSnapshot != null && activeSnapshot.applications().stream()
                .anyMatch(ApplicationBuild::testApplication);
        return CompilePlan.from(config.projectDirectory(), build,
                                config.fastCompilerEnabled() && !testApplication,
                                testApplication, changedFiles);
    }

    CompileResult compile(CompilePlan plan, Set<Path> changedFiles) {
        if (!plan.appReload()) {
            return new CompileResult(true, null, "app compile skipped (" + plan.reason() + ")");
        }
        if (plan.fastCompile()) {
            return compileFast(plan, MavenBuildIntrospection.load(config));
        }
        if (BuildTool.detect(config.projectDirectory()) == BuildTool.GRADLE) {
            return compileGradle(plan);
        }
        return compileMaven(plan, changedFiles);
    }

    private CompileResult compileGradle(CompilePlan plan) {
        try {
            return coordinator.withCompileLock(() -> compileGradleLocked(plan));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CompileResult(false, null, "compile interrupted");
        } catch (Exception e) {
            return new CompileResult(false, null, e.getMessage());
        }
    }

    private CompileResult compileGradleLocked(CompilePlan plan) throws Exception {
        long started = System.nanoTime();
        output.accept("[compile] " + plan.mode() + " because " + plan.reason());
        ProcessUtils.ProcessResult result = ProcessUtils.run(
                GradleCommand.command(config.projectDirectory(), plan.goals().toArray(String[]::new)),
                config.projectDirectory(), GradleCommand.environment(),
                line -> output.accept("[compile] " + line));
        if (!result.success()) {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new CompileResult(false, null, "failed after " + CompileTiming.format(elapsedMillis)
                                                   + System.lineSeparator() + result.tail(20));
        }
        List<ApplicationBuild> applications = GradleBuildMetadata.load(config.projectDirectory()).applications(config);
        StagingBuild stagingBuild = createStaging(applications);
        Path staging = stagingBuild.directory();
        try {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            CompileTiming timing = new CompileTiming(plan.mode(), elapsedMillis);
            ApplicationBuild primary = stagingBuild.applications().getFirst();
            BuildSnapshot snapshot = publish(staging, stagingBuild.primaryClassesDirectory(),
                                             primary.runtimeClasspath(), timing, stagingBuild.applications());
            staging = null;
            return new CompileResult(true, snapshot,
                                     "build " + snapshot.buildNumber() + " ready (" + timing.summary() + ")");
        } finally {
            deleteRecursively(staging);
        }
    }

    private CompileResult compileFast(CompilePlan plan, MavenBuildIntrospection build) {
        BuildSnapshot base = activeSnapshot;
        if (base == null) {
            return compileMaven(new CompilePlan("maven-compile", List.of("compile"), true,
                                                "no active immutable baseline", Set.of()), Set.of());
        }
        Path staging = null;
        try {
            output.accept("[compile] " + plan.mode() + " because " + plan.reason());
            staging = createStaging(base.classesDirectory());
            Path classesDirectory = staging.resolve("classes");
            FastJavaCompiler.Result result = FastJavaCompiler.compile(
                    build.mainJavaSources(), build, classesDirectory, staging.resolve("generated-sources"));
            if (result.success()) {
                CompileTiming timing = new CompileTiming(plan.mode(), result.millis());
                List<ApplicationBuild> applications = base.applications().stream()
                        .map(application -> application.withLocations(
                                List.of(classesDirectory), application.runtimeClasspath()))
                        .toList();
                BuildSnapshot snapshot = publish(staging, classesDirectory, build.runtimeClasspath(), timing,
                                                 applications);
                staging = null;
                return new CompileResult(true, snapshot,
                                         "build " + snapshot.buildNumber() + " ready (" + timing.summary() + ")");
            }
            output.accept("[compile] javac-fast failed after " + CompileTiming.format(result.millis())
                          + "; falling back to Maven compile");
            if (result.detail() != null && !result.detail().isBlank()) {
                output.accept("[compile] javac-fast diagnostics: " + result.detail().replace('\n', ' '));
            }
            CompileResult fallback = compileMaven(new CompilePlan("maven-compile", List.of("compile"), true,
                                                                   "javac-fast fallback", Set.of()), Set.of());
            if (fallback.success()) {
                return fallback;
            }
            return new CompileResult(false, null,
                                     "javac-fast failed and Maven fallback failed"
                                     + System.lineSeparator() + fallback.detail());
        } catch (Exception e) {
            return new CompileResult(false, null, e.getMessage());
        } finally {
            deleteRecursively(staging);
        }
    }

    private CompileResult compileMaven(CompilePlan plan, Set<Path> changedFiles) {
        try {
            return coordinator.withCompileLock(() -> compileMavenLocked(plan, changedFiles));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CompileResult(false, null, "compile interrupted");
        } catch (Exception e) {
            return new CompileResult(false, null, e.getMessage());
        }
    }

    private CompileResult compileMavenLocked(CompilePlan plan, Set<Path> changedFiles) throws InterruptedException {
        try {
            long started = System.nanoTime();
            MavenBuildIntrospection build = MavenBuildIntrospection.load(config);
            removeStaleClasses(changedFiles);
            var command = new java.util.ArrayList<>(MavenCommand.command(config.projectDirectory(),
                                                                         plan.goals().toArray(String[]::new)));
            if (plan.goals().contains("dependency:build-classpath")) {
                Files.createDirectories(build.runtimeClasspathFile().getParent());
                Files.deleteIfExists(build.runtimeClasspathFile());
                command.add("-Dmdep.outputFile=" + MavenReactor.CLASSPATH_FILE.toString().replace('\\', '/'));
            }
            output.accept("[compile] " + plan.mode() + " because " + plan.reason());
            ProcessUtils.ProcessResult result = ProcessUtils.run(
                    command,
                    config.projectDirectory(),
                    MavenCommand.environment(),
                    line -> output.accept("[compile] " + line));
            if (!result.success()) {
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                return new CompileResult(false, null,
                                         "failed after " + CompileTiming.format(elapsedMillis)
                                         + System.lineSeparator() + result.tail(20));
            }
            if (config.fastCompilerEnabled() && plan.goals().contains("dependency:build-classpath")) {
                MavenBuildIntrospection.load(config).refreshCompileClasspath(output);
            }
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            CompileTiming timing = new CompileTiming(plan.mode(), elapsedMillis);
            MavenBuildIntrospection completedBuild = MavenBuildIntrospection.load(config);
            List<ApplicationBuild> applications = MavenReactor.load(config.projectDirectory()).applications(config);
            if (applications.stream().anyMatch(ApplicationBuild::testApplication)
                && (plan.goals().contains("dependency:build-classpath")
                    || applications.stream().anyMatch(this::testClasspathMissing))) {
                ProcessUtils.ProcessResult testClasspath = generateTestClasspath();
                if (!testClasspath.success()) {
                    return new CompileResult(false, null, "failed to resolve test application classpath"
                                                          + System.lineSeparator() + testClasspath.tail(20));
                }
                applications = MavenReactor.load(config.projectDirectory()).applications(config);
            }
            for (ApplicationBuild application : applications) {
                Path moduleDirectory = ".".equals(application.module()) ? config.projectDirectory()
                        : config.projectDirectory().resolve(application.module());
                Path classpathFile = application.testApplication()
                        ? MavenReactor.TEST_CLASSPATH_FILE : MavenReactor.CLASSPATH_FILE;
                if (!Files.isRegularFile(moduleDirectory.resolve(classpathFile))) {
                    return new CompileResult(false, null, "Maven did not produce runtime classpath metadata for "
                                                           + application.module());
                }
            }
            StagingBuild stagingBuild = createStaging(applications);
            Path staging = stagingBuild.directory();
            BuildSnapshot snapshot;
            try {
                snapshot = publish(staging, stagingBuild.primaryClassesDirectory(),
                                   completedBuild.runtimeClasspath(), timing, stagingBuild.applications());
                staging = null;
            } finally {
                deleteRecursively(staging);
            }
            return new CompileResult(true, snapshot,
                                     "build " + snapshot.buildNumber() + " ready (" + timing.summary() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage();
            return new CompileResult(false, null,
                                     detail == null || detail.isBlank() ? e.getClass().getSimpleName() : detail);
        }
    }

    private boolean testClasspathMissing(ApplicationBuild application) {
        Path moduleDirectory = ".".equals(application.module()) ? config.projectDirectory()
                : config.projectDirectory().resolve(application.module());
        return !Files.isRegularFile(moduleDirectory.resolve(MavenReactor.TEST_CLASSPATH_FILE));
    }

    private ProcessUtils.ProcessResult generateTestClasspath() throws Exception {
        output.accept("[compile] resolving test application classpath");
        List<String> command = new java.util.ArrayList<>(MavenCommand.command(
                config.projectDirectory(), "dependency:build-classpath", "-DincludeScope=test"));
        command.add("-Dmdep.outputFile=" + MavenReactor.TEST_CLASSPATH_FILE.toString().replace('\\', '/'));
        return ProcessUtils.run(command, config.projectDirectory(), MavenCommand.environment(),
                                line -> output.accept("[compile] " + line));
    }

    private void removeStaleClasses(Set<Path> changedFiles) throws Exception {
        for (Path changedFile : changedFiles) {
            Path source = changedFile.isAbsolute()
                    ? changedFile.toAbsolutePath().normalize()
                    : config.projectDirectory().resolve(changedFile).toAbsolutePath().normalize();
            String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
            SourceOutput sourceOutput = sourceOutput(source);
            if (!fileName.endsWith(".java") || sourceOutput == null || Files.exists(source)) {
                continue;
            }
            Path relative = sourceOutput.sourceRoot().relativize(source);
            String simpleName = fileName.substring(0, fileName.length() - ".java".length());
            Path classFile = sourceOutput.classesDirectory().resolve(relative).resolveSibling(simpleName + ".class");
            Files.deleteIfExists(classFile);
            Path parent = classFile.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try (var stream = Files.newDirectoryStream(parent, simpleName + "$*.class")) {
                    for (Path nestedClass : stream) {
                        Files.deleteIfExists(nestedClass);
                    }
                }
            }
        }
    }

    private SourceOutput sourceOutput(Path source) {
        Path project = config.projectDirectory().toAbsolutePath().normalize();
        if (!source.startsWith(project)) {
            return null;
        }
        Path relative = project.relativize(source);
        for (int index = 0; index + 2 < relative.getNameCount(); index++) {
            if ("src".equals(relative.getName(index).toString())
                && ("main".equals(relative.getName(index + 1).toString())
                    || "test".equals(relative.getName(index + 1).toString()))
                && "java".equals(relative.getName(index + 2).toString())) {
                Path module = index == 0 ? project : project.resolve(relative.subpath(0, index));
                String sourceSet = relative.getName(index + 1).toString();
                return new SourceOutput(module.resolve("src").resolve(sourceSet).resolve("java"),
                                        module.resolve("target").resolve("test".equals(sourceSet)
                                                                                 ? "test-classes" : "classes"));
            }
        }
        return null;
    }

    private record SourceOutput(Path sourceRoot, Path classesDirectory) {
    }

    void activate(BuildSnapshot snapshot) {
        activate(snapshot, Set.of(snapshot.buildNumber()));
    }

    void activate(BuildSnapshot snapshot, Set<Long> retainedBuilds) {
        activeSnapshot = snapshot;
        Path buildsDirectory = buildsDirectory();
        if (!Files.isDirectory(buildsDirectory)) {
            return;
        }
        try (var children = Files.list(buildsDirectory)) {
            children.filter(path -> !path.equals(snapshot.buildDirectory()))
                    .filter(path -> !retainedBuilds.contains(buildNumber(path)))
                    .forEach(CompilePipeline::deleteRecursively);
        } catch (Exception e) {
            output.accept("[compile] failed to clean old build snapshots: " + e.getMessage());
        }
    }

    private static long buildNumber(Path path) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("build-(\\d+)-.*")
                .matcher(path.getFileName().toString());
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : -1;
    }

    void discard(BuildSnapshot snapshot) {
        if (snapshot != null && snapshot != activeSnapshot) {
            deleteRecursively(snapshot.buildDirectory());
        }
    }

    private Path createStaging(Path sourceClasses) throws Exception {
        Path buildsDirectory = buildsDirectory();
        Files.createDirectories(buildsDirectory);
        Path staging = Files.createTempDirectory(buildsDirectory, ".staging-");
        copyDirectory(sourceClasses, staging.resolve("classes"));
        return staging;
    }

    private StagingBuild createStaging(List<ApplicationBuild> applications) throws Exception {
        Path buildsDirectory = buildsDirectory();
        Files.createDirectories(buildsDirectory);
        Path staging = Files.createTempDirectory(buildsDirectory, ".staging-");
        Map<Path, Path> copiedDirectories = new java.util.LinkedHashMap<>();
        List<ApplicationBuild> stagedApplications = new java.util.ArrayList<>();
        for (ApplicationBuild application : applications) {
            List<Path> stagedClasses = new java.util.ArrayList<>();
            for (Path source : application.classesDirectories()) {
                Path normalized = source.toAbsolutePath().normalize();
                Path target = copiedDirectories.get(normalized);
                if (target == null) {
                    target = stagingClassesDirectory(staging, normalized);
                    copyDirectory(normalized, target);
                    copiedDirectories.put(normalized, target);
                }
                stagedClasses.add(target);
            }
            stagedApplications.add(application.withLocations(stagedClasses, application.runtimeClasspath()));
        }
        Path primary = stagedApplications.isEmpty() ? staging.resolve("classes")
                : stagedApplications.getFirst().classesDirectory();
        Files.createDirectories(primary);
        return new StagingBuild(staging, primary, List.copyOf(stagedApplications));
    }

    private Path stagingClassesDirectory(Path staging, Path sourceClasses) {
        Path project = config.projectDirectory().toAbsolutePath().normalize();
        if (sourceClasses.equals(project.resolve("target/classes"))) {
            return staging.resolve("classes");
        }
        if (sourceClasses.startsWith(project)) {
            Path relative = project.relativize(sourceClasses);
            int count = relative.getNameCount();
            String outputDirectory = count == 0 ? "" : relative.getName(count - 1).toString();
            if (count >= 2 && "target".equals(relative.getName(count - 2).toString())
                && ("classes".equals(outputDirectory) || "test-classes".equals(outputDirectory))) {
                Path module = count == 2 ? Path.of("") : relative.subpath(0, count - 2);
                return staging.resolve("modules").resolve(module).resolve(outputDirectory);
            }
        }
        return staging.resolve("dependencies").resolve(Integer.toHexString(sourceClasses.hashCode()));
    }

    private BuildSnapshot publish(Path staging, Path primaryClassesDirectory, List<Path> runtimeClasspath,
                                  CompileTiming timing, List<ApplicationBuild> applications) throws Exception {
        long buildNumber = buildSequence.incrementAndGet();
        Path target = buildsDirectory().resolve("build-" + buildNumber + "-" + java.util.UUID.randomUUID());
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(staging, target);
        }
        Path publishedPrimary = target.resolve(staging.relativize(primaryClassesDirectory));
        List<ApplicationBuild> publishedApplications = applications.stream().map(application ->
                application.withLocations(application.classesDirectories().stream()
                                                  .map(path -> target.resolve(staging.relativize(path))).toList(),
                                          application.runtimeClasspath())).toList();
        return new BuildSnapshot(buildNumber, target, publishedPrimary, runtimeClasspath,
                                 java.time.Instant.now(), timing, publishedApplications);
    }

    private Path buildsDirectory() {
        return config.projectDirectory().resolve(DevSessionStore.DEV_DIRECTORY).resolve("builds");
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        if (!Files.isDirectory(source)) {
            Files.createDirectories(target);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws java.io.IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, java.io.IOException failure)
                        throws java.io.IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // Snapshot cleanup is best effort and retried when another build becomes active.
        }
    }

    private record StagingBuild(Path directory, Path primaryClassesDirectory,
                                List<ApplicationBuild> applications) {
    }
}
