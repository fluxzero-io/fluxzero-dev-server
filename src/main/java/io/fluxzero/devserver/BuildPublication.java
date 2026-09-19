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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Publishes a completed snapshot without replacing or stopping the active application. */
final class BuildPublication {
    private BuildPublication() {}

    static void publish(Path source, Path target) throws IOException {
        try {
            retry(() -> {
                if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
                try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
            }, ProcessUtils.isWindows(), Duration.ofSeconds(2));
        } catch (IOException e) {
            throw new IOException("Build compilation succeeded, but snapshot publication failed: "
                    + source + " -> " + target + ". The active application was not replaced. "
                    + describe(e), e);
        }
    }

    static void retry(Operation operation, boolean windows, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try { operation.run(); return; }
            catch (IOException e) {
                // Windows reports transient sharing violations either as AccessDeniedException
                // or a plain FileSystemException. Do not retry missing paths or target collisions.
                if (!windows || !(e instanceof AccessDeniedException || e.getClass() == FileSystemException.class)
                        || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) throw e;
                try { TimeUnit.MILLISECONDS.sleep(25); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; failure != null && depth < 5; depth++, failure = failure.getCause()) {
            if (depth > 0) result.append("; caused by ");
            result.append(failure.getClass().getSimpleName());
            if (failure.getMessage() != null) result.append(": ").append(failure.getMessage());
        }
        return result.toString();
    }

    @FunctionalInterface interface Operation { void run() throws IOException; }
}
