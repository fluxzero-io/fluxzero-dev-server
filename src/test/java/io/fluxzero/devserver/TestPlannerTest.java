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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlannerTest {

    private final TestPlanner planner = new TestPlanner();

    @Test
    void changedTestClassRunsThatTest(@TempDir Path projectDirectory) {
        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(
                Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")), Set.of());

        assertTrue(plan.shouldRun());
        assertFalse(plan.runModule());
        assertEquals(List.of("com.acme.OrderHandlerTest"), plan.stableSelectors());
        assertEquals("changed test class", plan.reason());
        assertEquals("test source changed: src/test/java/com/acme/OrderHandlerTest.java",
                     plan.selectorReasons().get("com.acme.OrderHandlerTest"));
        assertEquals("src/test/java/com/acme/OrderHandlerTest.java", plan.explanation());
    }

    @Test
    void testApplicationMainIsNotTreatedAsTestSelector(@TempDir Path projectDirectory) throws Exception {
        Path source = projectDirectory.resolve("src/test/java/com/acme/Rebound.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme;
                public class Rebound {
                    static void main(String[] args) {}
                }
                """);

        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(Set.of(source), Set.of());

        assertFalse(plan.shouldRun());
        assertEquals("no affected tests", plan.reason());
    }

    @Test
    void unconventionalTestNameIsRecognizedFromTestFrameworkUsage(@TempDir Path projectDirectory) throws Exception {
        Path source = projectDirectory.resolve("src/test/java/com/acme/OrderSpecification.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class OrderSpecification {
                    @Test void createsOrder() {}
                }
                """);

        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(Set.of(source), Set.of());

        assertEquals(List.of("com.acme.OrderSpecification"), plan.stableSelectors());
        assertEquals("changed test class", plan.reason());
    }

    @Test
    void nonTestSourceIsNotRestoredAsPreviouslyFailingSelector(@TempDir Path projectDirectory) throws Exception {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>");
        Path source = projectDirectory.resolve("src/test/java/com/acme/Rebound.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.acme; public class Rebound { static void main(String[] args) {} }");

        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(
                Set.of(projectDirectory.resolve("README.md")), Set.of("com.acme.Rebound"));

        assertFalse(plan.shouldRun());
    }

    @Test
    void missingPreviouslyFailingTestRemainsVisible(@TempDir Path projectDirectory) {
        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(
                Set.of(projectDirectory.resolve("README.md")), Set.of("com.acme.RemovedTest"));

        assertEquals(List.of("com.acme.RemovedTest"), plan.stableSelectors());
        assertEquals("previously failing tests", plan.reason());
    }

    @Test
    void previouslyFailingTestsAreRetried() {
        TestPlanner.TestPlan plan = planner.plan(Set.of(Path.of("/project/README.md")),
                                                 Set.of("PaymentTest", "OrderTest"));

        assertTrue(plan.shouldRun());
        assertFalse(plan.runModule());
        assertEquals(List.of("OrderTest", "PaymentTest"), plan.stableSelectors());
        assertEquals("previously failing tests", plan.reason());
        assertEquals("previously failed", plan.selectorReasons().get("OrderTest"));
        assertEquals("retrying previous failures", plan.explanation());
    }

    @Test
    void changedMainCodeFallsBackToModuleTests() {
        TestPlanner.TestPlan plan = planner.plan(
                Set.of(Path.of("/project/src/main/java/com/acme/OrderHandler.java")), Set.of());

        assertTrue(plan.shouldRun());
        assertTrue(plan.runModule());
        assertEquals("changed app code fallback", plan.reason());
    }

    @Test
    void changedObservedHandlerUsesImpactIndexBeforeFallback(@TempDir Path projectDirectory) throws Exception {
        Path impactDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY);
        Files.createDirectories(impactDirectory);
        Files.writeString(impactDirectory.resolve(DevSessionStore.TEST_IMPACT_FILE), """
                {
                  "updatedAt": 1,
                  "tests": {
                    "com.acme.OrderHandlerTest#createsOrder": {
                      "handlers": ["com.acme.OrderHandler#handle"],
                      "payloads": ["com.acme.CreateOrder"],
                      "messages": [],
                      "web": [],
                      "documentCollections": [],
                      "schedulePayloads": []
                    },
                    "com.acme.PaymentTest#pays": {
                      "handlers": ["com.acme.PaymentHandler#handle"],
                      "payloads": ["com.acme.PayOrder"],
                      "messages": [],
                      "web": [],
                      "documentCollections": [],
                      "schedulePayloads": []
                    }
                  }
                }
                """);

        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(
                Set.of(projectDirectory.resolve("src/main/java/com/acme/OrderHandler.java")), Set.of());

        assertTrue(plan.shouldRun());
        assertFalse(plan.runModule());
        assertEquals(List.of("com.acme.OrderHandlerTest#createsOrder"), plan.stableSelectors());
        assertEquals("test impact index", plan.reason());
        assertEquals("observed OrderHandler",
                     plan.selectorReasons().get("com.acme.OrderHandlerTest#createsOrder"));
    }

    @Test
    void changedObservedPayloadSelectsOnlyAffectedFixtureTest(@TempDir Path projectDirectory) throws Exception {
        Path impactDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY);
        Files.createDirectories(impactDirectory);
        Files.writeString(impactDirectory.resolve(DevSessionStore.TEST_IMPACT_FILE), """
                {
                  "updatedAt": 1,
                  "tests": {
                    "com.acme.OrderFixtureTest#createsOrder": {
                      "handlers": ["com.acme.OrderHandler#handle"],
                      "payloads": ["com.acme.CreateOrder"],
                      "messages": [
                        {
                          "type": "COMMAND",
                          "topic": null,
                          "payloadClass": "com.acme.CreateOrder"
                        }
                      ],
                      "web": [],
                      "documentCollections": [],
                      "schedulePayloads": []
                    },
                    "com.acme.InvoiceFixtureTest#createsInvoice": {
                      "handlers": ["com.acme.InvoiceHandler#handle"],
                      "payloads": ["com.acme.CreateInvoice"],
                      "messages": [
                        {
                          "type": "COMMAND",
                          "topic": null,
                          "payloadClass": "com.acme.CreateInvoice"
                        }
                      ],
                      "web": [],
                      "documentCollections": [],
                      "schedulePayloads": []
                    }
                  }
                }
                """);

        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(
                Set.of(projectDirectory.resolve("src/main/java/com/acme/CreateInvoice.java")), Set.of());

        assertTrue(plan.shouldRun());
        assertFalse(plan.runModule());
        assertEquals(List.of("com.acme.InvoiceFixtureTest#createsInvoice"), plan.stableSelectors());
        assertEquals("test impact index", plan.reason());
    }

    @Test
    void changedSharedHelperFallsBackToModuleEvenWhenImpactIndexExists(@TempDir Path projectDirectory)
            throws Exception {
        writeImpactIndex(projectDirectory, """
                {
                  "com.acme.OrderFixtureTest#createsOrder": {
                    "handlers": ["com.acme.OrderHandler#handle"],
                    "payloads": ["com.acme.CreateOrder"],
                    "messages": [],
                    "web": [],
                    "documentCollections": [],
                    "schedulePayloads": []
                  }
                }
                """);

        TestPlanner.TestPlan plan = new TestPlanner(projectDirectory).plan(
                Set.of(projectDirectory.resolve("src/main/java/com/acme/OrderFormatting.java")), Set.of());

        assertTrue(plan.shouldRun());
        assertTrue(plan.runModule());
        assertEquals("changed app code fallback", plan.reason());
    }

    @Test
    void changedWebAuthDocumentAndScheduleClassesSelectAffectedTests(@TempDir Path projectDirectory)
            throws Exception {
        writeImpactIndex(projectDirectory, """
                {
                  "com.acme.SecureEndpointTest#currentUser": {
                    "handlers": ["com.acme.SecureEndpoint#me"],
                    "payloads": [],
                    "messages": [],
                    "web": [
                      {
                        "method": "GET",
                        "path": "/secure/me",
                        "payloadClass": "com.acme.CurrentUserRequest"
                      }
                    ],
                    "documentCollections": [],
                    "schedulePayloads": []
                  },
                  "com.acme.DocumentProjectionTest#indexesInvoice": {
                    "handlers": ["com.acme.DocumentProjection#handle"],
                    "payloads": ["com.acme.InvoiceDocument"],
                    "messages": [
                      {
                        "type": "DOCUMENT",
                        "topic": "invoices",
                        "payloadClass": "com.acme.InvoiceDocument"
                      }
                    ],
                    "web": [],
                    "documentCollections": ["invoices"],
                    "schedulePayloads": []
                  },
                  "com.acme.ScheduleFixtureTest#expiresDailyDigest": {
                    "handlers": ["com.acme.ScheduleHandler#expire"],
                    "payloads": [],
                    "messages": [],
                    "web": [],
                    "documentCollections": [],
                    "schedulePayloads": ["com.acme.DailyDigestSchedule"]
                  }
                }
                """);
        TestPlanner planner = new TestPlanner(projectDirectory);

        assertEquals(List.of("com.acme.SecureEndpointTest#currentUser"),
                     planner.plan(Set.of(projectDirectory.resolve(
                             "src/main/java/com/acme/SecureEndpoint.java")), Set.of()).stableSelectors());
        assertEquals(List.of("com.acme.SecureEndpointTest#currentUser"),
                     planner.plan(Set.of(projectDirectory.resolve(
                             "src/main/java/com/acme/CurrentUserRequest.java")), Set.of()).stableSelectors());
        assertEquals(List.of("com.acme.DocumentProjectionTest#indexesInvoice"),
                     planner.plan(Set.of(projectDirectory.resolve(
                             "src/main/java/com/acme/InvoiceDocument.java")), Set.of()).stableSelectors());
        assertEquals(List.of("com.acme.ScheduleFixtureTest#expiresDailyDigest"),
                     planner.plan(Set.of(projectDirectory.resolve(
                             "src/main/java/com/acme/DailyDigestSchedule.java")), Set.of()).stableSelectors());
    }

    @Test
    void unrelatedFilesDoNotRunTests() {
        TestPlanner.TestPlan plan = planner.plan(Set.of(Path.of("/project/docs/dev.md")), Set.of());

        assertFalse(plan.shouldRun());
    }

    private static void writeImpactIndex(Path projectDirectory, String testsJson) throws Exception {
        Path impactDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY);
        Files.createDirectories(impactDirectory);
        Files.writeString(impactDirectory.resolve(DevSessionStore.TEST_IMPACT_FILE), """
                {
                  "updatedAt": 1,
                  "tests": %s
                }
                """.formatted(testsJson));
    }
}
