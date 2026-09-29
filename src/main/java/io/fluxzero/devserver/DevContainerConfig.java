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

import java.util.List;
import java.util.Map;

/** Declarative container configuration for Docker-compatible runtime CLIs. */
public record DevContainerConfig(String image, String runtime, String pull, Map<String, Integer> ports,
                                 List<Mount> mounts, String network, String hostGateway, String user,
                                 Boolean readOnly, List<String> capDrop, List<String> securityOpt, List<String> command,
                                 String driver, Boolean registryInsecure) {
    public DevContainerConfig {
        if (image == null || !image.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("container.image must be a digest-pinned image reference (repository@sha256:...)");
        runtime = runtime == null ? "docker" : runtime;
        if (runtime.isBlank() || runtime.startsWith("-") || runtime.contains("\n") || runtime.contains("\r"))
            throw new IllegalArgumentException("container.runtime must name a runtime executable");
        driver = driver == null ? (java.nio.file.Path.of(runtime).getFileName().toString().startsWith("podman") ? "podman" : "docker") : driver;
        if (!List.of("docker", "podman").contains(driver)) throw new IllegalArgumentException("container.driver must be docker or podman");
        pull = pull == null ? "always" : pull;
        if (!List.of("always", "verify", "if-missing", "never").contains(pull))
            throw new IllegalArgumentException("container.pull must be always, verify, if-missing or never");
        ports = ports == null ? Map.of() : Map.copyOf(ports);
        ports.forEach((name, port) -> {
            if (!name.matches("[A-Za-z][A-Za-z0-9_-]*") || port == null || port < 1 || port > 65535)
                throw new IllegalArgumentException("container ports must map service port names to container port numbers");
        });
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
        if (network != null && !network.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))
            throw new IllegalArgumentException("container.network must name an existing network");
        hostGateway = hostGateway == null ? "host-gateway" : hostGateway;
        if (!hostGateway.matches("[a-zA-Z0-9:._-]+")) throw new IllegalArgumentException("Invalid container.hostGateway");
        capDrop = capDrop == null ? List.of() : List.copyOf(capDrop);
        securityOpt = securityOpt == null ? List.of() : List.copyOf(securityOpt);
        command = command == null ? List.of() : List.copyOf(command);
    }

    public record Mount(String source, String target, Boolean readOnly) {
        public Mount {
            if (source == null || source.isBlank() || source.contains(",") || target == null
                || !target.startsWith("/") || target.contains(","))
                throw new IllegalArgumentException("container mounts require a source and absolute container target without commas");
        }
    }
}
