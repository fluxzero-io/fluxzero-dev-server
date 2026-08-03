/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrontendTerminalFilterTest {

    private final FrontendTerminalFilter subject = new FrontendTerminalFilter();

    @Test
    void hidesReadinessPortsAndOrdinaryBuildOutput() {
        assertNull(subject.visibleLine("[frontend] ready at http://127.0.0.1:54321"));
        assertNull(subject.visibleLine("[frontend] Initial Chunk Files | Names | Raw Size"));
        assertNull(subject.visibleLine("[frontend] Compiled successfully."));
    }

    @Test
    void keepsActionableLifecycleOutputWithoutInternalUrl() {
        assertEquals("unavailable",
                     subject.visibleLine("[frontend] unavailable at http://127.0.0.1:54321"));
        assertEquals("still waiting: ConnectException: Connection refused",
                     subject.visibleLine("[frontend] still waiting for http://127.0.0.1:54321: "
                                         + "ConnectException: Connection refused"));
        assertEquals("process exited unexpectedly; restarting once",
                     subject.visibleLine("[frontend] process exited unexpectedly; restarting once"));
    }
}
