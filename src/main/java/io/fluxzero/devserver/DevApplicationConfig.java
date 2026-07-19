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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Project-shared launch configuration for one Fluxzero application flavor.
 *
 * @param application     application selector, such as a module, simple main class, or fully qualified main class
 * @param applicationName optional Fluxzero runtime application name override
 * @param env             non-secret environment values passed to the application
 * @param secrets         environment variable names mapped to 1Password {@code op://} references
 */
public record DevApplicationConfig(
        String application,
        String applicationName,
        Map<String, String> env,
        Map<String, String> secrets
) {
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SUPERVISOR_ENVIRONMENT = Set.of(
            "ENVIRONMENT", "FLUXZERO_BASE_URL", "FLUX_BASE_URL", "FLUX_PORT",
            "FLUXZERO_APPLICATION_NAME", "FLUX_APPLICATION_NAME", "FLUXZERO_PROXY_URL", "PROXY_PORT",
            "FLUXZERO_DEV_SESSION_ID", "FLUXZERO_TASK_ID", "FLUX_TASK_ID", "FLUXZERO_NAMESPACE");

    public DevApplicationConfig {
        application = application == null ? null : application.strip();
        applicationName = applicationName == null || applicationName.isBlank() ? null : applicationName.strip();
        env = copyAndValidate(env, false);
        secrets = copyAndValidate(secrets, true);
        for (String name : env.keySet()) {
            if (secrets.containsKey(name)) {
                throw new IllegalArgumentException("environment variable " + name
                                                   + " cannot be declared in both env and secrets");
            }
        }
    }

    @Override
    public String toString() {
        return "DevApplicationConfig[application=" + application + ", applicationName=" + applicationName
               + ", env=" + env.keySet() + ", secrets=" + secrets.keySet() + "]";
    }

    private static Map<String, String> copyAndValidate(Map<String, String> values, boolean secret) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (name == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid environment variable name: " + name);
            }
            if (SUPERVISOR_ENVIRONMENT.contains(name)) {
                throw new IllegalArgumentException("environment variable " + name + " is managed by the dev server");
            }
            if (value == null || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("environment value for " + name + " must be a single line");
            }
            if (secret && !value.startsWith("op://")) {
                throw new IllegalArgumentException("secret " + name + " must use an op:// reference");
            }
            result.put(name, value);
        });
        return Map.copyOf(result);
    }
}
