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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompilePlanTest {

    @Test
    void distinguishesProductionChangesFromTestApplicationChanges(@TempDir Path project) throws Exception {
        Path runtimeClasspath = project.resolve("target/fluxzero-dev/runtime-classpath.txt");
        Files.createDirectories(runtimeClasspath.getParent());
        Files.writeString(runtimeClasspath, "");
        MavenBuildIntrospection build = new MavenBuildIntrospection(
                project, project.resolve("target/classes"), runtimeClasspath, List.of(),
                project.resolve("target/generated-sources/annotations"),
                project.resolve("target/fluxzero-dev/compile-classpath.txt"), List.of(), "21");

        CompilePlan production = CompilePlan.from(
                project, build, false, true,
                Set.of(project.resolve("core/src/main/java/com/acme/UserId.java")));
        CompilePlan testApplication = CompilePlan.from(
                project, build, false, true,
                Set.of(project.resolve("app/src/test/java/com/acme/Rebound.java")));

        assertEquals("production sources/resources changed", production.reason());
        assertEquals("test application sources/resources changed", testApplication.reason());
    }
}
