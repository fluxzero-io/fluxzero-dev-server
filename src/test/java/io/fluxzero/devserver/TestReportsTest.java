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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestReportsTest {

    @Test
    void readsAndClearsMavenFailuresAcrossReactorModules(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>root</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  <modules><module>orders</module></modules>
                </project>
                """);
        Path module = projectDirectory.resolve("orders");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>orders</artifactId>
                  <version>1</version>
                </project>
                """);
        Path report = module.resolve("target/surefire-reports/TEST-com.acme.OrderHandlerTest.xml");
        writeFailure(report);

        TestReports.Result result = TestReports.read(
                projectDirectory, BuildTool.MAVEN, System.currentTimeMillis());

        assertTrue(result.failureFound());
        assertEquals(java.util.Set.of("com.acme.OrderHandlerTest#createsOrder"), result.failingSelectors());
        assertEquals("com.acme.OrderHandlerTest#createsOrder: java.lang.NoClassDefFoundError: com/acme/Order",
                     result.firstFailure());

        TestReports.clear(projectDirectory, BuildTool.MAVEN);
        assertFalse(Files.exists(report));
    }

    @Test
    void readsGradleFailuresFromConfiguredModules(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("settings.gradle"), "include 'orders'");
        Path metadata = projectDirectory.resolve(GradleBuildMetadata.FILE);
        Files.createDirectories(metadata.getParent());
        new ObjectMapper().writeValue(metadata.toFile(), Map.of("modules", List.of(Map.of(
                "path", ":orders", "name", "orders"))));
        Path report = projectDirectory.resolve(
                "orders/build/test-results/fluxzeroDevTest/TEST-com.acme.OrderHandlerTest.xml");
        writeFailure(report);

        TestReports.Result result = TestReports.read(
                projectDirectory, BuildTool.GRADLE, System.currentTimeMillis());

        assertTrue(result.failureFound());
        assertEquals(java.util.Set.of("com.acme.OrderHandlerTest#createsOrder"), result.failingSelectors());

        TestReports.clear(projectDirectory, BuildTool.GRADLE);
        assertFalse(Files.exists(report));
    }

    private static void writeFailure(Path report) throws Exception {
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <testsuite name="com.acme.OrderHandlerTest" tests="1" errors="1">
                  <testcase classname="com.acme.OrderHandlerTest" name="createsOrder">
                    <error message="java.lang.NoClassDefFoundError: com/acme/Order"/>
                  </testcase>
                </testsuite>
                """);
    }
}
