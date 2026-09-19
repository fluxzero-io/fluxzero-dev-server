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
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFolderOpenerTest {
    @Test
    void passesPathsAsSingleArgumentsWithoutShellExpansion() {
        String windows = "C:\\Users\\Dev User\\projects & files";
        assertEquals(List.of("explorer.exe", windows), ProjectFolderOpener.command("Windows 11", windows));
        String unix = "/Users/dev/projects with spaces/$(never-execute)";
        assertEquals(List.of("/usr/bin/open", unix), ProjectFolderOpener.command("Mac OS X", unix));
        assertEquals(List.of("xdg-open", unix), ProjectFolderOpener.command("Linux", unix));
    }
    @Test
    void doesNotLaunchForMissingFolders(@TempDir Path directory) {
        assertThrows(java.io.IOException.class, () -> ProjectFolderOpener.open(directory.resolve("missing")));
    }
}
