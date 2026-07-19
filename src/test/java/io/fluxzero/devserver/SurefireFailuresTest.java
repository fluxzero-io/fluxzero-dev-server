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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SurefireFailuresTest {

    @Test
    void readsAndClearsFailuresAcrossReactorModules(@TempDir Path projectDirectory) throws Exception {
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
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <testsuite name="com.acme.OrderHandlerTest" tests="1" errors="1">
                  <testcase classname="com.acme.OrderHandlerTest" name="createsOrder">
                    <error message="java.lang.NoClassDefFoundError: com/acme/Order"/>
                  </testcase>
                </testsuite>
                """);

        SurefireFailures.Result result = SurefireFailures.read(projectDirectory, System.currentTimeMillis());

        assertEquals(java.util.Set.of("com.acme.OrderHandlerTest#createsOrder"), result.selectors());
        assertEquals("com.acme.OrderHandlerTest#createsOrder: java.lang.NoClassDefFoundError: com/acme/Order",
                     result.firstFailure());

        SurefireFailures.clear(projectDirectory);
        assertFalse(Files.exists(report));
    }
}
