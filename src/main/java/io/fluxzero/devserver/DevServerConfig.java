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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the local Fluxzero dev server.
 *
 * @param projectDirectory        Maven or Gradle project directory
 * @param mainClass               optional application main class override; auto-detected when omitted
 * @param applicationName         Fluxzero application/client name for the app process
 * @param namespace               optional Fluxzero namespace/project id
 * @param watch                   whether source watching is enabled
 * @param compileOnStart          whether to compile and launch the app on startup
 * @param testsEnabled            whether background tests are enabled
 * @param startupTimeout          timeout for app readiness
 * @param gracefulShutdownTimeout timeout for old app shutdown
 * @param debounce                debounce duration for source/test changes
 * @param frontend                optional frontend adapter configuration
 * @param appArgs                 application arguments
 * @param fastCompilerEnabled     whether ordinary Java source changes may use the Maven-correct fast compiler
 * @param environment             Fluxzero application environment, for example {@code local} or {@code dev}
 * @param applications            optional reactor applications/modules to start; all discovered apps start when empty
 * @param gatewayPort             public frontend/backend gateway port; {@code 0} allocates a free dynamic port
 * @param idpMode                 managed local IDP or application-owned external IDP
 * @param applicationConfig       named per-application launch configurations loaded from project config
 */
public record DevServerConfig(
        Path projectDirectory,
        String mainClass,
        String applicationName,
        String namespace,
        boolean watch,
        boolean compileOnStart,
        boolean testsEnabled,
        Duration startupTimeout,
        Duration gracefulShutdownTimeout,
        Duration debounce,
        FrontendConfig frontend,
        List<String> appArgs,
        boolean fastCompilerEnabled,
        String environment,
        List<String> applications,
        int gatewayPort,
        IdpMode idpMode,
        Map<String, DevApplicationConfig> applicationConfig
) {
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(300);

    public DevServerConfig {
        projectDirectory = projectDirectory == null
                ? Path.of("").toAbsolutePath().normalize()
                : projectDirectory.toAbsolutePath().normalize();
        applicationName = applicationName == null || applicationName.isBlank()
                ? defaultApplicationName(projectDirectory) : applicationName;
        startupTimeout = startupTimeout == null ? DEFAULT_STARTUP_TIMEOUT : startupTimeout;
        gracefulShutdownTimeout = gracefulShutdownTimeout == null
                ? DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT : gracefulShutdownTimeout;
        debounce = debounce == null ? DEFAULT_DEBOUNCE : debounce;
        frontend = frontend == null ? FrontendConfig.none() : frontend;
        appArgs = appArgs == null ? List.of() : List.copyOf(appArgs);
        environment = environment == null || environment.isBlank() ? "local" : environment.strip();
        applications = applications == null ? List.of() : applications.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().toList();
        if (gatewayPort < 0 || gatewayPort > 65535) {
            throw new IllegalArgumentException("gatewayPort must be between 0 and 65535");
        }
        idpMode = idpMode == null ? IdpMode.MANAGED : idpMode;
        applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
        applicationConfig.forEach((id, value) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("applicationConfig keys must not be blank");
            }
            if (value == null || value.application() == null || value.application().isBlank()) {
                throw new IllegalArgumentException("applicationConfig." + id + ".application must be configured");
            }
        });
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
    }

    public DevServerConfig(
            Path projectDirectory,
            String mainClass,
            String applicationName,
            String namespace,
            boolean watch,
            boolean compileOnStart,
            boolean testsEnabled,
            Duration startupTimeout,
            Duration gracefulShutdownTimeout,
            Duration debounce,
            FrontendConfig frontend,
            List<String> appArgs,
            boolean fastCompilerEnabled
    ) {
        this(projectDirectory, mainClass, applicationName, namespace, watch, compileOnStart, testsEnabled,
             startupTimeout, gracefulShutdownTimeout, debounce, frontend, appArgs, fastCompilerEnabled,
             "local", List.of(), 0, IdpMode.MANAGED, Map.of());
    }

    public DevServerConfig(
            Path projectDirectory,
            String mainClass,
            String applicationName,
            String namespace,
            boolean watch,
            boolean compileOnStart,
            boolean testsEnabled,
            Duration startupTimeout,
            Duration gracefulShutdownTimeout,
            Duration debounce,
            FrontendConfig frontend,
            List<String> appArgs
    ) {
        this(projectDirectory, mainClass, applicationName, namespace, watch, compileOnStart, testsEnabled,
             startupTimeout, gracefulShutdownTimeout, debounce, frontend, appArgs, false, "local", List.of(), 0,
             IdpMode.MANAGED, Map.of());
    }

    public DevServerConfig(
            Path projectDirectory,
            String mainClass,
            String applicationName,
            String namespace,
            boolean watch,
            boolean compileOnStart,
            boolean testsEnabled,
            Duration startupTimeout,
            Duration gracefulShutdownTimeout,
            Duration debounce,
            FrontendConfig frontend,
            List<String> appArgs,
            boolean fastCompilerEnabled,
            String environment,
            List<String> applications,
            int gatewayPort,
            IdpMode idpMode
    ) {
        this(projectDirectory, mainClass, applicationName, namespace, watch, compileOnStart, testsEnabled,
             startupTimeout, gracefulShutdownTimeout, debounce, frontend, appArgs, fastCompilerEnabled,
             environment, applications, gatewayPort, idpMode, Map.of());
    }

    public static DevServerConfig defaults(Path projectDirectory) {
        return new DevServerConfig(projectDirectory, environment("FLUXZERO_MAIN_CLASS"),
                                   environment("FLUXZERO_APPLICATION_NAME"),
                                   environment("FLUXZERO_NAMESPACE"),
                                   true, true, true, DEFAULT_STARTUP_TIMEOUT,
                                   DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT, DEFAULT_DEBOUNCE,
                                   FrontendConfig.none(), List.of(), false);
    }

    public static DevServerConfig fromArgs(String[] args) {
        ParsedArgs parsed = ParsedArgs.parse(args);
        Path projectDirectory = parsed.path("project-dir", parsed.path("dir", Path.of("")));
        projectDirectory = projectDirectory.toAbsolutePath().normalize();
        DevProjectConfig project = DevProjectConfig.load(projectDirectory);
        boolean noFrontend = parsed.flag("no-frontend");
        String frontendCommand = parsed.value("frontend-command");
        String frontendUrl = parsed.value("frontend-url");
        String frontendDirectory = parsed.value("frontend-directory");
        String frontendSetupCommand = parsed.value("frontend-setup-command");
        if (!noFrontend && frontendCommand == null && frontendUrl == null) {
            frontendCommand = project.frontend().command();
            frontendUrl = project.frontend().url();
        }
        if (!noFrontend && frontendCommand != null) {
            frontendDirectory = firstNonBlank(frontendDirectory, project.frontend().directory());
            frontendSetupCommand = firstNonBlank(frontendSetupCommand, project.frontend().setupCommand());
        }
        if (!noFrontend && frontendCommand == null
            && (frontendDirectory != null || frontendSetupCommand != null)) {
            throw new DevServerStartupException(
                    "--frontend-directory and --frontend-setup-command require a managed frontend command");
        }
        FrontendConfig frontend = noFrontend ? FrontendConfig.none() : frontendCommand != null
                ? FrontendConfig.command(frontendCommand).withLaunchSetup(frontendDirectory, frontendSetupCommand)
                : frontendUrl == null ? FrontendConfig.none() : FrontendConfig.externalUrl(frontendUrl);
        List<String> backendPaths = parsed.values("backend-path");
        if (backendPaths.isEmpty()) {
            backendPaths = project.frontend().backendPaths();
        }
        if (!backendPaths.isEmpty()) {
            frontend = frontend.withBackendPaths(backendPaths);
        }
        List<String> applications = parsed.values("app");
        if (applications.isEmpty()) {
            applications = environmentList("FLUXZERO_DEV_APPS");
        }
        if (applications.isEmpty()) {
            applications = project.apps();
        }
        int gatewayPort = noFrontend ? 0 : parsed.integer("port", parsed.integer("gateway-port", environmentInteger(
                "FLUXZERO_DEV_PORT", project.port() == null ? 0 : project.port())));
        return new DevServerConfig(
                projectDirectory,
                parsed.value("main-class", firstNonBlank(environment("FLUXZERO_MAIN_CLASS"), project.mainClass())),
                parsed.value("application-name", firstNonBlank(
                        environment("FLUXZERO_APPLICATION_NAME"), project.applicationName())),
                parsed.value("namespace", firstNonBlank(environment("FLUXZERO_NAMESPACE"), project.namespace())),
                !parsed.flag("no-watch"),
                !parsed.flag("no-compile-on-start"),
                !parsed.flag("no-tests"),
                parsed.durationMillis("startup-timeout-ms", DEFAULT_STARTUP_TIMEOUT),
                parsed.durationMillis("graceful-shutdown-timeout-ms", DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT),
                parsed.durationMillis("debounce-ms", DEFAULT_DEBOUNCE),
                frontend,
                parsed.values("app-arg"),
                parsed.flag("fast-compiler") || "fast".equals(parsed.value("compile-mode"))
                || Boolean.TRUE.equals(project.fastCompiler()),
                parsed.value("environment", firstNonBlank(
                        environment("FLUXZERO_ENVIRONMENT"), project.environment(), "local")),
                applications,
                gatewayPort,
                parsed.flag("no-idp") ? IdpMode.EXTERNAL
                        : IdpMode.parse(parsed.value("idp", firstNonBlank(
                                environment("FLUXZERO_DEV_IDP"), project.idp(), "managed"))),
                project.applicationConfig());
    }

    List<ApplicationSelection> applicationSelections() {
        return applications.stream().map(id -> {
            DevApplicationConfig named = applicationConfig.get(id);
            return named == null
                    ? new ApplicationSelection(id, id, null, Map.of(), Map.of())
                    : new ApplicationSelection(id, named.application(), named.applicationName(),
                                               named.env(), named.secrets());
        }).toList();
    }

    record ApplicationSelection(String id, String selector, String applicationName,
                                Map<String, String> env, Map<String, String> secrets) {
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String environment(String name, String defaultValue) {
        String value = environment(name);
        return value == null ? defaultValue : value;
    }

    private static List<String> environmentList(String name) {
        String value = environment(name);
        return value == null ? List.of() : java.util.Arrays.stream(value.split(","))
                .map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private static int environmentInteger(String name, int defaultValue) {
        String value = environment(name);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String defaultApplicationName(Path projectDirectory) {
        Path fileName = projectDirectory.toAbsolutePath().normalize().getFileName();
        return fileName == null ? "fluxzero-dev-app" : fileName.toString();
    }

    private record ParsedArgs(Map<String, List<String>> values) {
        private static ParsedArgs parse(String[] args) {
            java.util.LinkedHashMap<String, List<String>> values = new java.util.LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                String option = arg.substring(2);
                String value = null;
                int separator = option.indexOf('=');
                if (separator >= 0) {
                    value = option.substring(separator + 1);
                    option = option.substring(0, separator);
                } else if (i + 1 < args.length && ("app-arg".equals(option) || !args[i + 1].startsWith("--"))) {
                    value = args[++i];
                }
                values.computeIfAbsent(option, ignored -> new ArrayList<>());
                if (value != null) {
                    values.get(option).add(value);
                }
            }
            return new ParsedArgs(values);
        }

        boolean flag(String name) {
            return values.containsKey(name) && values.get(name).isEmpty();
        }

        String value(String name) {
            return value(name, null);
        }

        String value(String name, String defaultValue) {
            List<String> optionValues = values.get(name);
            return optionValues == null || optionValues.isEmpty() ? defaultValue : optionValues.getLast();
        }

        List<String> values(String name) {
            return values.getOrDefault(name, List.of());
        }

        Path path(String name, Path defaultValue) {
            String value = value(name);
            return value == null ? defaultValue : Path.of(value);
        }

        Duration durationMillis(String name, Duration defaultValue) {
            String value = value(name);
            return value == null ? defaultValue : Duration.ofMillis(Long.parseLong(value));
        }

        int integer(String name, int defaultValue) {
            String value = value(name);
            return value == null ? defaultValue : Integer.parseInt(value);
        }
    }
}
