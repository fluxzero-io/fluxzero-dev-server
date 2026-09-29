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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectedMavenSdkVersionsE2EIT {
    @Test
    void usesSelectedEffectiveDependenciesWithInheritanceBomMediationAndProfiles(@TempDir Path root) throws Exception {
        fixture(root);
        Files.createDirectories(root.resolve(".fluxzero"));
        Files.writeString(root.resolve(".fluxzero/dev.yaml"), """
                version: 1
                profiles:
                  selected:
                    apps: [modern]
                    applicationConfig:
                      modern:
                        application: selected
                  incompatible:
                    apps: [selected, legacy]
                """);
        var selected = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString(), "--profile", "selected"});
        assertEquals("2.0.0-issue110", FluxzeroSdkVersionDetector.detect(selected).version());
        var incompatible = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString(), "--profile", "incompatible"});
        var failure = assertThrows(DevServerStartupException.class, () -> FluxzeroSdkVersionDetector.detect(incompatible));
        assertTrue(failure.getMessage().contains("selected"));
        assertTrue(failure.getMessage().contains("legacy"));
        assertTrue(failure.getMessage().contains("io.fluxzero:sdk:1.0.0-issue110"));
        String previous = System.getProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY);
        try {
            System.setProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY, "2.0.0-override");
            assertEquals("2.0.0-override", FluxzeroSdkVersionDetector.detect(incompatible).version());
        } finally {
            if (previous == null) System.clearProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY);
            else System.setProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY, previous);
        }
        Files.delete(root.resolve(".fluxzero/dev.yaml"));
        var explicit = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString(), "--app", "selected"});
        assertEquals("2.0.0-issue110", FluxzeroSdkVersionDetector.detect(explicit).version());
        // A selected application with no direct SDK still inherits its runtime dependency's managed SDK.
        Files.writeString(root.resolve("selected/pom.xml"), module("selected", """
                <properties><fluxzero.version>2.0.0-issue110</fluxzero.version></properties>
                <dependencies><dependency><groupId>example</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>
                """));
        assertEquals("2.0.0-issue110", FluxzeroSdkVersionDetector.detect(explicit).version());
        try (var files = Files.walk(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("sdk-selection-")));
        }
    }

    @Test
    void respectsSelectionsAcrossBuildRoots(@TempDir Path root) throws Exception {
        fixture(root.resolve("one")); fixture(root.resolve("two"));
        Files.createDirectories(root.resolve(".fluxzero"));
        Files.writeString(root.resolve(".fluxzero/dev.yaml"), """
                version: 1
                projects:
                  one:
                    directory: one
                    apps: [selected]
                  two:
                    directory: two
                    apps: [selected]
                """);
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", root.toString()});
        assertEquals("2.0.0-issue110", FluxzeroSdkVersionDetector.detect(config).version());
    }

    static void fixture(Path root) throws Exception {
        Files.createDirectories(root);
        Path repository = root.resolve("repository");
        for (String version : List.of("1.0.0-issue110", "2.0.0-issue110")) {
            artifact(repository, "sdk", version, "");
            artifact(repository, "fluxzero-bom", version, "<packaging>pom</packaging><dependencyManagement><dependencies>"
                    + sdk(version) + "</dependencies></dependencyManagement>");
        }
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>root</artifactId><version>1</version>
                <packaging>pom</packaging><properties><fluxzero.version>1.0.0-issue110</fluxzero.version></properties>
                <modules><module>selected</module><module>legacy</module><module>library</module></modules>
                <repositories><repository><id>fixture</id><url>%s</url><releases><checksumPolicy>ignore</checksumPolicy></releases></repository></repositories>
                <dependencyManagement><dependencies><dependency><groupId>io.fluxzero</groupId><artifactId>fluxzero-bom</artifactId>
                <version>${fluxzero.version}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>
                """.formatted(repository.toUri()));
        for (String name : List.of("selected", "legacy", "library")) {
            Files.createDirectories(root.resolve(name));
            String properties = name.equals("selected") ? "<properties><fluxzero.version>2.0.0-issue110</fluxzero.version></properties>" : "";
            String library = name.equals("selected") ? "<dependency><groupId>example</groupId><artifactId>library</artifactId><version>1</version></dependency>" : "";
            Files.writeString(root.resolve(name + "/pom.xml"), module(name,
                    properties + "<dependencies>" + sdk(null) + library + "</dependencies>"));
        }
        for (String name : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            Path target = root.resolve(name); Files.createDirectories(target.getParent());
            Files.copy(Path.of(name), target); target.toFile().setExecutable(true);
        }
    }

    private static String module(String name, String content) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>example</groupId><artifactId>root</artifactId>"
                + "<version>1</version></parent><artifactId>" + name + "</artifactId>" + content + "</project>";
    }

    private static String sdk(String version) {
        return "<dependency><groupId>io.fluxzero</groupId><artifactId>sdk</artifactId>"
                + (version == null ? "" : "<version>" + version + "</version>") + "</dependency>";
    }

    private static void artifact(Path repository, String id, String version, String content) throws Exception {
        Path file = repository.resolve("io/fluxzero/" + id + "/" + version + "/" + id + "-" + version + ".pom");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project><modelVersion>4.0.0</modelVersion><groupId>io.fluxzero</groupId><artifactId>"
                + id + "</artifactId><version>" + version + "</version>" + content + "</project>");
    }
}
