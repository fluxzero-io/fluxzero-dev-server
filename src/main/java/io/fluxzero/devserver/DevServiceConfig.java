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

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Configuration for a managed command service or an externally supplied local service. */
public record DevServiceConfig(
        String command,
        String stopCommand,
        String url,
        String directory,
        Map<String, Integer> ports,
        Map<String, String> environment,
        Readiness readiness,
        List<Pattern> redact
) {
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(2);

    public DevServiceConfig {
        ports = ports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ports));
        environment = environment == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        redact = redact == null ? List.of() : List.copyOf(redact);
        readiness = readiness == null ? new Readiness(null, null, DEFAULT_STARTUP_TIMEOUT) : readiness;
    }

    public DevServiceConfig(String command, String stopCommand, String url, String directory,
                            Map<String, Integer> ports, Map<String, String> environment, Readiness readiness) {
        this(command, stopCommand, url, directory, ports, environment, readiness, List.of());
    }

    boolean managed() {
        return command != null;
    }

    static Pattern compilePattern(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new DevServerStartupException(field + " must not be blank");
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new DevServerStartupException(field + " must be a valid regular expression: " + e.getDescription());
        }
    }

    /** Protocol-independent readiness configuration. Log patterns are compiled before process startup. */
    public record Readiness(String http, String tcp, Pattern log, Duration timeout) {
        public Readiness(String http, String tcp, Duration timeout) {
            this(http, tcp, null, timeout);
        }

        public Readiness {
            timeout = timeout == null ? DEFAULT_STARTUP_TIMEOUT : timeout;
        }
    }
}
