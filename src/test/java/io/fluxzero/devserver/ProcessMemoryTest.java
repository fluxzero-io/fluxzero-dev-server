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
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class ProcessMemoryTest {
    @Test void convertsUnixKiBAndWindowsBytes() {
        assertEquals(Map.of(12L, 2048L, 15L, 3072L), ProcessMemory.parse(" 12 2\n15 3\ninvalid", false));
        assertEquals(Map.of(12L, 2048L), ProcessMemory.parse("12 2048\r\n", true));
        assertTrue(ProcessMemory.command(Set.of(12L), true).contains("powershell.exe"));
        assertEquals("12", ProcessMemory.command(Set.of(12L), false).getLast());
    }
    @Test void countsOnlyTheSupervisorWhenRequested() {
        var sampler = new ProcessMemory();
        long current = ProcessHandle.current().pid();
        sampler.sample(Set.of(current, Long.MAX_VALUE), Set.of(current));
        assertEquals(1, sampler.processCount(current));
        assertEquals(0, sampler.processCount(Long.MAX_VALUE));
        assertEquals(0, sampler.processCount(null));
    }
    @Test void measuresCurrentProcessAndOmitsMissingProcess() {
        var memory = new ProcessMemory().sample(Set.of(ProcessHandle.current().pid(), Long.MAX_VALUE));
        assertTrue(memory.get(ProcessHandle.current().pid()) > 0);
        assertFalse(memory.containsKey(Long.MAX_VALUE));
    }
}
