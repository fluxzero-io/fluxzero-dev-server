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
        Map<String, DevCommandConfig> commands
) {
    static final Path FILE = Path.of(".fluxzero", "dev.yaml");
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    DevProjectConfig {
        apps = apps == null ? List.of() : List.copyOf(apps);
        applicationConfig = applicationConfig == null ? Map.of() : Map.copyOf(applicationConfig);
        frontend = frontend == null ? new Frontend(null, null, null, null, List.of()) : frontend;
        commands = commands == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(commands));
        commands.forEach((id, command) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("commands keys must not be blank");
            }
            if (command == null || command.type() == null || command.type().isBlank()) {
                throw new IllegalArgumentException("commands." + id + ".type must be configured");
            }
        });
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
        return new DevProjectConfig(1, null, null, null, null, List.of(), Map.of(), null, null, null, null, Map.of());
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
    }
}
