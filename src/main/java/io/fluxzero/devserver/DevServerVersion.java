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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class DevServerVersion {
    private DevServerVersion() {
    }

    static String current() {
        String version = DevServerVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    static String sdkVersion() {
        return buildProperty("fluxzero.sdk.version");
    }

    static String logbackVersion() {
        return buildProperty("logback.version");
    }

    private static String buildProperty(String key) {
        try (InputStream input = DevServerVersion.class.getResourceAsStream("version.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing Dev Server version metadata");
            }
            Properties properties = new Properties();
            properties.load(input);
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing Dev Server build property " + key);
            }
            return value.strip();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read Dev Server version metadata", e);
        }
    }
}
