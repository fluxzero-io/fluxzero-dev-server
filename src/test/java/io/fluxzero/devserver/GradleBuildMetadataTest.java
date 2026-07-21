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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleBuildMetadataTest {

    @Test
    void discoversAndConfiguresGradleApplication(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }");
        Path classes = compile(project, "com.example.App", """
                package com.example;
                public class App { public static void main(String[] args) { } }
                """);
        Path dependency = Files.createFile(project.resolve("dependency.jar"));
        writeMetadata(project, classes, dependency);
        DevApplicationConfig configured = new DevApplicationConfig(
                "app", "configured-name", Map.of("MODE", "local"), Map.of("KEY", "op://vault/item/field"));
        DevServerConfig defaults = DevServerConfig.defaults(project);
        DevServerConfig config = new DevServerConfig(
                defaults.projectDirectory(), defaults.mainClass(), defaults.applicationName(), defaults.namespace(),
                defaults.watch(), defaults.compileOnStart(), defaults.testsEnabled(), defaults.startupTimeout(),
                defaults.gracefulShutdownTimeout(), defaults.debounce(), defaults.frontend(), defaults.appArgs(),
                defaults.fastCompilerEnabled(), defaults.environment(), List.of("encrypted"), defaults.gatewayPort(),
                defaults.idpMode(), Map.of("encrypted", configured));

        ApplicationBuild application = GradleBuildMetadata.load(project).applications(config).getFirst();

        assertEquals("com.example.App", application.mainClass());
        assertEquals("configured-name", application.applicationName());
        assertEquals("encrypted", application.launchId());
        assertEquals("local", application.environment().get("MODE"));
        assertEquals("op://vault/item/field", application.secretReferences().get("KEY"));
        assertTrue(application.runtimeClasspath().contains(dependency));
    }

    @Test
    void createsGradleCompilePlanForBuildAndSourceChanges(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }");

        CompilePlan initial = CompilePlan.fromGradle(
                project, false, java.util.Set.of(project.resolve("build.gradle.kts")));
        CompilePlan source = CompilePlan.fromGradle(
                project, false, java.util.Set.of(project.resolve("src/main/java/com/example/App.java")));

        assertEquals("gradle-compile", initial.mode());
        assertEquals("Gradle build changed", initial.reason());
        assertEquals(List.of("fluxzeroDevMetadata"), source.goals());
    }

    @Test
    void requiresExplicitSelectionWhenGradleModuleHasMultipleMainClasses(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }");
        Path classes = compile(project, "com.example.App", """
                package com.example;
                public class App { public static void main(String[] args) { } }
                """);
        compile(project, "com.example.ImportData", """
                package com.example;
                public class ImportData { public static void main(String[] args) { } }
                """);
        Path dependency = Files.createFile(project.resolve("dependency.jar"));
        writeMetadata(project, classes, dependency);
        GradleBuildMetadata metadata = GradleBuildMetadata.load(project);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> metadata.applications(DevServerConfig.defaults(project)));
        assertTrue(exception.getMessage().contains("Multiple main classes found in unconfigured Gradle modules"));

        DevServerConfig defaults = DevServerConfig.defaults(project);
        DevServerConfig selectedConfig = new DevServerConfig(
                defaults.projectDirectory(), defaults.mainClass(), defaults.applicationName(), defaults.namespace(),
                defaults.watch(), defaults.compileOnStart(), defaults.testsEnabled(), defaults.startupTimeout(),
                defaults.gracefulShutdownTimeout(), defaults.debounce(), defaults.frontend(), defaults.appArgs(),
                defaults.fastCompilerEnabled(), defaults.environment(), List.of("ImportData"),
                defaults.gatewayPort(), defaults.idpMode());
        assertEquals("com.example.ImportData", metadata.applications(selectedConfig).getFirst().mainClass());
    }

    private static void writeMetadata(Path project, Path classes, Path dependency) throws Exception {
        Path file = project.resolve(GradleBuildMetadata.FILE);
        Files.createDirectories(file.getParent());
        new ObjectMapper().writeValue(file.toFile(), Map.of("modules", List.of(Map.of(
                "path", ".",
                "name", "app",
                "mainClasses", List.of(classes.toString()),
                "testClasses", List.of(),
                "runtimeDirectories", List.of(classes.toString()),
                "testRuntimeDirectories", List.of(classes.toString()),
                "runtimeClasspath", List.of(dependency.toString()),
                "testRuntimeClasspath", List.of(dependency.toString())))));
    }

    private static Path compile(Path project, String className, String source) throws Exception {
        Path sourceFile = project.resolve("source").resolve(className.replace('.', '/') + ".java");
        Path classes = project.resolve("build/classes/java/main");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), sourceFile.toString()));
        return classes;
    }
}
