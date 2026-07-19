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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerConfigTest {

    @Test
    void parsesCommandLineOptions(@TempDir Path projectDirectory) {
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(),
                "--main-class", "com.acme.App",
                "--application-name", "acme",
                "--namespace", "local",
                "--no-watch",
                "--no-tests",
                "--startup-timeout-ms", "1234",
                "--graceful-shutdown-timeout-ms", "4321",
                "--debounce-ms", "50",
                "--fast-compiler",
                "--environment", "dev",
                "--port", "4200",
                "--idp", "external",
                "--app", "app",
                "--app", "audittrail",
                "--frontend-command", "npm run dev",
                "--frontend-directory", "frontend",
                "--frontend-setup-command", "npm install --prefer-offline --no-audit --no-fund",
                "--backend-path", "/api",
                "--backend-path", "rest/",
                "--app-arg", "--spring.profiles.active=dev",
                "--app-arg", "--debug"
        });

        assertEquals(projectDirectory.toAbsolutePath(), config.projectDirectory());
        assertEquals("com.acme.App", config.mainClass());
        assertEquals("acme", config.applicationName());
        assertEquals("local", config.namespace());
        assertFalse(config.watch());
        assertFalse(config.testsEnabled());
        assertEquals(Duration.ofMillis(1234), config.startupTimeout());
        assertEquals(Duration.ofMillis(4321), config.gracefulShutdownTimeout());
        assertEquals(Duration.ofMillis(50), config.debounce());
        assertEquals(FrontendConfig.Mode.COMMAND, config.frontend().mode());
        assertEquals("npm run dev", config.frontend().command());
        assertEquals("frontend", config.frontend().directory());
        assertEquals("npm install --prefer-offline --no-audit --no-fund", config.frontend().setupCommand());
        assertEquals(List.of("/api", "/rest"), config.frontend().backendPaths());
        assertEquals(List.of("--spring.profiles.active=dev", "--debug"), config.appArgs());
        assertTrue(config.fastCompilerEnabled());
        assertEquals("dev", config.environment());
        assertEquals(List.of("app", "audittrail"), config.applications());
        assertEquals(4200, config.gatewayPort());
        assertEquals(IdpMode.EXTERNAL, config.idpMode());
    }

    @Test
    void parsesExternalFrontendUrl(@TempDir Path projectDirectory) {
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--dir=" + projectDirectory,
                "--frontend-url=http://localhost:5173"
        });

        assertEquals(FrontendConfig.Mode.EXTERNAL_URL, config.frontend().mode());
        assertEquals("http://localhost:5173", config.frontend().url());
        assertEquals(List.of("/api"), config.frontend().backendPaths());
        assertEquals("local", config.environment());
        assertTrue(config.applications().isEmpty());
        assertEquals(0, config.gatewayPort());
        assertEquals(IdpMode.MANAGED, config.idpMode());
    }

    @Test
    void normalizesProjectDirectory(@TempDir Path projectDirectory) {
        DevServerConfig config = DevServerConfig.defaults(projectDirectory.resolve("nested").resolve(".."));

        assertEquals(projectDirectory.toAbsolutePath().normalize(), config.projectDirectory());
    }

    @Test
    void loadsTrackedProjectDefaultsAndLetsCliOverrideThem(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                environment: local
                apps:
                  - app
                  - audittrail
                port: 4200
                idp: external
                fastCompiler: true
                frontend:
                  directory: frontend
                  setupCommand: "npm install --prefer-offline --no-audit --no-fund"
                  command: "npm start -- --port {port}"
                  backendPaths:
                    - /api
                    - /graphql
                commands:
                  create-user:
                    type: com.example.CreateUser
                    revision: 2
                    payload:
                      name: Ada
                  assign-role:
                    type: com.example.AssignRole
                """);

        DevServerConfig defaults = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString()
        });
        assertEquals("local", defaults.environment());
        assertEquals(List.of("app", "audittrail"), defaults.applications());
        assertEquals(4200, defaults.gatewayPort());
        assertEquals(IdpMode.EXTERNAL, defaults.idpMode());
        assertTrue(defaults.fastCompilerEnabled());
        assertEquals("npm start -- --port {port}", defaults.frontend().command());
        assertEquals("frontend", defaults.frontend().directory());
        assertEquals("npm install --prefer-offline --no-audit --no-fund",
                     defaults.frontend().setupCommand());
        assertEquals(List.of("/api", "/graphql"), defaults.frontend().backendPaths());
        DevProjectConfig projectConfig = DevProjectConfig.load(projectDirectory);
        assertEquals(List.of("create-user", "assign-role"), List.copyOf(projectConfig.commands().keySet()));
        assertEquals("com.example.CreateUser", projectConfig.commands().get("create-user").type());
        assertEquals(2, projectConfig.commands().get("create-user").effectiveRevision());
        assertEquals("Ada", projectConfig.commands().get("create-user").payload().path("name").asText());

        DevServerConfig overridden = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(),
                "--environment", "test",
                "--app", "system-api",
                "--port", "4300",
                "--idp", "managed",
                "--frontend-url", "http://localhost:5173",
                "--backend-path", "/rest"
        });
        assertEquals("test", overridden.environment());
        assertEquals(List.of("system-api"), overridden.applications());
        assertEquals(4300, overridden.gatewayPort());
        assertEquals(IdpMode.MANAGED, overridden.idpMode());
        assertEquals(FrontendConfig.Mode.EXTERNAL_URL, overridden.frontend().mode());
        assertEquals(List.of("/rest"), overridden.frontend().backendPaths());

        DevServerConfig backendOnly = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(),
                "--no-frontend"
        });
        assertEquals(FrontendConfig.Mode.NONE, backendOnly.frontend().mode());
        assertEquals(0, backendOnly.gatewayPort());
    }

    @Test
    void rejectsUnknownProjectConfigKeys(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                applicatons:
                  - app
                """);

        DevServerStartupException exception = assertThrows(
                DevServerStartupException.class,
                () -> DevServerConfig.fromArgs(new String[]{"--project-dir", projectDirectory.toString()}));
        assertTrue(exception.getMessage().contains("applicatons"));
    }

    @Test
    void rejectsFrontendSetupWithoutManagedCommand(@TempDir Path projectDirectory) {
        DevServerStartupException exception = assertThrows(
                DevServerStartupException.class,
                () -> DevServerConfig.fromArgs(new String[]{
                        "--project-dir", projectDirectory.toString(),
                        "--frontend-setup-command", "npm install"
                }));

        assertTrue(exception.getMessage().contains("require a managed frontend command"));
    }

    @Test
    void loadsNamedApplicationConfigurationsWithoutChangingDirectSelection(@TempDir Path projectDirectory)
            throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                apps:
                  - rebound-encrypted
                applicationConfig:
                  rebound-encrypted:
                    application: rebound
                    applicationName: Rebound
                    env:
                      FEATURE_MODE: encrypted
                    secrets:
                      ENCRYPTION_KEY: "op://Shared Vault/rebound/local key"
                """);

        DevServerConfig named = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});
        DevServerConfig.ApplicationSelection selection = named.applicationSelections().getFirst();
        assertEquals("rebound-encrypted", selection.id());
        assertEquals("rebound", selection.selector());
        assertEquals("Rebound", selection.applicationName());
        assertEquals(Map.of("FEATURE_MODE", "encrypted"), selection.env());
        assertEquals(Map.of("ENCRYPTION_KEY", "op://Shared Vault/rebound/local key"), selection.secrets());

        DevServerConfig direct = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--app", "rebound"
        });
        DevServerConfig.ApplicationSelection directSelection = direct.applicationSelections().getFirst();
        assertEquals("rebound", directSelection.id());
        assertEquals("rebound", directSelection.selector());
        assertTrue(directSelection.env().isEmpty());
        assertTrue(directSelection.secrets().isEmpty());
    }

    @Test
    void rejectsInvalidNamedApplicationSecrets(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                apps: [secure]
                applicationConfig:
                  secure:
                    application: app
                    secrets:
                      ENCRYPTION_KEY: plain-text-is-not-allowed
                """);

        DevServerStartupException exception = assertThrows(
                DevServerStartupException.class,
                () -> DevServerConfig.fromArgs(new String[]{"--project-dir", projectDirectory.toString()}));
        assertTrue(exception.getMessage().contains("must use an op:// reference"));
    }

    @Test
    void rejectsEnvironmentVariablesOwnedByTheSupervisor() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DevApplicationConfig("app", null,
                                               Map.of("FLUXZERO_BASE_URL", "ws://elsewhere"), Map.of()));
        assertTrue(exception.getMessage().contains("managed by the dev server"));
    }

    @Test
    void rejectsBackendPathsThatCaptureFrontendOrReservedGatewayPath() {
        assertThrows(IllegalArgumentException.class,
                     () -> FrontendConfig.command("npm run dev").withBackendPaths(List.of("/")));
        assertThrows(IllegalArgumentException.class,
                     () -> FrontendConfig.command("npm run dev").withBackendPaths(List.of("/_fluxzero/api")));
    }
}
