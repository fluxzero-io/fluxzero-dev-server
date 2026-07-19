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
import java.util.List;
import java.util.Map;

record ApplicationBuild(
        String applicationName,
        String module,
        String mainClass,
        List<Path> classesDirectories,
        List<Path> runtimeClasspath,
        boolean testApplication,
        String launchId,
        Map<String, String> environment,
        Map<String, String> secretReferences
) {
    ApplicationBuild {
        classesDirectories = classesDirectories == null ? List.of() : List.copyOf(classesDirectories);
        runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
        launchId = launchId == null || launchId.isBlank() ? applicationName : launchId;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        secretReferences = secretReferences == null ? Map.of() : Map.copyOf(secretReferences);
    }

    ApplicationBuild(String applicationName, String module, String mainClass, List<Path> classesDirectories,
                     List<Path> runtimeClasspath, boolean testApplication) {
        this(applicationName, module, mainClass, classesDirectories, runtimeClasspath, testApplication,
             applicationName, Map.of(), Map.of());
    }

    ApplicationBuild(String applicationName, String module, String mainClass, List<Path> classesDirectories,
                     List<Path> runtimeClasspath) {
        this(applicationName, module, mainClass, classesDirectories, runtimeClasspath, false);
    }

    ApplicationBuild withLocations(List<Path> classes, List<Path> classpath) {
        return new ApplicationBuild(applicationName, module, mainClass, classes, classpath, testApplication,
                                    launchId, environment, secretReferences);
    }

    @Override
    public String toString() {
        return "ApplicationBuild[launchId=" + launchId + ", applicationName=" + applicationName
               + ", module=" + module + ", mainClass=" + mainClass + ", testApplication=" + testApplication
               + ", env=" + environment.keySet() + ", secrets=" + secretReferences.keySet() + "]";
    }

    Path classesDirectory() {
        if (classesDirectories.isEmpty()) {
            throw new IllegalStateException("No classes directory available for " + applicationName);
        }
        return classesDirectories.getFirst();
    }
}
