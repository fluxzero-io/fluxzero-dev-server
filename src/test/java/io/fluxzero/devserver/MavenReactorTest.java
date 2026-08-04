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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void excludesInstalledReactorArtifactsFromTestApplicationClasspath(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>core</module><module>app</module></modules>
                """));
        writeModule(project, "core", "");
        writeModule(project, "app", dependency("core"));
        compileMain(project.resolve("core"), "com.acme.CurrentHandler");
        compileMain(project.resolve("app"), "com.acme.AppSupport");
        compileTestMain(project.resolve("app"), "com.acme.Rebound");
        Path staleReactorArtifact = project.resolve("repository/com/acme/core/1/core-1.jar");
        Path externalTestDependency = project.resolve("repository/com/external/testing/2/testing-2.jar");
        Files.createDirectories(staleReactorArtifact.getParent());
        Files.createDirectories(externalTestDependency.getParent());
        Files.writeString(staleReactorArtifact, "stale reactor output");
        Files.writeString(externalTestDependency, "external test dependency");
        writeTestClasspath(project.resolve("app"), staleReactorArtifact, externalTestDependency);

        ApplicationBuild rebound = MavenReactor.load(project)
                .applications(config(project, List.of("rebound"))).getFirst();

        assertEquals(List.of(project.resolve("app/target/test-classes"), project.resolve("app/target/classes"),
                             project.resolve("core/target/classes")), rebound.classesDirectories());
        assertEquals(List.of(externalTestDependency), rebound.runtimeClasspath());
    }

    @Test
    void keepsExternalSnapshotWhenReactorModuleHasSameArtifactId(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("com.flowmaps", "root", "pom", """
                <modules><module>common</module><module>app</module></modules>
        """));
        writeModule(project, "common", "com.flowmaps", "");
        writeModule(project, "app", "com.flowmaps", """
                <dependencies>
                  <dependency>
                    <groupId>com.flowmaps</groupId><artifactId>common</artifactId><version>1</version>
                  </dependency>
                  <dependency>
                    <groupId>io.fluxzero</groupId><artifactId>common</artifactId><version>0-SNAPSHOT</version>
                  </dependency>
                </dependencies>
                """);
        compileMain(project.resolve("common"), "com.flowmaps.common.ReactorCommon");
        compileMain(project.resolve("app"), "com.flowmaps.AppMain");
        Path installedReactorArtifact = project.resolve(
                "repository/com/flowmaps/common/1/common-1.jar");
        Path externalSnapshot = project.resolve(
                "repository/io/fluxzero/common/0-SNAPSHOT/common-0-SNAPSHOT.jar");
        writeJarWithClass(installedReactorArtifact, "com.flowmaps.common.StaleCommon");
        writeJarWithClass(externalSnapshot, "io.fluxzero.common.ExternalCommon");
        writeClasspath(project.resolve("app"), installedReactorArtifact, externalSnapshot);

        ApplicationBuild application = MavenReactor.load(project)
                .applications(config(project, List.of("app"))).getFirst();

        assertEquals(List.of(project.resolve("app/target/classes"), project.resolve("common/target/classes")),
                     application.classesDirectories());
        assertEquals(List.of(externalSnapshot), application.runtimeClasspath());
        try (URLClassLoader classLoader = classLoader(application)) {
            assertNotNull(Class.forName("com.flowmaps.common.ReactorCommon", true, classLoader));
            assertNotNull(Class.forName("io.fluxzero.common.ExternalCommon", true, classLoader));
        }
    }

    @Test
    void keepsExternalDependencyWhenReactorModuleHasDifferentVersion(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>common</module><module>app</module></modules>
                """));
        writeModule(project, "common", "");
        writeModule(project, "app", """
                <dependencies><dependency>
                  <groupId>com.acme</groupId><artifactId>common</artifactId><version>2</version>
                </dependency></dependencies>
                """);
        compileMain(project.resolve("common"), "com.acme.common.ReactorVersionOne");
        compileMain(project.resolve("app"), "com.acme.AppMain");
        Path externalVersionTwo = project.resolve("repository/com/acme/common/2/common-2.jar");
        writeJarWithClass(externalVersionTwo, "com.acme.common.ExternalVersionTwo");
        writeClasspath(project.resolve("app"), externalVersionTwo);

        ApplicationBuild application = MavenReactor.load(project)
                .applications(config(project, List.of("app"))).getFirst();

        assertEquals(List.of(project.resolve("app/target/classes")), application.classesDirectories());
        assertEquals(List.of(externalVersionTwo), application.runtimeClasspath());
        try (URLClassLoader classLoader = classLoader(application)) {
            assertNotNull(Class.forName("com.acme.common.ExternalVersionTwo", true, classLoader));
            assertThrows(ClassNotFoundException.class,
                         () -> Class.forName("com.acme.common.ReactorVersionOne", true, classLoader));
        }
    }

    @Test
    void resolvesProjectVersionForReactorDependencies(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>common</module><module>app</module></modules>
                """));
        writeModule(project, "common", "");
        writeModule(project, "app", """
                <dependencies><dependency>
                  <groupId>com.acme</groupId><artifactId>common</artifactId>
                  <version>${project.version}</version>
                </dependency></dependencies>
                """);
        compileMain(project.resolve("common"), "com.acme.CommonMain");
        compileMain(project.resolve("app"), "com.acme.AppMain");

        ApplicationBuild application = MavenReactor.load(project)
                .applications(config(project, List.of("app"))).getFirst();

        assertEquals(List.of(project.resolve("app/target/classes"), project.resolve("common/target/classes")),
                     application.classesDirectories());
    }

    @Test
    void preservesClassifiedDependencyInsteadOfReplacingItWithMainClasses(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("root", "pom", """
                <modules><module>common</module><module>app</module></modules>
                """));
        writeModule(project, "common", "");
        writeModule(project, "app", dependency("com.acme", "common", "test-jar", "tests"));
        compileMain(project.resolve("common"), "com.acme.CommonMain");
        compileMain(project.resolve("app"), "com.acme.AppMain");
        Path testJar = project.resolve("repository/com/acme/common/1/common-1-tests.jar");
        writeJarWithClass(testJar, "com.acme.CommonTestSupport");
        writeClasspath(project.resolve("app"), testJar);

        ApplicationBuild application = MavenReactor.load(project)
                .applications(config(project, List.of("app"))).getFirst();

        assertEquals(List.of(project.resolve("app/target/classes")), application.classesDirectories());
        assertEquals(List.of(testJar), application.runtimeClasspath());
    }

    @Test
    void resolvesInheritedProjectGroupForReactorDependencies(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("com.acme", "root", "pom", """
                <modules><module>common</module><module>app</module></modules>
                """));
        writeInheritedModule(project, "common", "");
        writeInheritedModule(project, "app", """
                <dependencies><dependency>
                  <groupId>${project.groupId}</groupId><artifactId>common</artifactId><version>1</version>
                </dependency></dependencies>
                """);
        compileMain(project.resolve("common"), "com.acme.CommonMain");
        compileMain(project.resolve("app"), "com.acme.AppMain");

        ApplicationBuild application = MavenReactor.load(project)
                .applications(config(project, List.of("app"))).getFirst();

        assertEquals(List.of(project.resolve("app/target/classes"), project.resolve("common/target/classes")),
                     application.classesDirectories());
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
        writeModule(project, module, "com.acme", body);
    }

    private static void writeModule(Path project, String module, String groupId, String body) throws Exception {
        Path directory = Files.createDirectories(project.resolve(module));
        Files.writeString(directory.resolve("pom.xml"), pom(groupId, module, "jar", body));
    }

    private static void writeInheritedModule(Path project, String module, String body) throws Exception {
        Path directory = Files.createDirectories(project.resolve(module));
        Files.writeString(directory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId><artifactId>root</artifactId><version>1</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  %s
                </project>
                """.formatted(module, body));
    }

    private static String pom(String artifactId, String packaging, String body) {
        return pom("com.acme", artifactId, packaging, body);
    }

    private static String pom(String groupId, String artifactId, String packaging, String body) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>1</version>
                  <packaging>%s</packaging>
                  %s
                </project>
                """.formatted(groupId, artifactId, packaging, body);
    }

    private static String dependency(String artifactId) {
        return dependency("com.acme", artifactId, null, null);
    }

    private static String dependency(String groupId, String artifactId, String type, String classifier) {
        return """
                <dependencies><dependency>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>1</version>
                  %s
                  %s
                </dependency></dependencies>
                """.formatted(groupId, artifactId,
                               type == null ? "" : "<type>" + type + "</type>",
                               classifier == null ? "" : "<classifier>" + classifier + "</classifier>");
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

    private static void writeClasspath(Path module, Path... dependencies) throws Exception {
        Files.createDirectories(module.resolve(MavenReactor.CLASSPATH_FILE).getParent());
        Files.writeString(module.resolve(MavenReactor.CLASSPATH_FILE),
                          String.join(System.getProperty("path.separator"),
                                      java.util.Arrays.stream(dependencies).map(Path::toString).toList()));
    }

    private static void writeTestClasspath(Path module, Path... dependencies) throws Exception {
        Files.createDirectories(module.resolve(MavenReactor.TEST_CLASSPATH_FILE).getParent());
        Files.writeString(module.resolve(MavenReactor.TEST_CLASSPATH_FILE),
                          String.join(System.getProperty("path.separator"),
                                      java.util.Arrays.stream(dependencies).map(Path::toString).toList()));
    }

    private static void writeJarWithClass(Path jar, String className) throws Exception {
        Path workDirectory = jar.getParent().resolve("build-" + className.replace('.', '-'));
        Path source = workDirectory.resolve("src").resolve(className.replace('.', '/') + ".java");
        Path classes = workDirectory.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, "package " + className.substring(0, className.lastIndexOf('.'))
                                  + "; public class " + className.substring(className.lastIndexOf('.') + 1) + " {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static URLClassLoader classLoader(ApplicationBuild application) throws Exception {
        List<URL> entries = new ArrayList<>();
        for (Path path : application.classesDirectories()) {
            entries.add(path.toUri().toURL());
        }
        for (Path path : application.runtimeClasspath()) {
            entries.add(path.toUri().toURL());
        }
        return new URLClassLoader(entries.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
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
