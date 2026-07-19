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

/** Determines whether the dev environment manages authentication infrastructure. */
public enum IdpMode {
    /** Start the local test IDP and inject its OIDC application settings. */
    MANAGED,
    /** Let the application use its own externally configured IDP without dev-server auth overrides. */
    EXTERNAL;

    static IdpMode parse(String value) {
        if (value == null || value.isBlank() || "managed".equalsIgnoreCase(value)) {
            return MANAGED;
        }
        if ("external".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value)) {
            return EXTERNAL;
        }
        throw new IllegalArgumentException("Unsupported IDP mode '" + value + "'. Use managed or external.");
    }
}
