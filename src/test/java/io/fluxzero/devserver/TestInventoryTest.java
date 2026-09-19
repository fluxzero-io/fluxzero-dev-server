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
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TestInventoryTest {
    private static final String A = "[engine:junit-jupiter]/[class:demo.A]/[method:first()]";
    private static final String B = "[engine:junit-jupiter]/[class:demo.B]/[method:second()]";

    @Test void selectionClearsOnlyItsPreviousResultsAndSurvivesReconnect(@TempDir Path directory) {
        var inventory = new TestInventory(directory);
        inventory.discover("module", Set.of(A, B), Set.of());
        inventory.event("module", "passed", A); inventory.event("module", "failed", B);
        inventory.finish(true, true, Map.of("module", Set.of(A, B)));
        inventory = new TestInventory(directory);
        assertEquals(new TestCounts(1, 1, 0), inventory.snapshot().counts());
        inventory.reset(List.of("demo.B"));
        assertEquals(2, inventory.snapshot().total());
        assertEquals(new TestCounts(1, 0, 0), inventory.snapshot().counts());
        assertEquals(inventory.snapshot(), new TestInventory(directory).snapshot());
        inventory.discover("module", Set.of(A, B), Set.of());
        inventory.event("module", "passed", B);
        inventory.finish(false, true, Map.of("module", Set.of(B)));
        assertEquals(new TestCounts(2, 0, 0), new TestInventory(directory).snapshot().counts());
    }

    @Test void discoveredTestsAreNotLimitedToExecutedSelectionAndRemovedTestsDisappear(@TempDir Path directory) {
        var inventory = new TestInventory(directory);
        assertFalse(inventory.snapshot().known());
        inventory.discover("module", Set.of(A, B), Set.of());
        inventory.event("module", "passed", A);
        assertEquals(2, inventory.snapshot().total());
        assertEquals(1, inventory.snapshot().counts().passed());
        inventory.finish(true, true, Map.of("module", Set.of(A)));
        assertEquals(2, inventory.snapshot().total(), "Present tests stay counted even when runner filters omit them");
        inventory.discover("module", Set.of(B), Set.of());
        assertEquals(1, inventory.snapshot().total());
        assertEquals(0, inventory.snapshot().counts().total());
    }

    @Test void methodSelectionAndInterruptedRunsDoNotEraseOtherResults(@TempDir Path directory) {
        var inventory = new TestInventory(directory);
        String other = A.replace("first", "other");
        inventory.discover("module", Set.of(A, other), Set.of());
        inventory.event("module", "passed", A); inventory.event("module", "passed", other);
        inventory.reset(List.of("demo.A#first"));
        inventory.finish(false, false, Map.of());
        assertEquals(2, inventory.snapshot().total());
        assertEquals(new TestCounts(1, 0, 0), inventory.snapshot().counts());
    }

    @Test void dynamicInvocationsCanShrinkOnSelectiveRerun(@TempDir Path directory) {
        var inventory = new TestInventory(directory);
        String template = "[engine:junit-jupiter]/[class:demo.A]/[test-template:parameter(int)]";
        String first = template + "/[test-template-invocation:#1]", second = template + "/[test-template-invocation:#2]";
        inventory.discover("module", Set.of(B), Set.of(template));
        assertFalse(inventory.snapshot().known());
        inventory.event("module", "passed", first);inventory.event("module", "passed", second);
        assertTrue(inventory.snapshot().known());
        inventory.reset(List.of("demo.A#parameter"));
        inventory.event("module", "passed", first);
        inventory.finish(false, true, Map.of("module", Set.of(first)));
        assertEquals(2, inventory.snapshot().total());
        assertEquals(1, inventory.snapshot().counts().passed());
    }
}
