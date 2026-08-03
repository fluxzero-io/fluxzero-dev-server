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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevPlaceholderResolverTest {

    @Test
    void resolvesServiceValuesWithoutClaimingFrontendOrShellPlaceholders() {
        DevPlaceholderResolver resolver = DevPlaceholderResolver.services(
                "session-1", Map.of("services.logs.url", "http://127.0.0.1:19428"));

        assertEquals("http://127.0.0.1:19428/insert", resolver.resolve("{services.logs.url}/insert"));
        assertEquals("frontend-{port}-${HOME}", resolver.resolve("frontend-{port}-${HOME}"));
        assertEquals("session-1", resolver.resolve("{session.id}"));
    }

    @Test
    void rejectsUnknownManagedPlaceholders() {
        DevPlaceholderResolver resolver = DevPlaceholderResolver.services("session-1", Map.of());

        assertThrows(DevServerStartupException.class,
                     () -> resolver.resolve("{services.missing.url}"));
    }
}
