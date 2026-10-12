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

package io.fluxzero.devserver.fixture;

import io.fluxzero.common.application.DefaultPropertySource;

/** Only reports paths and flags, never key material. Deliberately exercises a package-private main. */
public final class BffConfigurationMain {
    static void main(String[] args) {
        var properties = new DefaultPropertySource();
        System.out.println("session.file=" + properties.get("fluxzero.auth.session-key-file"));
        System.out.println("oidc.disabled=" + properties.get("fluxzero.auth.oidc.disabled"));
        System.out.println("args=" + String.join("|", args));
        System.out.println("BFF_CONFIGURATION_READY");
    }
}
