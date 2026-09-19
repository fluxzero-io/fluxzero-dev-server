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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opens only a directory, with separate process arguments and without a shell. */
final class ProjectFolderOpener {
    static void open(Path directory) throws IOException {
        Path folder = directory.toRealPath();
        if (!Files.isDirectory(folder)) throw new IOException("The project folder no longer exists.");
        new ProcessBuilder(command(System.getProperty("os.name", ""), folder.toString()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    static List<String> command(String os, String folder) {
        String platform = os.toLowerCase(Locale.ROOT);
        if (platform.startsWith("windows")) return List.of("explorer.exe", folder);
        if (platform.contains("mac") || platform.contains("darwin")) return List.of("/usr/bin/open", folder);
        return List.of("xdg-open", folder);
    }
}
