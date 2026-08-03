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
        assertEquals(Duration.ofHours(24), config.idleTimeout());
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
                lifecycle:
                  idleTimeout: 4h
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
        assertEquals(Duration.ofHours(4), defaults.idleTimeout());
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
        assertEquals(List.of("/api", "/graphql", "/rest"), overridden.frontend().backendPaths());

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
    void parsesLifecycleDurationsAndRejectsInvalidValues(@TempDir Path projectDirectory) {
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(),
                "--idle-timeout", "30m"
        });

        assertEquals(Duration.ofMinutes(30), config.idleTimeout());
        assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--idle-timeout", "eventually"
        }));
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
    void loadsMultipleRoutedFrontendsAndAllowsSingleFrontendCliOverride(@TempDir Path projectDirectory)
            throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                port: 4200
                backendPaths: [/webhooks, /logs, /observer]
                frontends:
                  dashboard:
                    directory: frontend
                    command: "npm run dashboard -- --port {frontendPort}"
                  auditlog:
                    path: /marketplace/logs/1/
                    directory: ../fluxzero-auditlog/frontend
                    command: "npm run start-dashboard -- --port {frontendPort}"
                """);

        DevServerConfig config = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});

        assertEquals(List.of("dashboard", "auditlog"), config.frontends().stream().map(RoutedFrontend::id).toList());
        assertEquals(List.of("/", "/marketplace/logs/1"),
                     config.frontends().stream().map(RoutedFrontend::path).toList());
        assertEquals(4200, config.gatewayPort());
        assertEquals("npm run dashboard -- --port {frontendPort}", config.frontend().command());
        assertEquals(List.of("/api", "/webhooks", "/logs", "/observer"),
                     config.frontend().backendPaths());

        DevServerConfig overridden = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--frontend-url", "http://localhost:5173"
        });
        assertEquals(1, overridden.frontends().size());
        assertEquals("frontend", overridden.frontends().getFirst().id());
        assertEquals("http://localhost:5173", overridden.frontend().url());

        DevServerConfig disabled = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--no-frontend"
        });
        assertTrue(disabled.frontends().isEmpty());
    }

    @Test
    void rejectsAmbiguousOrInvalidFrontendRoutes(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                frontend:
                  command: npm start
                frontends:
                  dashboard:
                    path: /
                    command: npm start
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains("cannot both"));

        Files.writeString(configFile, """
                version: 1
                frontends:
                  dashboard:
                    path: /dashboard
                    command: npm start
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains("mounted at /"));

        Files.writeString(configFile, """
                version: 1
                frontends:
                  dashboard:
                    path: /
                    command: npm start
                  duplicate:
                    path: /
                    url: http://localhost:5173
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains("duplicates"));

        Files.writeString(configFile, """
                version: 1
                frontends:
                  dashboard:
                    path: /
                    url: http://localhost:5173
                    directory: frontend
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains(
                "directory and frontend.setupCommand require frontend.command"));
    }

    @Test
    void selectsDefaultAndExplicitDevelopmentProfiles(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                defaultProfile: encrypted
                profiles:
                  encrypted:
                    environment: local
                    apps: [rebound-encrypted]
                    applicationConfig:
                      rebound-encrypted:
                        application: rebound
                        env:
                          SPRING_PROFILES_ACTIVE: main
                        secrets:
                          ENCRYPTION_KEY: "op://Shared Vault/rebound/local key"
                    gatewayPort: 4200
                    idp: external
                    backendPaths: [/graphql]
                    frontend:
                      directory: frontend
                      command: "npm start -- --port {frontendPort}"
                  reporting:
                    environment: reporting
                    apps: [rebound, reporting]
                    port: 4300
                    frontend:
                      directory: reporting-ui
                      command: "npm run reporting -- --port {frontendPort}"
                """);

        DevServerConfig defaults = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});
        assertEquals("encrypted", defaults.profile());
        assertEquals(List.of("rebound-encrypted"), defaults.applications());
        assertEquals("frontend", defaults.frontend().directory());
        assertEquals(4200, defaults.gatewayPort());
        assertEquals(IdpMode.EXTERNAL, defaults.idpMode());
        assertEquals(List.of("/api", "/graphql"), defaults.frontend().backendPaths());

        DevServerConfig reporting = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--profile", "reporting"
        });
        assertEquals("reporting", reporting.profile());
        assertEquals("reporting", reporting.environment());
        assertEquals(List.of("rebound", "reporting"), reporting.applications());
        assertEquals("reporting-ui", reporting.frontend().directory());
        assertEquals(4300, reporting.gatewayPort());
    }

    @Test
    void loadsIndependentBuildProjectsAndPerApplicationNamespaces(@TempDir Path projectDirectory) throws Exception {
        Path auditlog = projectDirectory.resolveSibling("auditlog-backend");
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                environment: local
                projects:
                  dashboard:
                    directory: .
                    apps: [rebound]
                  auditlog:
                    directory: ../auditlog-backend
                    apps: [auditlog-local]
                    applicationConfig:
                      auditlog-local:
                        application: auditlog
                        namespace: fluxzero_mp_prod-logs
                        env:
                          TARGET_NAMESPACE: fluxzero_mp_prod-logs
                """);

        DevServerConfig config = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});

        assertEquals(List.of("dashboard", "auditlog"), config.projects().stream().map(DevBuildProject::id).toList());
        assertEquals(projectDirectory.toAbsolutePath(), config.projects().getFirst().directory());
        assertEquals(auditlog.toAbsolutePath(), config.projects().get(1).directory());
        DevServerConfig auditlogConfig = config.forProject(config.projects().get(1));
        assertEquals(List.of("auditlog-local"), auditlogConfig.applications());
        assertEquals("fluxzero_mp_prod-logs", auditlogConfig.applicationSelections().getFirst().namespace());
        assertEquals("fluxzero_mp_prod-logs",
                     auditlogConfig.applicationSelections().getFirst().env().get("TARGET_NAMESPACE"));

        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--app", "rebound"
        })).getMessage().contains("configured per project"));
    }

    @Test
    void rejectsMixedOrDuplicateBuildProjectConfiguration(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                apps: [rebound]
                projects:
                  dashboard:
                    directory: .
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains(
                "projects cannot be combined"));

        Files.writeString(configFile, """
                version: 1
                projects:
                  dashboard:
                    directory: .
                  duplicate:
                    directory: ./
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains(
                "duplicates"));
    }

    @Test
    void preservesLegacyConfigurationAndAutoSelectsASingleProfile(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                apps: [app]
                frontend:
                  command: "npm start -- --port {port}"
                """);

        DevServerConfig legacy = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});
        assertEquals(null, legacy.profile());
        assertEquals(List.of("app"), legacy.applications());

        Files.writeString(configFile, """
                version: 1
                profiles:
                  only:
                    apps: [worker]
                """);
        DevServerConfig only = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});
        assertEquals("only", only.profile());
        assertEquals(List.of("worker"), only.applications());
    }

    @Test
    void rejectsAmbiguousUnknownAndMixedDevelopmentProfiles(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                profiles:
                  app:
                    apps: [app]
                  worker:
                    apps: [worker]
                """);

        DevServerStartupException ambiguous = assertThrows(
                DevServerStartupException.class,
                () -> DevServerConfig.fromArgs(new String[]{"--project-dir", projectDirectory.toString()}));
        assertTrue(ambiguous.getMessage().contains("--profile"));
        assertTrue(ambiguous.getMessage().contains("app, worker"));

        DevServerStartupException unknown = assertThrows(
                DevServerStartupException.class,
                () -> DevServerConfig.fromArgs(new String[]{
                        "--project-dir", projectDirectory.toString(), "--profile", "missing"
                }));
        assertTrue(unknown.getMessage().contains("Unknown development profile 'missing'"));

        Files.writeString(configFile, """
                version: 1
                apps: [legacy]
                defaultProfile: app
                profiles:
                  app:
                    apps: [app]
                """);
        DevServerStartupException mixed = assertThrows(
                DevServerStartupException.class,
                () -> DevServerConfig.fromArgs(new String[]{"--project-dir", projectDirectory.toString()}));
        assertTrue(mixed.getMessage().contains("cannot be combined"));
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
    void loadsManagedAndExternalServicesWithNamedPorts(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                services:
                  victoriaLogs:
                    directory: local/victoria
                    command: docker compose up
                    stopCommand: docker compose down --remove-orphans
                    ports:
                      http: dynamic
                      metrics: 19428
                    url: "http://127.0.0.1:{port.http}"
                    env:
                      COMPOSE_PROJECT_NAME: "fluxzero-{session.id}-victoria"
                    readiness:
                      http: "{url}/health"
                      timeout: 3m
                  mail:
                    url: http://127.0.0.1:8025
                """);

        DevServerConfig config = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});

        assertEquals(List.of("victoriaLogs", "mail"), List.copyOf(config.services().keySet()));
        DevServiceConfig victoriaLogs = config.services().get("victoriaLogs");
        assertTrue(victoriaLogs.managed());
        assertEquals(Map.of("http", 0, "metrics", 19428), victoriaLogs.ports());
        assertEquals("http://127.0.0.1:{port.http}", victoriaLogs.url());
        assertEquals("{url}/health", victoriaLogs.readiness().http());
        assertEquals(Duration.ofMinutes(3), victoriaLogs.readiness().timeout());
        assertFalse(config.services().get("mail").managed());
        assertEquals("http://127.0.0.1:8025", config.services().get("mail").readiness().http());
    }

    @Test
    void keepsServicesProfileScopedAndRejectsInvalidServiceShapes(@TempDir Path projectDirectory) throws Exception {
        Path configFile = projectDirectory.resolve(DevProjectConfig.FILE);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                version: 1
                defaultProfile: managed
                profiles:
                  managed:
                    services:
                      database:
                        command: start-db --port {port.sql}
                        ports:
                          sql: dynamic
                        readiness:
                          tcp: "127.0.0.1:{port.sql}"
                  external:
                    services:
                      database:
                        url: http://localhost:5432
                """);

        DevServerConfig managed = DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()});
        assertEquals("managed", managed.profile());
        assertEquals(0, managed.services().get("database").ports().get("sql"));

        DevServerConfig external = DevServerConfig.fromArgs(new String[]{
                "--project-dir", projectDirectory.toString(), "--profile", "external"
        });
        assertFalse(external.services().get("database").managed());

        Files.writeString(configFile, """
                version: 1
                services:
                  broken:
                    stopCommand: stop-it
                    url: http://localhost:1234
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains(
                "require service.command"));

        Files.writeString(configFile, """
                version: 1
                services:
                  broken:
                    command: start-it
                    ports:
                      http: eventually
                    readiness:
                      tcp: "127.0.0.1:{port.http}"
                """);
        assertTrue(assertThrows(DevServerStartupException.class, () -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", projectDirectory.toString()})).getMessage().contains(
                "must be dynamic or a port number"));
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
