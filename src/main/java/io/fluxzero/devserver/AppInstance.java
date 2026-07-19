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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

record AppInstance(String launchId, String applicationName, long buildNumber, String clientId, Process process,
                   java.util.List<java.nio.file.Path> cleanupFiles, java.util.Set<String> environmentNames,
                   java.util.Set<String> secretNames) implements AutoCloseable {
    AppInstance {
        cleanupFiles = cleanupFiles == null ? java.util.List.of() : java.util.List.copyOf(cleanupFiles);
        environmentNames = environmentNames == null ? java.util.Set.of() : java.util.Set.copyOf(environmentNames);
        secretNames = secretNames == null ? java.util.Set.of() : java.util.Set.copyOf(secretNames);
    }

    AppInstance(String launchId, String applicationName, long buildNumber, String clientId, Process process) {
        this(launchId, applicationName, buildNumber, clientId, process, java.util.List.of(), java.util.Set.of(),
             java.util.Set.of());
    }
    boolean alive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }

    CompletableFuture<Process> onExit() {
        return process.onExit();
    }

    void stop(Duration timeout) {
        if (process.isAlive()) {
            ProcessUtils.stopTree(process, timeout);
        }
        cleanup();
    }

    @Override
    public void close() {
        ProcessUtils.forceStopTree(process);
        cleanup();
    }

    private void cleanup() {
        cleanupFiles.forEach(path -> {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (java.io.IOException ignored) {
                // Secret reference files contain no values and are cleaned best-effort.
            }
        });
    }
}
