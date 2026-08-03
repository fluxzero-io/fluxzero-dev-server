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

record DevBuildProject(
        String id,
        Path directory,
        String mainClass,
        String applicationName,
        String namespace,
        boolean fastCompilerEnabled,
        List<String> applications,
        Map<String, DevApplicationConfig> applicationConfig
) {
    DevBuildProject {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("project id must not be blank");
        }
        id = id.strip();
        directory = directory.toAbsolutePath().normalize();
        applications = applications == null ? List.of() : List.copyOf(applications);
        applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
    }
}
