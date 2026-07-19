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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildCommandTest {

    @Test
    void preservesExistingOptionsAndAddsJavaCompatibilityOptionsOnce() {
        String options = MavenCommand.jvmOptions("-Xmx1g --enable-native-access=ALL-UNNAMED");

        assertTrue(options.contains("-Xmx1g"));
        assertEquals(1, occurrences(options, "--enable-native-access=ALL-UNNAMED"));
        if (Runtime.version().feature() >= 24) {
            assertEquals(1, occurrences(options, "--sun-misc-unsafe-memory-access=allow"));
        }
    }

    @Test
    void configuresBothMavenAndGradleProcesses() {
        assertTrue(MavenCommand.environment().get("MAVEN_OPTS").contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(GradleCommand.environment().get("GRADLE_OPTS").contains("--enable-native-access=ALL-UNNAMED"));
    }

    private static int occurrences(String value, String expected) {
        return value.split(java.util.regex.Pattern.quote(expected), -1).length - 1;
    }
}
