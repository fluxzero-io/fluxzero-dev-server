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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

record BuildSnapshot(
        long buildNumber,
        Path buildDirectory,
        Path classesDirectory,
        List<Path> runtimeClasspath,
        Instant createdAt,
        CompileTiming compileTiming,
        List<ApplicationBuild> applications
) {

    BuildSnapshot {
        runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
        applications = applications == null ? List.of() : List.copyOf(applications);
    }

    BuildSnapshot(long buildNumber, Path buildDirectory, Path classesDirectory, List<Path> runtimeClasspath,
                  Instant createdAt) {
        this(buildNumber, buildDirectory, classesDirectory, runtimeClasspath, createdAt, CompileTiming.unknown(),
             List.of());
    }

    BuildSnapshot(long buildNumber, Path buildDirectory, Path classesDirectory, List<Path> runtimeClasspath,
                  Instant createdAt, CompileTiming compileTiming) {
        this(buildNumber, buildDirectory, classesDirectory, runtimeClasspath, createdAt, compileTiming, List.of());
    }
}
