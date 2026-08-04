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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record DevProjectConfig(
        Integer version,
        String mainClass,
        String applicationName,
        String namespace,
        String environment,
        List<String> apps,
        Map<String, DevApplicationConfig> applicationConfig,
        Map<String, Project> projects,
        @JsonAlias("gatewayPort") Integer port,
        String idp,
        Boolean fastCompiler,
        List<String> backendPaths,
        Frontend frontend,
        Map<String, Frontend> frontends,
        Map<String, Service> services,
        Lifecycle lifecycle,
        @JsonDeserialize(using = DevCommandsDeserializer.class) Map<String, DevCommandConfig> commands,
        String defaultProfile,
        Map<String, Profile> profiles
) {
    static final Path FILE = Path.of(".fluxzero", "dev.yaml");
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    DevProjectConfig {
        apps = apps == null ? List.of() : List.copyOf(apps);
        applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
        projects = projects == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(projects));
        validateProjects(projects);
        validateProjectShape(mainClass, applicationName, namespace, apps, applicationConfig, fastCompiler, projects);
        backendPaths = backendPaths == null ? List.of() : List.copyOf(backendPaths);
        frontend = frontend == null ? new Frontend(null, null, null, null, List.of()) : frontend;
        frontends = frontends == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(frontends));
        validateFrontends(frontend, frontends);
        services = services == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(services));
        validateServices(services);
        lifecycle = lifecycle == null ? new Lifecycle(null) : lifecycle;
        commands = commands == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(commands));
        validateCommands(commands, "commands");
        profiles = profiles == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        profiles.forEach((id, profile) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("profiles keys must not be blank");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profiles." + id + " must be configured");
            }
        });
        if (profiles.isEmpty()) {
            if (defaultProfile != null && !defaultProfile.isBlank()) {
                throw new IllegalArgumentException("defaultProfile requires profiles");
            }
        } else {
            if (legacyConfigurationPresent(mainClass, applicationName, namespace, environment, apps,
                                           applicationConfig, projects, port, idp, fastCompiler, backendPaths,
                                           frontend, frontends, services, lifecycle,
                                           commands)) {
                throw new IllegalArgumentException(
                        "profiles cannot be combined with legacy top-level development settings");
            }
            if (defaultProfile != null && !defaultProfile.isBlank() && !profiles.containsKey(defaultProfile)) {
                throw new IllegalArgumentException("defaultProfile '" + defaultProfile
                                                   + "' does not match a configured profile");
            }
        }
    }

    static DevProjectConfig load(Path projectDirectory) {
        Path file = projectDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            DevProjectConfig config = MAPPER.readValue(file.toFile(), DevProjectConfig.class);
            if (config.version() == null || config.version() != 1) {
                throw new DevServerStartupException(file + " must declare version: 1");
            }
            return config;
        } catch (DevServerStartupException e) {
            throw e;
        } catch (Exception e) {
            throw new DevServerStartupException("Could not read " + file + ": " + rootMessage(e), e);
        }
    }

    private static DevProjectConfig empty() {
        return new DevProjectConfig(1, null, null, null, null, List.of(), Map.of(), Map.of(), null, null, null, null,
                                    null, Map.of(), Map.of(), null, Map.of(), null, Map.of());
    }

    Selection select(String requestedProfile) {
        String requested = requestedProfile == null || requestedProfile.isBlank() ? null : requestedProfile.strip();
        if (profiles.isEmpty()) {
            if (requested != null) {
                throw new DevServerStartupException(
                        "Profile '" + requested + "' was requested, but " + FILE + " does not define profiles");
            }
            return new Selection(null, this);
        }
        String selected = requested;
        if (selected == null && defaultProfile != null && !defaultProfile.isBlank()) {
            selected = defaultProfile;
        }
        if (selected == null && profiles.size() == 1) {
            selected = profiles.keySet().iterator().next();
        }
        if (selected == null) {
            throw new DevServerStartupException(
                    "Select a development profile with --profile. Available profiles: "
                    + String.join(", ", profiles.keySet()));
        }
        Profile profile = profiles.get(selected);
        if (profile == null) {
            throw new DevServerStartupException(
                    "Unknown development profile '" + selected + "'. Available profiles: "
                    + String.join(", ", profiles.keySet()));
        }
        return new Selection(selected, profile.toProjectConfig(version));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    record Frontend(String command, String url, String directory, String setupCommand, List<String> backendPaths,
                    String path) {
        Frontend(String command, String url, String directory, String setupCommand, List<String> backendPaths) {
            this(command, url, directory, setupCommand, backendPaths, null);
        }

        Frontend {
            backendPaths = backendPaths == null ? List.of() : List.copyOf(backendPaths);
            path = normalizePath(path);
            if (command != null && !command.isBlank() && url != null && !url.isBlank()) {
                throw new IllegalArgumentException("frontend.command and frontend.url cannot both be configured");
            }
            if (((directory != null && !directory.isBlank()) || (setupCommand != null && !setupCommand.isBlank()))
                && (command == null || command.isBlank())) {
                throw new IllegalArgumentException(
                        "frontend.directory and frontend.setupCommand require frontend.command");
            }
            if (path != null && (command == null || command.isBlank()) && (url == null || url.isBlank())) {
                throw new IllegalArgumentException("frontend.path requires frontend.command or frontend.url");
            }
        }

        boolean configured() {
            return command != null || url != null || directory != null || setupCommand != null
                   || !backendPaths.isEmpty() || path != null;
        }
    }

    record Lifecycle(String idleTimeout) {
    }

    record Service(
            String command,
            String stopCommand,
            String url,
            String directory,
            Map<String, String> ports,
            Map<String, String> env,
            Readiness readiness
    ) {
        private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        private static final Pattern PORT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

        Service {
            command = normalize(command);
            stopCommand = normalize(stopCommand);
            url = normalize(url);
            directory = normalize(directory);
            ports = ports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ports));
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
            readiness = readiness == null ? new Readiness(null, null, null) : readiness;
            if (command == null && url == null) {
                throw new IllegalArgumentException("service must configure command or url");
            }
            if (command == null && (stopCommand != null || directory != null || !ports.isEmpty() || !env.isEmpty())) {
                throw new IllegalArgumentException(
                        "service stopCommand, directory, ports and env require service.command");
            }
            ports.forEach((name, value) -> {
                if (name == null || !PORT_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid service port name: " + name);
                }
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("service port " + name + " must be dynamic or a port number");
                }
                if (!"dynamic".equalsIgnoreCase(value.strip())) {
                    try {
                        int port = Integer.parseInt(value.strip());
                        if (port < 1 || port > 65535) {
                            throw new IllegalArgumentException(
                                    "service port " + name + " must be between 1 and 65535");
                        }
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "service port " + name + " must be dynamic or a port number");
                    }
                }
            });
            env.forEach((name, value) -> {
                if (name == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid service environment variable name: " + name);
                }
                if (value == null || value.contains("\n") || value.contains("\r")) {
                    throw new IllegalArgumentException(
                            "service environment value for " + name + " must be a single line");
                }
            });
            if (url == null && !readiness.configured()) {
                throw new IllegalArgumentException("managed service must configure url or readiness");
            }
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    record Readiness(String http, String tcp, String timeout) {
        Readiness {
            http = normalize(http);
            tcp = normalize(tcp);
            timeout = normalize(timeout);
            if (http != null && tcp != null) {
                throw new IllegalArgumentException("service readiness must configure either http or tcp");
            }
        }

        boolean configured() {
            return http != null || tcp != null;
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    record Project(
            String directory,
            String mainClass,
            String applicationName,
            String namespace,
            List<String> apps,
            Map<String, DevApplicationConfig> applicationConfig,
            Boolean fastCompiler
    ) {
        Project {
            directory = directory == null || directory.isBlank() ? "." : directory.strip();
            apps = apps == null ? List.of() : List.copyOf(apps);
            applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
        }
    }

    record Profile(
            String mainClass,
            String applicationName,
            String namespace,
            String environment,
            List<String> apps,
            Map<String, DevApplicationConfig> applicationConfig,
            Map<String, Project> projects,
            @JsonAlias("gatewayPort") Integer port,
            String idp,
            Boolean fastCompiler,
            List<String> backendPaths,
            Frontend frontend,
            Map<String, Frontend> frontends,
            Map<String, Service> services,
            Lifecycle lifecycle,
            @JsonDeserialize(using = DevCommandsDeserializer.class) Map<String, DevCommandConfig> commands
    ) {
        Profile {
            apps = apps == null ? List.of() : List.copyOf(apps);
            applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
            projects = projects == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(projects));
            validateProjects(projects);
            validateProjectShape(mainClass, applicationName, namespace, apps, applicationConfig, fastCompiler,
                                 projects);
            backendPaths = backendPaths == null ? List.of() : List.copyOf(backendPaths);
            frontend = frontend == null ? new Frontend(null, null, null, null, List.of()) : frontend;
            frontends = frontends == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(frontends));
            validateFrontends(frontend, frontends);
            services = services == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(services));
            validateServices(services);
            lifecycle = lifecycle == null ? new Lifecycle(null) : lifecycle;
            commands = commands == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(commands));
            validateCommands(commands, "commands");
        }

        private DevProjectConfig toProjectConfig(Integer version) {
            return new DevProjectConfig(version, mainClass, applicationName, namespace, environment, apps,
                                        applicationConfig, projects, port, idp, fastCompiler, backendPaths,
                                        frontend, frontends, services, lifecycle, commands, null, Map.of());
        }
    }

    record Selection(String profile, DevProjectConfig config) {
    }

    private static void validateCommands(Map<String, DevCommandConfig> commands, String path) {
        commands.forEach((id, command) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(path + " keys must not be blank");
            }
            if (command == null) {
                throw new IllegalArgumentException(path + "." + id + " must be configured");
            }
            if (command.fileReference()) {
                if (command.type() != null || command.revision() != null || command.payload() != null
                    || !command.metadata().isEmpty()) {
                    throw new IllegalArgumentException(path + "." + id
                                                       + " cannot combine file with an inline command definition");
                }
            } else if (command.type() == null || command.type().isBlank()) {
                throw new IllegalArgumentException(path + "." + id + ".type must be configured");
            }
        });
    }

    private static boolean legacyConfigurationPresent(
            String mainClass, String applicationName, String namespace, String environment, List<String> apps,
            Map<String, DevApplicationConfig> applicationConfig, Map<String, Project> projects,
            Integer port, String idp, Boolean fastCompiler, List<String> backendPaths,
            Frontend frontend, Map<String, Frontend> frontends, Map<String, Service> services, Lifecycle lifecycle,
            Map<String, DevCommandConfig> commands
    ) {
        return mainClass != null || applicationName != null || namespace != null || environment != null
               || !apps.isEmpty() || !applicationConfig.isEmpty() || port != null || idp != null
               || !projects.isEmpty() || fastCompiler != null || frontend.configured() || !frontends.isEmpty()
               || !backendPaths.isEmpty() || !services.isEmpty()
               || lifecycle.idleTimeout() != null
               || !commands.isEmpty();
    }

    private static void validateProjects(Map<String, Project> projects) {
        Map<Path, String> directories = new LinkedHashMap<>();
        projects.forEach((id, project) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("projects keys must not be blank");
            }
            if (project == null) {
                throw new IllegalArgumentException("projects." + id + " must be configured");
            }
            Path directory = Path.of(project.directory()).normalize();
            String previous = directories.putIfAbsent(directory, id);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "projects." + id + ".directory duplicates projects." + previous + ".directory: "
                        + project.directory());
            }
        });
    }

    private static void validateProjectShape(
            String mainClass, String applicationName, String namespace, List<String> apps,
            Map<String, DevApplicationConfig> applicationConfig, Boolean fastCompiler, Map<String, Project> projects
    ) {
        if (!projects.isEmpty() && (mainClass != null || applicationName != null || namespace != null
                                    || !apps.isEmpty() || !applicationConfig.isEmpty() || fastCompiler != null)) {
            throw new IllegalArgumentException(
                    "projects cannot be combined with mainClass, applicationName, namespace, apps, "
                    + "applicationConfig or fastCompiler");
        }
    }

    private static void validateFrontends(Frontend legacy, Map<String, Frontend> frontends) {
        if (legacy.configured() && !frontends.isEmpty()) {
            throw new IllegalArgumentException("frontend and frontends cannot both be configured");
        }
        Map<String, String> paths = new LinkedHashMap<>();
        frontends.forEach((id, frontend) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("frontends keys must not be blank");
            }
            if (frontend == null || !frontend.configured()) {
                throw new IllegalArgumentException("frontends." + id + " must configure command or url");
            }
            String path = frontend.path() == null ? "/" : frontend.path();
            String previous = paths.putIfAbsent(path, id);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "frontends." + id + ".path duplicates frontends." + previous + ".path: " + path);
            }
        });
        if (!frontends.isEmpty() && !paths.containsKey("/")) {
            throw new IllegalArgumentException("frontends must contain exactly one frontend mounted at /");
        }
    }

    private static void validateServices(Map<String, Service> services) {
        Set<String> ids = new java.util.HashSet<>();
        services.forEach((id, service) -> {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("services keys must be non-blank identifiers");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate service id: " + id);
            }
            if (service == null) {
                throw new IllegalArgumentException("services." + id + " must be configured");
            }
        });
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String path = value.strip();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("frontend path must not contain a query or fragment: " + value);
        }
        if (path.equals(DevGateway.BACKEND_PREFIX) || path.startsWith(DevGateway.BACKEND_PREFIX + "/")) {
            throw new IllegalArgumentException(DevGateway.BACKEND_PREFIX + " is reserved by the dev gateway");
        }
        return path;
    }
}
