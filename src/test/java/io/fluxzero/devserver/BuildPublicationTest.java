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
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BuildPublicationTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void reproducesWindowsDirectoryLockAndPublishesAfterRelease(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve(".staging-windows"));
        Path classes = Files.writeString(source.resolve("App.class"), "new classes");
        Path target = root.resolve("build-windows");
        var lock = java.nio.channels.FileChannel.open(classes, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        try (lock; var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            IOException original = assertThrows(IOException.class,
                    () -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
            System.out.println("Windows staging reproduction: " + BuildPublication.describe(original));
            assertTrue(Files.exists(source));
            executor.schedule(() -> { try { lock.close(); } catch (IOException e) { throw new RuntimeException(e); } },
                    100, java.util.concurrent.TimeUnit.MILLISECONDS);
            BuildPublication.publish(source, target);
            assertEquals("new classes", Files.readString(target.resolve("App.class")));
        }
    }

    @Test void publishesCompleteSnapshot(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve(".staging-1"));
        Files.writeString(source.resolve("App.class"), "new classes");
        Path target = root.resolve("build-1");
        BuildPublication.publish(source, target);
        assertFalse(Files.exists(source));
        assertEquals("new classes", Files.readString(target.resolve("App.class")));
    }

    @Test void preservesExistingBuildAndExplainsFailure(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve(".staging-1"));
        Path target = Files.createDirectory(root.resolve("build-1"));
        Files.writeString(target.resolve("App.class"), "active classes");
        IOException error = assertThrows(IOException.class, () -> BuildPublication.publish(source, target));
        assertTrue(error.getMessage().contains("compilation succeeded"));
        assertTrue(error.getMessage().contains("FileAlreadyExistsException"));
        assertTrue(error.getMessage().contains(source.toString()));
        assertTrue(error.getMessage().contains(target.toString()));
        assertEquals("active classes", Files.readString(target.resolve("App.class")));
        assertTrue(Files.exists(source));
    }

    @Test void retriesTransientWindowsSharingViolations() throws Exception {
        var attempts = new AtomicInteger();
        BuildPublication.retry(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) throw new AccessDeniedException("staging");
            if (attempt == 2) throw new FileSystemException("staging", "build", "sharing violation");
        }, true, Duration.ofSeconds(1));
        assertEquals(3, attempts.get());
    }

    @Test void boundsRetriesAndPreservesOriginalFailure() {
        var error = new AccessDeniedException("staging");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertSame(error, assertThrows(IOException.class, () -> BuildPublication.retry(
                        () -> { throw error; }, true, Duration.ofMillis(50)))));
    }

    @Test void doesNotRetryMissingPathsOrUnixPermissionErrors() {
        for (IOException error : new IOException[]{new NoSuchFileException("staging"), new FileAlreadyExistsException("build")}) {
            var attempts = new AtomicInteger();
            assertSame(error, assertThrows(IOException.class, () -> BuildPublication.retry(() -> {
                attempts.incrementAndGet(); throw error;
            }, true, Duration.ofSeconds(1))));
            assertEquals(1, attempts.get());
        }
        var attempts = new AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> BuildPublication.retry(() -> {
            attempts.incrementAndGet(); throw new AccessDeniedException("staging");
        }, false, Duration.ofSeconds(1)));
        assertEquals(1, attempts.get());
    }

    @Test void preservesInterrupt() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(AccessDeniedException.class, () -> BuildPublication.retry(() -> {
                throw new AccessDeniedException("staging");
            }, true, Duration.ofSeconds(1)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
