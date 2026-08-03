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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DevProjectConfig(
        Integer version,
        String mainClass,
        String applicationName,
        String namespace,
        String environment,
        List<String> apps,
        Map<String, DevApplicationConfig> applicationConfig,
        Integer port,
        String idp,
        Boolean fastCompiler,
        Frontend frontend,
        Lifecycle lifecycle,
        Map<String, DevCommandConfig> commands,
        String defaultProfile,
        Map<String, Profile> profiles
) {
    static final Path FILE = Path.of(".fluxzero", "dev.yaml");
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    DevProjectConfig {
        apps = apps == null ? List.of() : List.copyOf(apps);
        applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
        frontend = frontend == null ? new Frontend(null, null, null, null, List.of()) : frontend;
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
                                           applicationConfig, port, idp, fastCompiler, frontend, lifecycle,
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
        return new DevProjectConfig(1, null, null, null, null, List.of(), Map.of(), null, null, null, null, null,
                                    Map.of(), null, Map.of());
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

    record Frontend(String command, String url, String directory, String setupCommand, List<String> backendPaths) {
        Frontend {
            backendPaths = backendPaths == null ? List.of() : List.copyOf(backendPaths);
            if (command != null && !command.isBlank() && url != null && !url.isBlank()) {
                throw new IllegalArgumentException("frontend.command and frontend.url cannot both be configured");
            }
            if ((directory != null && !directory.isBlank()) || (setupCommand != null && !setupCommand.isBlank())) {
                if (command == null || command.isBlank()) {
                    throw new IllegalArgumentException(
                            "frontend.directory and frontend.setupCommand require frontend.command");
                }
            }
        }

        boolean configured() {
            return command != null || url != null || directory != null || setupCommand != null
                   || !backendPaths.isEmpty();
        }
    }

    record Lifecycle(String idleTimeout) {
    }

    record Profile(
            String mainClass,
            String applicationName,
            String namespace,
            String environment,
            List<String> apps,
            Map<String, DevApplicationConfig> applicationConfig,
            Integer port,
            String idp,
            Boolean fastCompiler,
            Frontend frontend,
            Lifecycle lifecycle,
            Map<String, DevCommandConfig> commands
    ) {
        Profile {
            apps = apps == null ? List.of() : List.copyOf(apps);
            applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
            frontend = frontend == null ? new Frontend(null, null, null, null, List.of()) : frontend;
            lifecycle = lifecycle == null ? new Lifecycle(null) : lifecycle;
            commands = commands == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(commands));
            validateCommands(commands, "commands");
        }

        private DevProjectConfig toProjectConfig(Integer version) {
            return new DevProjectConfig(version, mainClass, applicationName, namespace, environment, apps,
                                        applicationConfig, port, idp, fastCompiler, frontend, lifecycle, commands,
                                        null, Map.of());
        }
    }

    record Selection(String profile, DevProjectConfig config) {
    }

    private static void validateCommands(Map<String, DevCommandConfig> commands, String path) {
        commands.forEach((id, command) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(path + " keys must not be blank");
            }
            if (command == null || command.type() == null || command.type().isBlank()) {
                throw new IllegalArgumentException(path + "." + id + ".type must be configured");
            }
        });
    }

    private static boolean legacyConfigurationPresent(
            String mainClass, String applicationName, String namespace, String environment, List<String> apps,
            Map<String, DevApplicationConfig> applicationConfig, Integer port, String idp, Boolean fastCompiler,
            Frontend frontend, Lifecycle lifecycle, Map<String, DevCommandConfig> commands
    ) {
        return mainClass != null || applicationName != null || namespace != null || environment != null
               || !apps.isEmpty() || !applicationConfig.isEmpty() || port != null || idp != null
               || fastCompiler != null || frontend.configured() || lifecycle.idleTimeout() != null
               || !commands.isEmpty();
    }
}
