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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortListenerProcessTest {

    @Test
    void parsesWindowsListenerPidForExactPort() {
        List<String> netstat = List.of(
                "  TCP    127.0.0.1:4200       0.0.0.0:0       LISTENING       80273",
                "  TCP    127.0.0.1:14200      0.0.0.0:0       LISTENING       90000");

        assertEquals(80273L, PortListenerProcess.parseWindowsListener(netstat, 4200).orElseThrow());
        assertTrue(PortListenerProcess.parseWindowsListener(netstat, 4300).isEmpty());
    }

    @Test
    void parsesLinuxSsListenerPid() {
        List<String> ss = List.of(
                "LISTEN 0 511 127.0.0.1:4200 0.0.0.0:* users:((\"node\",pid=80273,fd=19))");

        assertEquals(80273L, PortListenerProcess.parseSsListener(ss).orElseThrow());
    }

    @Test
    void shortensLongProcessDescription() {
        PortListenerProcess process = new PortListenerProcess(1, "node " + "x".repeat(300));

        assertEquals(180, process.displayCommand().length());
        assertTrue(process.displayCommand().endsWith("..."));
    }
}
