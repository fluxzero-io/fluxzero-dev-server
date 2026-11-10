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

import java.util.Map;
import java.util.regex.Pattern;

/** Environment overrides for the managed SDK Test Server and its embedded proxy. */
public record DevRuntimeConfig(Map<String, String> env) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public DevRuntimeConfig {
        if (env == null) {
            env = Map.of();
        }
        env.forEach((name, value) -> {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid runtime environment variable name");
            }
            if (value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("runtime environment values must be non-null and contain no NUL");
            }
        });
        env = Map.copyOf(env);
    }

    @Override
    public String toString() {
        return "DevRuntimeConfig[env=" + env.keySet() + "]";
    }
}
