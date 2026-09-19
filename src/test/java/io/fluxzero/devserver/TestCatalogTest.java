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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TestCatalogTest {
    private static final String ID = "[engine:junit-jupiter]/[class:demo.CheckoutTest]/[method:acceptsValidPayment()]";
    @Test void describesLegacyAndDynamicNamesWithoutMergingModules() {
        var legacy = TestCatalog.describe("app", "a", ID, null, "passed");
        assertEquals("Accepts valid payment", legacy.name());
        assertEquals("demo.CheckoutTest", legacy.suite());
        assertNotEquals(legacy.key(), TestCatalog.describe("app", "b", ID, null, "passed").key());
        assertNotEquals(legacy.key(), TestCatalog.describe("other", "a", ID, null, "passed").key());
        assertEquals("Voiding a draft via API", TestCatalog.describe("app", "m", "demo.Test#voidingADraftViaAPI", null, "passed").name());
        assertEquals("Accepts valid payment · [1] async=true", TestCatalog.describe("app", "a", ID,
                "acceptsValidPayment() · [1] async=true", "passed").name());
        var dynamic = TestCatalog.describe("app", "a", ID + "/[test-template-invocation:#2]", null, "pending");
        assertTrue(dynamic.name().endsWith(" · #2"));
        assertEquals("Customer can pay", TestCatalog.describe("app", "a", ID, "Customer can pay", "failed").name());
        assertEquals("Accepts payment", TestCatalog.describe("app", "gradle", "demo.Checkout#acceptsPayment()", null, "passed").name());
    }

    @Test void preservesOldCachesAndNewDisplayNamesAcrossSelectiveRuns(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("test-inventory.json"),
                "{\"known\":true,\"modules\":{\"m\":{\"demo.A#works\":\"passed\"}},\"templates\":{}}");
        var inventory = new TestInventory(directory);
        assertEquals("Works", inventory.cases("app").getFirst().name());
        try (var events = new TestTelemetry(() -> {}, inventory)) {
            events.record("m", "name", "demo.A#works\0Customer can checkout");
            events.record("m", "passed", "demo.A#works");
        }
        inventory.save();
        inventory = new TestInventory(directory);
        assertEquals("Customer can checkout", inventory.cases("app").getFirst().name());
        inventory.reset(List.of("demo.A#works"));
        assertEquals("pending", inventory.cases("app").getFirst().state());
        assertEquals("Customer can checkout", inventory.cases("app").getFirst().name());
        inventory.discover("m", Set.of(), Set.of());
        inventory.finish(true, true, Map.of());
        assertTrue(new TestInventory(directory).cases("app").isEmpty());
    }

    @Test void pagesAndSearchesAllResultsWhileAttentionHidesSuccessfulTests() {
        var tests = new java.util.ArrayList<TestCatalog.Case>();
        for (int i = 0; i < 123; i++) tests.add(TestCatalog.describe("app", "m", "demo.A#scenario" + i, null, "passed"));
        tests.add(TestCatalog.describe("app", "m", "demo.A#broken", "Payment fails", "failed"));
        tests.add(TestCatalog.describe("app", "m", "demo.A#pending", null, "pending"));
        var attention = TestCatalog.page(tests, null, null, null);
        assertEquals(List.of("failed", "pending"), attention.items().stream().map(TestCatalog.Case::state).toList());
        assertEquals(123L, attention.counts().get("passed"));
        var page = TestCatalog.page(tests, "passed", "", "50");
        assertEquals(50, page.items().size());assertEquals(123, page.total());assertEquals(50, page.offset());
        assertEquals(100, TestCatalog.page(tests, "passed", "", "999999").offset());
        assertEquals(0, TestCatalog.page(tests, "passed", "", "invalid").offset());
        assertEquals("Payment fails", TestCatalog.page(tests, "all", "PAYMENT", "0").items().getFirst().name());
        assertTrue(TestCatalog.page(tests, "passed", "PAYMENT", "0").items().isEmpty());
    }
}
