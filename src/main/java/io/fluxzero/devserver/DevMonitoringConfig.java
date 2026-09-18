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
import java.time.Duration;
import java.util.Set;

/** Local Auditlog artifacts; both storage implementations use the same UI and backend. */
public record DevMonitoringConfig(String auditlogJar, String uiDirectory, String storage,
                                  String victoriaLogsBinary, Long maxRecords, Long maxBytes,
                                  String retention, String javaExecutable, Long maxDiskBytes, Boolean enabled) {
    public DevMonitoringConfig(String auditlogJar, String uiDirectory, String storage,
                               String victoriaLogsBinary, Long maxRecords, Long maxBytes,
                               String retention, String javaExecutable, Long maxDiskBytes) {
        this(auditlogJar, uiDirectory, storage, victoriaLogsBinary, maxRecords, maxBytes,
             retention, javaExecutable, maxDiskBytes, true);
    }

    public DevMonitoringConfig {
        enabled = enabled == null || enabled;
        if (enabled && (auditlogJar == null || auditlogJar.isBlank() || uiDirectory == null || uiDirectory.isBlank())) {
            throw new IllegalArgumentException("monitoring requires auditlogJar and uiDirectory");
        }
        storage = storage == null ? "victorialogs" : storage;
        if (!Set.of("victorialogs", "testserver").contains(storage)) {
            throw new IllegalArgumentException("monitoring.storage must be victorialogs or testserver");
        }
        maxDiskBytes = maxDiskBytes == null ? 1024L * 1024 * 1024 : maxDiskBytes;
        maxRecords = maxRecords == null ? 5000 : maxRecords;
        maxBytes = maxBytes == null ? 8L * 1024 * 1024 : maxBytes;
        retention = retention == null ? (storage.equals("victorialogs") ? "P1D" : "PT15M") : retention;
        Duration duration = Duration.parse(retention);
        if (maxDiskBytes < 1024 || maxRecords < 1 || maxBytes < 1024 || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("monitoring retention and budgets must be positive");
        }
        if (storage.equals("victorialogs") && duration.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("VictoriaLogs requires at least one day of retention");
        }
    }

    Path resolve(Path project, String value) { return project.resolve(value).toAbsolutePath().normalize(); }
}
