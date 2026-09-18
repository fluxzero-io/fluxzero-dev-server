/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/** User-local monitoring defaults, overridden by the selected project's development profile. */
final class DevMonitoringDefaults {
    static final String FILE_PROPERTY = "fluxzero.dev.monitoringDefaults";
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private DevMonitoringDefaults() {}

    static DevMonitoringConfig resolve(DevProjectConfig project) {
        String override = System.getProperty(FILE_PROPERTY);
        Path file = override == null ? Path.of(System.getProperty("user.home"), ".fluxzero", "dev", "monitoring.yaml")
                : Path.of(override);
        return resolve(project, file.toAbsolutePath().normalize());
    }

    static DevMonitoringConfig resolve(DevProjectConfig project, Path defaultsFile) {
        if (Boolean.TRUE.equals(project.frontendOnly())) return null;
        DevMonitoringConfig selected = project.monitoring();
        if (selected == null && Files.isRegularFile(defaultsFile)) {
            try {
                selected = MAPPER.readValue(defaultsFile.toFile(), DevMonitoringConfig.class);
                if (selected == null) throw new IllegalArgumentException("Expected a monitoring configuration");
                if (selected.enabled()) {
                    Path root = defaultsFile.toAbsolutePath().getParent();
                    selected = new DevMonitoringConfig(absolute(root, selected.auditlogJar()), absolute(root, selected.uiDirectory()),
                            selected.storage(), absolute(root, selected.victoriaLogsBinary()), selected.maxRecords(), selected.maxBytes(),
                            selected.retention(), absolute(root, selected.javaExecutable()), selected.maxDiskBytes(), true);
                }
            } catch (Exception e) {
                throw new DevServerStartupException("Could not read monitoring defaults " + defaultsFile + ": " + e.getMessage(), e);
            }
        }
        if (selected == null || !selected.enabled()) return null;
        if (project.services().keySet().stream().anyMatch(id -> id.startsWith("monitoring-"))) {
            throw new DevServerStartupException("Service names starting with monitoring- are reserved for local monitoring. "
                    + "Rename the service or set monitoring.enabled: false in the selected development profile.");
        }
        return selected;
    }

    private static String absolute(Path root, String value) {
        return value == null ? null : root.resolve(value).normalize().toString();
    }
}
