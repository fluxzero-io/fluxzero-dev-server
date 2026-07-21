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
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenReactorTest {

    @Test
    void discoversIndependentAppsWithOnlyTheirReactorDependencyClasses(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>core</module><module>app</module><module>audit</module></modules>
                """));
        writeModule(project, "core", "");
        writeModule(project, "app", dependency("core"));
        writeModule(project, "audit", dependency("core"));
        compileMain(project.resolve("app"), "com.acme.AppMain");
        compileMain(project.resolve("audit"), "com.acme.AuditMain");
        Files.createDirectories(project.resolve("core/target/classes"));
        writeClasspath(project.resolve("app"), project.resolve("app-dependency.jar"));
        writeClasspath(project.resolve("audit"), project.resolve("audit-dependency.jar"));

        List<ApplicationBuild> applications = MavenReactor.load(project).applications(config(project));

        assertEquals(List.of("app", "audit"), applications.stream().map(ApplicationBuild::applicationName).toList());
        ApplicationBuild app = applications.getFirst();
        assertEquals("com.acme.AppMain", app.mainClass());
        assertEquals(List.of(project.resolve("app/target/classes"), project.resolve("core/target/classes")),
                     app.classesDirectories());
        assertFalse(app.classesDirectories().contains(project.resolve("audit/target/classes")));
        assertEquals(List.of(project.resolve("app-dependency.jar")), app.runtimeClasspath());
    }

    @Test
    void selectsOneOrMoreReactorApplicationsByArtifactId(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>app</module><module>audit</module></modules>
                """));
        writeModule(project, "app", "");
        writeModule(project, "audit", "");
        compileMain(project.resolve("app"), "com.acme.AppMain");
        compileMain(project.resolve("audit"), "com.acme.AuditMain");
        MavenReactor reactor = MavenReactor.load(project);

        List<ApplicationBuild> appOnly = reactor.applications(config(project, List.of("app")));
        assertEquals(List.of("app"), appOnly.stream().map(ApplicationBuild::applicationName).toList());
        assertEquals("com.acme.AppMain", appOnly.getFirst().mainClass());

        List<ApplicationBuild> both = reactor.applications(config(project, List.of("audit", "app")));
        assertEquals(List.of("app", "audit"), both.stream().map(ApplicationBuild::applicationName).toList());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> reactor.applications(config(project, List.of("missing"))));
        assertTrue(exception.getMessage().contains("Available applications: app, audit"));
    }

    @Test
    void skipsAmbiguousUtilityModuleUnlessAMainClassIsExplicitlySelected(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>app</module><module>utilities</module></modules>
                """));
        writeModule(project, "app", configuredMainClass("com.acme.AppMain"));
        writeModule(project, "utilities", "");
        compileMain(project.resolve("app"), "com.acme.AppMain");
        compileMain(project.resolve("app"), "com.acme.AppTool");
        compileMain(project.resolve("utilities"), "com.acme.ImportData");
        compileMain(project.resolve("utilities"), "com.acme.ConvertIndex");
        MavenReactor reactor = MavenReactor.load(project);

        List<ApplicationBuild> automatic = reactor.applications(config(project));
        assertEquals(List.of("com.acme.AppMain"), automatic.stream().map(ApplicationBuild::mainClass).toList());

        List<ApplicationBuild> selected = reactor.applications(config(project, List.of("ImportData")));
        assertEquals(List.of("com.acme.ImportData"), selected.stream().map(ApplicationBuild::mainClass).toList());
    }

    @Test
    void explainsAmbiguousMainClassesWhenNoDefaultApplicationExists(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("tools", "jar", ""));
        compileMain(project, "com.acme.ImportData");
        compileMain(project, "com.acme.ConvertIndex");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> MavenReactor.load(project).applications(config(project)));

        assertTrue(exception.getMessage().contains("Multiple main classes found in unconfigured Maven modules"));
        assertTrue(exception.getMessage().contains("--app <main-class>"));
    }

    @Test
    void discoversTestApplicationOnlyWhenItIsExplicitlySelected(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>app</module></modules>
                """));
        writeModule(project, "app", "");
        compileMain(project.resolve("app"), "com.acme.AppMain");
        compileTestMain(project.resolve("app"), "com.acme.Rebound");
        Path runtimeDependency = project.resolve("runtime-dependency.jar");
        Path testDependency = project.resolve("test-dependency.jar");
        writeClasspath(project.resolve("app"), runtimeDependency);
        writeTestClasspath(project.resolve("app"), testDependency);
        MavenReactor reactor = MavenReactor.load(project);

        List<ApplicationBuild> automatic = reactor.applications(config(project));
        assertEquals(List.of("com.acme.AppMain"), automatic.stream().map(ApplicationBuild::mainClass).toList());

        ApplicationBuild rebound = reactor.applications(config(project, List.of("rebound"))).getFirst();
        assertEquals("Rebound", rebound.applicationName());
        assertEquals("com.acme.Rebound", rebound.mainClass());
        assertTrue(rebound.testApplication());
        assertEquals(List.of(project.resolve("app/target/test-classes"), project.resolve("app/target/classes")),
                     rebound.classesDirectories());
        assertEquals(List.of(testDependency), rebound.runtimeClasspath());

        assertEquals("com.acme.Rebound",
                     reactor.applications(config(project, List.of("app:rebound"))).getFirst().mainClass());
        assertEquals("com.acme.AppMain",
                     reactor.applications(config(project, List.of("app"))).getFirst().mainClass());
    }

    @Test
    void launchesMultipleNamedFlavorsOfTheSameTestApplication(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>app</module></modules>
                """));
        writeModule(project, "app", "");
        compileTestMain(project.resolve("app"), "com.acme.Rebound");
        MavenReactor reactor = MavenReactor.load(project);
        Map<String, DevApplicationConfig> configurations = Map.of(
                "rebound-plain", new DevApplicationConfig("rebound", null,
                                                          Map.of("MODE", "plain"), Map.of()),
                "rebound-encrypted", new DevApplicationConfig(
                        "rebound", null, Map.of("MODE", "encrypted"),
                        Map.of("ENCRYPTION_KEY", "op://Shared/rebound/key")));
        DevServerConfig config = new DevServerConfig(
                project, null, "root", null, false, false, false,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of(), false,
                "local", List.of("rebound-plain", "rebound-encrypted"), 0, IdpMode.EXTERNAL, configurations);

        List<ApplicationBuild> applications = reactor.applications(config);

        assertEquals(List.of("rebound-plain", "rebound-encrypted"),
                     applications.stream().map(ApplicationBuild::launchId).toList());
        assertEquals(List.of("Rebound", "Rebound"),
                     applications.stream().map(ApplicationBuild::applicationName).toList());
        assertEquals("plain", applications.getFirst().environment().get("MODE"));
        assertEquals(java.util.Set.of("ENCRYPTION_KEY"), applications.getLast().secretReferences().keySet());
        assertFalse(applications.toString().contains("op://"));
    }

    private static void writeModule(Path project, String module, String body) throws Exception {
        Path directory = Files.createDirectories(project.resolve(module));
        Files.writeString(directory.resolve("pom.xml"), pom(module, "jar", body));
    }

    private static String pom(String artifactId, String packaging, String body) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>%s</artifactId>
                  <version>1</version>
                  <packaging>%s</packaging>
                  %s
                </project>
                """.formatted(artifactId, packaging, body);
    }

    private static String dependency(String artifactId) {
        return """
                <dependencies><dependency>
                  <groupId>com.acme</groupId><artifactId>%s</artifactId><version>1</version>
                </dependency></dependencies>
                """.formatted(artifactId);
    }

    private static String configuredMainClass(String mainClass) {
        return """
                <build><plugins><plugin><configuration>
                  <mainClass>%s</mainClass>
                </configuration></plugin></plugins></build>
                """.formatted(mainClass);
    }

    private static void compileMain(Path module, String className) throws Exception {
        Path source = module.resolve("src/main/java").resolve(className.replace('.', '/') + ".java");
        Path classes = module.resolve("target/classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, "package " + className.substring(0, className.lastIndexOf('.'))
                                  + "; public class " + className.substring(className.lastIndexOf('.') + 1)
                                  + " { public static void main(String[] args) {} }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));
    }

    private static void compileTestMain(Path module, String className) throws Exception {
        Path source = module.resolve("src/test/java").resolve(className.replace('.', '/') + ".java");
        Path classes = module.resolve("target/test-classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, "package " + className.substring(0, className.lastIndexOf('.'))
                                  + "; public class " + className.substring(className.lastIndexOf('.') + 1)
                                  + " { static void main(String[] args) {} }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));
    }

    private static void writeClasspath(Path module, Path dependency) throws Exception {
        Files.createDirectories(module.resolve(MavenReactor.CLASSPATH_FILE).getParent());
        Files.writeString(module.resolve(MavenReactor.CLASSPATH_FILE), dependency.toString());
    }

    private static void writeTestClasspath(Path module, Path dependency) throws Exception {
        Files.createDirectories(module.resolve(MavenReactor.TEST_CLASSPATH_FILE).getParent());
        Files.writeString(module.resolve(MavenReactor.TEST_CLASSPATH_FILE), dependency.toString());
    }

    private static DevServerConfig config(Path project) {
        return config(project, List.of());
    }

    private static DevServerConfig config(Path project, List<String> applications) {
        return new DevServerConfig(project, null, "root", null, false, false, false,
                                   DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                                   DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                                   DevServerConfig.DEFAULT_DEBOUNCE, FrontendConfig.none(), List.of(), false,
                                   "local", applications, 0, IdpMode.MANAGED);
    }
}
