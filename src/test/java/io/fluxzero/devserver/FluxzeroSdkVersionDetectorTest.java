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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluxzeroSdkVersionDetectorTest {

    @Test
    void detectsMavenPropertyAndReactorModules(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>root</artifactId><version>1</version>
                  <properties><fluxzero.sdk.version>2.0.0-RC1</fluxzero.sdk.version></properties>
                  <modules><module>app</module></modules>
                </project>
                """);
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.writeString(projectDirectory.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId><artifactId>root</artifactId><version>1</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>io.fluxzero</groupId><artifactId>sdk</artifactId>
                      <version>${fluxzero.sdk.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertEquals(Set.of("2.0.0-RC1"), FluxzeroSdkVersionDetector.detect(projectDirectory));
    }

    @Test
    void detectsGradleVersionProperty(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("gradle.properties"), "fluxzeroVersion=1.246.0\n");
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("io.fluxzero:sdk:$fluxzeroVersion")
                }
                """);

        assertEquals(Set.of("1.246.0"), FluxzeroSdkVersionDetector.detect(projectDirectory));
    }

    @Test
    void declarativeVersionWinsOverStaleRuntimeClasspath(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>app</artifactId><version>1</version>
                  <properties><fluxzero.version>2.0.0-RC1</fluxzero.version></properties>
                </project>
                """);
        Files.createDirectories(projectDirectory.resolve("target/fluxzero-dev"));
        Files.writeString(projectDirectory.resolve("target/fluxzero-dev/runtime-classpath.txt"),
                          Path.of(System.getProperty("user.home"), ".m2", "repository", "io", "fluxzero", "sdk",
                                  "1.246.0", "sdk-1.246.0.jar").toString());

        assertEquals(Set.of("2.0.0-RC1"), FluxzeroSdkVersionDetector.detect(projectDirectory));
    }

    @Test
    void selectsNewestCompatibleVersionAndRejectsDifferentMajorGenerations() {
        assertEquals("1.246.0", FluxzeroSdkVersionDetector.compatibleVersion(
                "development environment", Set.of("1.240.0", "1.246.0")));

        DevServerStartupException failure = assertThrows(DevServerStartupException.class,
                                                         () -> FluxzeroSdkVersionDetector.compatibleVersion(
                                                                 "development environment",
                                                                 Set.of("1.246.0", "2.0.0-RC1")));
        assertTrue(failure.getMessage().contains("separate dev environments"));
    }
}
