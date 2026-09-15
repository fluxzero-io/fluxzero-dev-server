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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

final class MonitoringStorage {
    static void clear(Path project) throws IOException {
        Path root = project.toRealPath();
        Path data = root.resolve(".fluxzero/dev/monitoring/victorialogs");
        for (Path path = data; !path.equals(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("Refusing to clear linked monitoring storage: " + path);
        }
        if (!Files.exists(data)) return;
        Files.walkFileTree(data, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                if (!directory.equals(data)) Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
