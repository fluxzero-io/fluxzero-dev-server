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
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class AtomicFileUtils {
    private static final Duration SHARING_VIOLATION_TIMEOUT = Duration.ofSeconds(1);

    private AtomicFileUtils() {
    }

    static void replace(Path source, Path target) throws IOException {
        retrySharingViolation(() -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                                               StandardCopyOption.ATOMIC_MOVE));
    }

    static void deleteIfExists(Path target) throws IOException {
        retrySharingViolation(() -> Files.deleteIfExists(target));
    }

    static byte[] readAllBytes(Path target) throws IOException {
        // NIO opens Windows handles with FILE_SHARE_DELETE, allowing the writer to replace the snapshot.
        return retrySharingViolation(() -> Files.readAllBytes(target));
    }

    static <T> T retrySharingViolation(IoOperation<T> operation) throws IOException {
        long deadline = System.nanoTime() + SHARING_VIOLATION_TIMEOUT.toNanos();
        while (true) {
            try {
                return operation.run();
            } catch (FileSystemException e) {
                // Windows maps sharing violations to an unclassified FileSystemException with a localized
                // reason. Do not match English messages or retry typed permanent failures (e.g. missing files).
                if (!(e instanceof AccessDeniedException || e.getClass() == FileSystemException.class)
                    || System.nanoTime() >= deadline) {
                    throw e;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @FunctionalInterface
    interface IoOperation<T> {
        T run() throws IOException;
    }
}
