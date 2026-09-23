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

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileUtilsTest {
    @Test
    void retriesSharingViolationsWithoutDependingOnLocalizedMessages() throws Exception {
        var attempts = new AtomicInteger();
        String result = AtomicFileUtils.retrySharingViolation(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) throw new AccessDeniedException("session.json");
            if (attempt == 2) throw new FileSystemException("session.json", null, "In gebruik door een ander proces");
            return "snapshot";
        });
        assertEquals("snapshot", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void persistentSharingViolationIsBoundedAndRetainsCause() {
        var attempts = new AtomicInteger();
        var failure = new FileSystemException("session.json", null, "sharing violation");
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertSame(failure, assertThrows(FileSystemException.class, () ->
                    AtomicFileUtils.retrySharingViolation(() -> {
                        attempts.incrementAndGet();
                        throw failure;
                    })));
        });
        assertTrue(attempts.get() > 1);
    }

    @Test
    void interruptionStopsRetryAndPreservesInterruptFlag() {
        var attempts = new AtomicInteger();
        var failure = new AccessDeniedException("session.json");
        Thread.currentThread().interrupt();
        try {
            assertSame(failure, assertThrows(AccessDeniedException.class, () ->
                    AtomicFileUtils.retrySharingViolation(() -> {
                        attempts.incrementAndGet();
                        throw failure;
                    })));
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void typedPermanentFailuresAndOtherIoErrorsAreNotRetried() {
        for (IOException failure : List.of(new NoSuchFileException("session.json"),
                                          new FileAlreadyExistsException("session.json"),
                                          new AtomicMoveNotSupportedException("temp", "session.json", "unsupported"),
                                          new IOException("broken storage"))) {
            var attempts = new AtomicInteger();
            assertSame(failure, assertThrows(IOException.class, () -> AtomicFileUtils.retrySharingViolation(() -> {
                attempts.incrementAndGet();
                throw failure;
            })));
            assertEquals(1, attempts.get());
        }
    }
}
