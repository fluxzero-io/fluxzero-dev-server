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

import static org.junit.jupiter.api.Assertions.*;

class GoMemoryTest {
    @Test void usesTheSameAccountingAsGoMemoryLimit() {
        var usage = DevMonitoring.goMemory("""
                process_resident_memory_bytes 99000000
                go_memstats_alloc_bytes 15000000
                go_memstats_sys_bytes 32000000
                go_memstats_heap_released_bytes 4000000
                go_memlimit_bytes 1.34217728e8
                """);
        assertEquals(28000000, usage.used());
        assertEquals(134217728, usage.max());
    }
    @Test void doesNotInventMissingOrUnlimitedValues() {
        assertNull(DevMonitoring.goMemory("go_memstats_alloc_bytes 100"));
        assertNull(DevMonitoring.goMemory("go_memstats_sys_bytes NaN\ngo_memstats_heap_released_bytes 0"));
        assertNull(DevMonitoring.goMemory("go_memstats_sys_bytes 1\ngo_memstats_heap_released_bytes 2"));
        var usage = DevMonitoring.goMemory("go_memstats_sys_bytes 100\ngo_memstats_heap_released_bytes 0\ngo_memlimit_bytes 9223372036854775807");
        assertEquals(100, usage.used());
        assertNull(usage.max());
    }
}
