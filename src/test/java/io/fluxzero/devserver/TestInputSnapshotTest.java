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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInputSnapshotTest {

    @Test
    void capturesOnlyTestRelevantProjectInputs(@TempDir Path project) throws Exception {
        write(project, "pom.xml", "<project/>");
        write(project, "app/src/main/java/com/acme/App.java", "class App {}");
        write(project, "app/src/test/resources/example.json", "{}");
        write(project, "app/target/classes/App.class", "generated");
        write(project, "frontend/node_modules/package/index.js", "dependency");
        write(project, ".env", "SECRET=value");

        TestInputSnapshot snapshot = TestInputSnapshot.capture(project);

        assertEquals(3, snapshot.files().size());
        assertTrue(snapshot.files().containsKey("pom.xml"));
        assertTrue(snapshot.files().containsKey("app/src/main/java/com/acme/App.java"));
        assertTrue(snapshot.files().containsKey("app/src/test/resources/example.json"));
        assertFalse(snapshot.files().keySet().stream().anyMatch(path -> path.contains("target")));
        assertFalse(snapshot.files().keySet().stream().anyMatch(path -> path.contains("node_modules")));
        assertFalse(snapshot.files().containsKey(".env"));
    }

    @Test
    void detectsChangedAddedAndRemovedInputs(@TempDir Path project) throws Exception {
        Path pom = write(project, "pom.xml", "v1");
        Path removed = write(project, "src/test/java/com/acme/RemovedTest.java", "v1");
        TestInputSnapshot previous = TestInputSnapshot.capture(project);

        Files.writeString(pom, "v2");
        Files.delete(removed);
        Path added = write(project, "src/main/java/com/acme/Added.java", "v1");
        TestInputSnapshot current = TestInputSnapshot.capture(project);

        assertEquals(java.util.Set.of(pom, removed, added), current.changesSince(previous, project));
    }

    private static Path write(Path project, String relative, String content) throws Exception {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
