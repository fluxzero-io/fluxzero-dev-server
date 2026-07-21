/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import io.fluxzero.common.api.ConnectEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerReadinessTest {

    @Test
    void matchesUniqueClientIdRegardlessOfApplicationChosenClientName() {
        ConnectEvent renamedClient = new ConnectEvent(
                "flowmaps-core", "session-app-build-1", "session", "tracking", "1", "1");
        ConnectEvent anotherProcess = new ConnectEvent(
                "app", "session-app-build-2", "session", "tracking", "1", "1");

        assertTrue(DevServer.matchesReadinessClient("session-app-build-1", renamedClient));
        assertFalse(DevServer.matchesReadinessClient("session-app-build-1", anotherProcess));
    }
}
