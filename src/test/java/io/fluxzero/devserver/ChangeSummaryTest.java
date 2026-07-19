/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeSummaryTest {

    @Test
    void rendersStableRelativePathsAndCapsLongLists(@TempDir Path projectDirectory) {
        ChangeSummary summary = ChangeSummary.of(projectDirectory, Set.of(
                projectDirectory.resolve("src/main/java/com/acme/Z.java"),
                projectDirectory.resolve("src/main/java/com/acme/A.java"),
                projectDirectory.resolve("src/main/java/com/acme/B.java"),
                projectDirectory.resolve("src/main/java/com/acme/C.java")));

        assertEquals("4 Java sources changed", summary.category());
        assertEquals("src/main/java/com/acme/A.java, src/main/java/com/acme/B.java, "
                     + "src/main/java/com/acme/C.java (and 1 more)", summary.displayPaths());
    }

    @Test
    void recognizesTestAndCommandOnlyChanges(@TempDir Path projectDirectory) {
        assertEquals("test source changed", ChangeSummary.of(projectDirectory, Set.of(
                projectDirectory.resolve("src/test/java/com/acme/A.java"))).category());
        assertEquals("dev command changed", ChangeSummary.of(projectDirectory, Set.of(
                projectDirectory.resolve(".fluxzero/dev.yaml"))).category());
    }
}
