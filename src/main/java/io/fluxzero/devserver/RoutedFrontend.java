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

import java.util.Objects;

record RoutedFrontend(String id, String path, FrontendConfig config) {
    RoutedFrontend {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("frontend id must not be blank");
        }
        id = id.strip();
        path = normalizePath(path);
        config = Objects.requireNonNull(config, "frontend config must not be null");
        if (config.mode() == FrontendConfig.Mode.NONE) {
            throw new IllegalArgumentException("routed frontend must configure a command or URL");
        }
    }

    private static String normalizePath(String value) {
        String result = value == null || value.isBlank() ? "/" : value.strip();
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
