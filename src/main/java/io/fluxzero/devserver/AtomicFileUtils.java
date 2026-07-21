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

    private static void retrySharingViolation(IoOperation operation) throws IOException {
        long deadline = System.nanoTime() + SHARING_VIOLATION_TIMEOUT.toNanos();
        while (true) {
            try {
                operation.run();
                return;
            } catch (AccessDeniedException e) {
                if (System.nanoTime() >= deadline) {
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
    private interface IoOperation {
        void run() throws IOException;
    }
}
