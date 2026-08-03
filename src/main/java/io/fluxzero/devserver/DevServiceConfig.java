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
import java.util.Map;

/** Configuration for a managed command service or an externally supplied local service. */
public record DevServiceConfig(
        String command,
        String stopCommand,
        String url,
        String directory,
        Map<String, Integer> ports,
        Map<String, String> environment,
        Readiness readiness
) {
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(2);

    public DevServiceConfig {
        ports = ports == null ? Map.of() : Map.copyOf(ports);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        readiness = readiness == null ? new Readiness(null, null, DEFAULT_STARTUP_TIMEOUT) : readiness;
    }

    boolean managed() {
        return command != null;
    }

    /** Protocol-independent readiness configuration. */
    public record Readiness(String http, String tcp, Duration timeout) {
        public Readiness {
            timeout = timeout == null ? DEFAULT_STARTUP_TIMEOUT : timeout;
        }
    }
}
