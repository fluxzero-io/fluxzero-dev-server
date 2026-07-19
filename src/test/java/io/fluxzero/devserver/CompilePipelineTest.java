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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class CompilePipelineTest {

    @Test
    void usesFullMavenPathForPomChanges(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);

        CompileResult result = new CompilePipeline(config(projectDirectory), ignored -> {
        }).compile(Set.of(projectDirectory.resolve("pom.xml")));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        String log = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(log.contains("test-compile dependency:build-classpath"), log);
        assertFalse(log.contains("compile test-compile"), log);
        assertTrue(log.contains("-DincludeScope=runtime"), log);
        assertTrue(log.contains("-Dmdep.outputFile="), log);
        assertTrue(result.snapshot().classesDirectory().startsWith(
                projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("builds")));
    }

    @Test
    void usesFastCompileWhenRuntimeDependenciesAreAlreadyAvailable(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        writeRuntimeClasspath(projectDirectory);

        CompileResult result = new CompilePipeline(config(projectDirectory), ignored -> {
        }).compile(Set.of(projectDirectory.resolve("src/main/java/com/acme/OrderHandler.java")));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        String log = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(log.contains("compile"), log);
        assertFalse(log.contains("test-compile"), log);
        assertFalse(log.contains("dependency:build-classpath"), log);
    }

    @Test
    void usesJavacFastWhenEnabledAndMetadataIsAvailable(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        CompilePipeline pipeline = fastPipeline(projectDirectory, ignored -> {
        });
        Path source = writeSource(projectDirectory, "OrderHandler", """
                package com.acme;

                public class OrderHandler {
                    public String version() {
                        return "v1";
                    }
                }
                """);

        CompileResult result = pipeline.compile(Set.of(source));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        assertTrue(result.detail().contains("javac-fast"), result.detail());
        assertTrue(Files.isRegularFile(result.snapshot().classesDirectory().resolve("com/acme/OrderHandler.class")));
        assertFalse(Files.exists(projectDirectory.resolve("compile-runs.log")));
    }

    @Test
    void directoryNoiseDoesNotPreventJavacFastForNewJavaSource(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        CompilePipeline pipeline = fastPipeline(projectDirectory, ignored -> {
        });
        Path source = writeSource(projectDirectory, "LiveHandler", """
                package com.acme;

                public class LiveHandler {
                    public String version() {
                        return "live";
                    }
                }
                """);

        CompileResult result = pipeline.compile(Set.of(source.getParent(), source));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        assertTrue(result.detail().contains("javac-fast"), result.detail());
        assertFalse(Files.exists(projectDirectory.resolve("compile-runs.log")));
    }

    @Test
    void fallsBackToMavenWhenJavacFastFails(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        CompilePipeline pipeline = fastPipeline(projectDirectory, ignored -> {
        });
        Path source = writeSource(projectDirectory, "Broken", """
                package com.acme;

                public class Broken {
                    public String version() {
                        return "broken"
                    }
                }
                """);

        CompileResult result = pipeline.compile(Set.of(source));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        assertTrue(result.detail().contains("maven-compile"), result.detail());
        String log = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(log.contains("compile"), log);
        assertFalse(log.contains("test-compile"), log);
    }

    @Test
    void refreshesCompileClasspathMetadataAfterFullMavenWhenFastCompilerIsEnabled(@TempDir Path projectDirectory)
            throws Exception {
        installFakeMaven(projectDirectory);

        CompileResult result = new CompilePipeline(config(projectDirectory, true), ignored -> {
        }).compile(Set.of(projectDirectory.resolve("pom.xml")));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        assertTrue(Files.isRegularFile(projectDirectory.resolve("target/fluxzero-dev/compile-classpath.txt")));
        String log = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(log.contains("test-compile dependency:build-classpath"), log);
        assertFalse(log.contains("compile test-compile"), log);
        assertTrue(log.contains("dependency:build-classpath"), log);
    }

    @Test
    void refreshesRuntimeDependenciesWhenMissingForAppChanges(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);

        CompileResult result = new CompilePipeline(config(projectDirectory), ignored -> {
        }).compile(Set.of(projectDirectory.resolve("src/main/java/com/acme/OrderHandler.java")));

        assertTrue(result.success(), result.detail());
        assertNotNull(result.snapshot());
        String log = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(log.contains("test-compile dependency:build-classpath"), log);
        assertFalse(log.contains("compile test-compile"), log);
    }

    @Test
    void skipsAppCompileForTestOnlyChanges(@TempDir Path projectDirectory) {
        CompileResult result = new CompilePipeline(config(projectDirectory), ignored -> {
        }).compile(Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")));

        assertTrue(result.success(), result.detail());
        assertNull(result.snapshot());
        assertTrue(result.detail().contains("skipped"), result.detail());
    }

    @Test
    void recompilesTestSourcesWhenRunningAnExplicitTestApplication(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        writeRuntimeClasspath(projectDirectory);
        Path buildDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("builds/baseline");
        Path testClasses = Files.createDirectories(buildDirectory.resolve("test-classes"));
        CompilePipeline pipeline = new CompilePipeline(config(projectDirectory, true), ignored -> {
        });
        pipeline.activate(new BuildSnapshot(
                0, buildDirectory, testClasses, List.of(), Instant.now(), CompileTiming.unknown(),
                List.of(new ApplicationBuild("Rebound", ".", "com.acme.Rebound", List.of(testClasses), List.of(),
                                             true))));

        CompileResult result = pipeline.compile(
                Set.of(projectDirectory.resolve("src/test/java/com/acme/Rebound.java")));

        assertTrue(result.success(), result.detail());
        String log = Files.readString(projectDirectory.resolve("compile-runs.log"));
        assertTrue(log.contains("test-compile"), log);
        assertFalse(log.contains("dependency:build-classpath"), log);
        assertFalse(result.detail().contains("javac-fast"), result.detail());
    }

    @Test
    void removesStaleClassFilesForDeletedMainJavaSources(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        writeRuntimeClasspath(projectDirectory);
        Path source = writeSource(projectDirectory, "DeletedHandler", """
                package com.acme;

                public class DeletedHandler {
                    static class Nested {
                    }
                }
                """);
        Path classes = projectDirectory.resolve("target/classes/com/acme");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("DeletedHandler.class"), "stale");
        Files.writeString(classes.resolve("DeletedHandler$Nested.class"), "stale");
        Files.writeString(classes.resolve("OtherHandler.class"), "keep");
        Files.delete(source);

        CompileResult result = new CompilePipeline(config(projectDirectory), ignored -> {
        }).compile(Set.of(source));

        assertTrue(result.success(), result.detail());
        assertFalse(Files.exists(classes.resolve("DeletedHandler.class")));
        assertFalse(Files.exists(classes.resolve("DeletedHandler$Nested.class")));
        assertTrue(Files.exists(classes.resolve("OtherHandler.class")));
    }

    @Test
    void publishedBuildClassesRemainImmutableWhenMavenOutputChanges(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Path mutableClass = projectDirectory.resolve("target/classes/com/acme/version.txt");
        Files.createDirectories(mutableClass.getParent());
        Files.writeString(mutableClass, "v1");

        CompileResult result = new CompilePipeline(config(projectDirectory), ignored -> {
        }).compile(Set.of(projectDirectory.resolve("pom.xml")));

        assertTrue(result.success(), result.detail());
        Path snapshotClass = result.snapshot().classesDirectory().resolve("com/acme/version.txt");
        assertTrue(Files.isRegularFile(snapshotClass));
        Files.writeString(mutableClass, "v2");
        assertTrue("v1".equals(Files.readString(snapshotClass)));
    }

    private static DevServerConfig config(Path projectDirectory) {
        return config(projectDirectory, false);
    }

    private static DevServerConfig config(Path projectDirectory, boolean fastCompilerEnabled) {
        return new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null, fastCompilerEnabled);
    }

    private static Path writeSource(Path projectDirectory, String typeName, String source) throws Exception {
        Path file = projectDirectory.resolve("src/main/java/com/acme/" + typeName + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private static CompilePipeline fastPipeline(Path projectDirectory, Consumer<String> output) throws Exception {
        Path metadataDirectory = projectDirectory.resolve("target/fluxzero-dev");
        Files.createDirectories(metadataDirectory);
        Files.writeString(metadataDirectory.resolve("runtime-classpath.txt"), "");
        Files.writeString(metadataDirectory.resolve("compile-classpath.txt"), "");
        Path buildDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve("builds/baseline");
        Path classesDirectory = buildDirectory.resolve("classes");
        Files.createDirectories(classesDirectory);
        CompilePipeline pipeline = new CompilePipeline(config(projectDirectory, true), output);
        pipeline.activate(new BuildSnapshot(0, buildDirectory, classesDirectory, List.of(), Instant.now()));
        return pipeline;
    }

    private static void writeRuntimeClasspath(Path projectDirectory) throws Exception {
        Path metadataDirectory = projectDirectory.resolve("target/fluxzero-dev");
        Files.createDirectories(metadataDirectory);
        Files.writeString(metadataDirectory.resolve("runtime-classpath.txt"), "");
    }

    private static void installFakeMaven(Path projectDirectory) throws Exception {
        Path mvnw = projectDirectory.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                set -eu
                echo "$*" >> "$PWD/compile-runs.log"
                mkdir -p "$PWD/target/classes" "$PWD/target/fluxzero-dev"
                cp_file=""
                for arg in "$@"; do
                  case "$arg" in
                    -Dmdep.outputFile=*) cp_file="${arg#-Dmdep.outputFile=}" ;;
                  esac
                done
                if [ -n "$cp_file" ]; then
                  mkdir -p "$(dirname "$cp_file")"
                  : > "$cp_file"
                fi
                exit 0
                """);
        assertTrue(mvnw.toFile().setExecutable(true));
    }
}
