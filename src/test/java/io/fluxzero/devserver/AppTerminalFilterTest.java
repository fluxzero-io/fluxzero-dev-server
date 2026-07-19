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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppTerminalFilterTest {

    private final AppTerminalFilter subject = new AppTerminalFilter();

    @Test
    void addsExceptionCauseAfterGenericErrorHeader() {
        assertNull(subject.visibleLine("orders-1", "stdout", "ERROR FluxzeroSpringConfig - Uncaught exception"));
        assertEquals("Cause: IllegalCommandException: Already exists.",
                     subject.visibleLine("orders-1", "stdout",
                                         "io.fluxzero.sdk.tracking.handling.IllegalCommandException: Already exists."));
        assertNull(subject.visibleLine("orders-1", "stdout", "\tat host.flux.Orders.main(Orders.java:42)"));
    }

    @Test
    void keepsErrorDetailsScopedToTheirApplicationInstance() {
        subject.visibleLine("orders-1", "stdout", "Application run failed");

        assertNull(subject.visibleLine("billing-1", "stdout", "java.lang.IllegalStateException: billing"));
        assertEquals("Cause: IllegalStateException: orders",
                     subject.visibleLine("orders-1", "stdout", "java.lang.IllegalStateException: orders"));
    }
}
