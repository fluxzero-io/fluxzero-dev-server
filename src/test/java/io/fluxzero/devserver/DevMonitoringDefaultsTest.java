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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DevMonitoringDefaultsTest {
    @TempDir Path directory;

    @Test
    void unconfiguredProjectsInheritUserDefaultsWithPathsRelativeToTheDefaultsFile() throws Exception {
        Path defaults = defaults("auditlogJar: artifacts/auditlog.jar\nuiDirectory: artifacts/ui\n");
        var config = DevMonitoringDefaults.resolve(project(""), defaults);
        assertTrue(config.enabled());
        assertEquals(defaults.getParent().resolve("artifacts/auditlog.jar").toString(), config.auditlogJar());
        assertEquals(defaults.getParent().resolve("artifacts/ui").toString(), config.uiDirectory());
        assertEquals("victorialogs", config.storage());
        assertEquals("P1D", config.retention());
    }

    @Test
    void selectedProfileCanDisableDefaultsWithoutArtifactPaths() throws Exception {
        Path defaults = defaults("this: is not a valid monitoring config\n");
        var project = project("""
                defaultProfile: local
                profiles:
                  local:
                    monitoring:
                      enabled: false
                  inherited: {}
                """);
        assertNull(DevMonitoringDefaults.resolve(project.select(null).config(), defaults));
        assertThrows(DevServerStartupException.class,
                () -> DevMonitoringDefaults.resolve(project.select("inherited").config(), defaults));
    }

    @Test
    void explicitProjectConfigurationReplacesUserDefaultsAndKeepsProjectRelativePaths() throws Exception {
        Path defaults = defaults("auditlogJar: global.jar\nuiDirectory: global-ui\n");
        var config = DevMonitoringDefaults.resolve(project("""
                monitoring:
                  auditlogJar: custom.jar
                  uiDirectory: custom-ui
                  storage: testserver
                """), defaults);
        assertEquals("custom.jar", config.auditlogJar());
        assertEquals("custom-ui", config.uiDirectory());
        assertEquals("testserver", config.storage());
    }

    @Test
    void frontendOnlyProjectsDoNotStartLocalMonitoring() throws Exception {
        Path defaults = defaults("invalid defaults must not be read");
        var config = project("""
                frontendOnly: true
                frontend:
                  url: http://localhost:5173
                monitoring:
                  enabled: false
                """);
        assertNull(DevMonitoringDefaults.resolve(config, defaults));
    }

    @Test
    void absentDefaultsEnableBundledMonitoringAndExplicitDisableOptsOut() throws Exception {
        var config = DevMonitoringDefaults.resolve(project(""), directory.resolve("missing.yaml"));
        assertTrue(config.enabled());
        assertNull(config.auditlogJar());
        assertNull(config.uiDirectory());
        assertNull(DevMonitoringDefaults.resolve(project(""), defaults("enabled: false\n")));
    }

    @Test
    void malformedDefaultsReportTheirSourceInsteadOfSilentlyDisablingMonitoring() throws Exception {
        Path defaults = defaults("auditlogJar: only-one-path.jar\n");
        var failure = assertThrows(DevServerStartupException.class,
                () -> DevMonitoringDefaults.resolve(project(""), defaults));
        assertTrue(failure.getMessage().contains(defaults.toString()));
        assertTrue(failure.getMessage().contains("monitoring requires both auditlogJar and uiDirectory"));
    }

    @Test
    void inheritedMonitoringDoesNotOverwriteAnExistingService() throws Exception {
        Path defaults = defaults("auditlogJar: a.jar\nuiDirectory: ui\n");
        var config = project("""
                services:
                  monitoring-auditlog:
                    command: echo existing service
                    url: http://localhost:8080
                """);
        var failure = assertThrows(DevServerStartupException.class,
                () -> DevMonitoringDefaults.resolve(config, defaults));
        assertTrue(failure.getMessage().contains("monitoring.enabled: false"));
    }

    @Test
    void projectCanCustomizeBundledMonitoringWithoutArtifactPaths() throws Exception {
        var config = DevMonitoringDefaults.resolve(project("monitoring: {storage: testserver}\n"),
                directory.resolve("missing.yaml"));
        assertTrue(config.enabled());
        assertEquals("testserver", config.storage());
        assertNull(config.auditlogJar());
        assertNull(config.uiDirectory());
    }

    private Path defaults(String yaml) throws Exception {
        Path file = directory.resolve("user/monitoring.yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, yaml);
    }

    private DevProjectConfig project(String yaml) throws Exception {
        Path root = directory.resolve("project");
        Files.createDirectories(root.resolve(".fluxzero"));
        Files.writeString(root.resolve(".fluxzero/dev.yaml"), "version: 1\n" + yaml);
        return DevProjectConfig.load(root);
    }
}
