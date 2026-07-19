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
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

final class DevSessionStore {
    static final Path DEV_DIRECTORY = Path.of(".fluxzero", "dev");
    static final String SESSION_FILE = "session.json";
    static final String SESSION_LOCK_FILE = "session.lock";
    static final String TEST_STATUS_FILE = "test-status.json";
    static final String TEST_IMPACT_FILE = "test-impact.json";
    static final String TEST_INPUTS_FILE = "test-inputs.json";
    static final String COMMAND_STATUS_FILE = "command-status.json";
    static final String GITIGNORE_FILE = ".gitignore";

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path directory;

    DevSessionStore(Path projectDirectory) {
        this.directory = projectDirectory.resolve(DEV_DIRECTORY);
    }

    Path directory() {
        return directory;
    }

    synchronized void writeSession(DevSession session) {
        writeJson(directory.resolve(SESSION_FILE), session);
    }

    synchronized Optional<DevSession> readSession() {
        Path target = directory.resolve(SESSION_FILE);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), DevSession.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dev session file " + target, e);
        }
    }

    synchronized void writeTestStatus(TestStatus status) {
        writeJson(directory.resolve(TEST_STATUS_FILE), status);
    }

    synchronized Optional<TestStatus> readTestStatus() {
        Path target = directory.resolve(TEST_STATUS_FILE);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), TestStatus.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dev test status file " + target, e);
        }
    }

    synchronized void writeTestInputs(TestInputSnapshot snapshot) {
        writeJson(directory.resolve(TEST_INPUTS_FILE), snapshot);
    }

    synchronized Optional<TestInputSnapshot> readTestInputs() {
        Path target = directory.resolve(TEST_INPUTS_FILE);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), TestInputSnapshot.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    synchronized void writeCommandStatus(DevCommandStatus status) {
        writeJson(directory.resolve(COMMAND_STATUS_FILE), status);
    }

    synchronized Optional<DevCommandStatus> readCommandStatus() {
        Path target = directory.resolve(COMMAND_STATUS_FILE);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), DevCommandStatus.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dev command status file " + target, e);
        }
    }

    synchronized Optional<DevSession> reconcileUnexpectedStop() {
        Optional<DevSession> current = readSession();
        if (current.isEmpty()) {
            return current;
        }
        DevSession session = current.get();
        if ("stopped".equals(session.status()) || "stopped-unexpectedly".equals(session.status())
            || ProcessUtils.isAlive(session.pid())) {
            return current;
        }
        String detail = "dev server process stopped unexpectedly; in-memory runtime data was lost";
        DevSession reconciled = session.withStoppedServices(detail).withStatus("stopped-unexpectedly");
        writeSession(reconciled);
        invalidateCommandStatus(session.sessionId(),
                                "runtime session ended unexpectedly; command will run again in the next session");
        return Optional.of(reconciled);
    }

    synchronized void invalidateCommandStatus(String sessionId, String detail) {
        readCommandStatus().filter(status -> sessionId.equals(status.sessionId()))
                .ifPresent(status -> writeCommandStatus(status.invalidated(detail)));
    }

    DevSessionLock acquireLock() {
        try {
            prepareDirectory();
            Path lockFile = directory.resolve(SESSION_LOCK_FILE);
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("Another Fluxzero dev environment is already active for "
                                                + directory.getParent().getParent());
            }
            return new DevSessionLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            throw new IllegalStateException("Another Fluxzero dev environment is already active for "
                                            + directory.getParent().getParent(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to acquire dev session lock in " + directory, e);
        }
    }

    private void writeJson(Path target, Object value) {
        try {
            prepareDirectory();
            Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
            objectMapper.writeValue(temp.toFile(), value);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write dev session file " + target, e);
        }
    }

    private void prepareDirectory() throws IOException {
        Files.createDirectories(directory);
        Path gitignore = directory.resolve(GITIGNORE_FILE);
        if (Files.notExists(gitignore)) {
            try {
                Files.writeString(gitignore, "*\n", StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another session artifact writer created it concurrently.
            }
        }
    }

    record DevSessionLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
