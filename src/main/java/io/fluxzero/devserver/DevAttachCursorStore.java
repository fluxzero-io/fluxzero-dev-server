/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

final class DevAttachCursorStore {
    static final String FILE = "attach.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path file;

    DevAttachCursorStore(Path projectDirectory) {
        file = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY).resolve(FILE);
    }

    Optional<Cursor> read(String sessionId) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Cursor cursor = objectMapper.readValue(file.toFile(), Cursor.class);
            return sessionId.equals(cursor.sessionId()) ? Optional.of(cursor) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    void write(String sessionId, long offset) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), FILE, ".tmp");
            objectMapper.writeValue(temporary.toFile(), new Cursor(sessionId, Math.max(0, offset)));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store dev attach cursor in " + file, e);
        }
    }

    record Cursor(String sessionId, long offset) {
    }
}
