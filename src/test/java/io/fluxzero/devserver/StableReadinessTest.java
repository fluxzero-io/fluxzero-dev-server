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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StableReadinessTest {

    private static final Duration READY_THRESHOLD = Duration.ofSeconds(3);
    private static final Duration UNAVAILABLE_THRESHOLD = Duration.ofSeconds(1);

    @Test
    void onlyConfirmsReadinessAfterContinuousHealthyPeriod() {
        StableReadiness readiness = new StableReadiness(READY_THRESHOLD, UNAVAILABLE_THRESHOLD);

        assertNull(readiness.observe(true, seconds(1)));
        assertNull(readiness.observe(true, seconds(3)));
        assertEquals(true, readiness.observe(true, seconds(4)));
    }

    @Test
    void transientHealthyProbeDoesNotMakeFrontendReady() {
        StableReadiness readiness = new StableReadiness(READY_THRESHOLD, UNAVAILABLE_THRESHOLD);

        assertNull(readiness.observe(true, seconds(1)));
        assertNull(readiness.observe(true, seconds(2)));
        assertNull(readiness.observe(false, seconds(2) + 1));
        assertNull(readiness.observe(true, seconds(3)));
        assertNull(readiness.observe(true, seconds(5)));
        assertEquals(true, readiness.observe(true, seconds(6)));
    }

    @Test
    void briefProbeFailureDoesNotWithdrawConfirmedReadiness() {
        StableReadiness readiness = readyReadiness();

        assertNull(readiness.observe(false, seconds(5)));
        assertNull(readiness.observe(true, seconds(5) + UNAVAILABLE_THRESHOLD.toNanos() / 2));
        assertNull(readiness.observe(false, seconds(6)));
        assertEquals(false, readiness.observe(false, seconds(7)));
    }

    @Test
    void canRecoverImmediatelyAfterInitialStableReadiness() {
        StableReadiness readiness = new StableReadiness(
                READY_THRESHOLD, UNAVAILABLE_THRESHOLD, Duration.ZERO);
        assertNull(readiness.observe(true, seconds(1)));
        assertEquals(true, readiness.observe(true, seconds(4)));
        assertNull(readiness.observe(false, seconds(5)));
        assertEquals(false, readiness.observe(false, seconds(7)));

        assertEquals(true, readiness.observe(true, seconds(7) + 1));
    }

    private static StableReadiness readyReadiness() {
        StableReadiness result = new StableReadiness(READY_THRESHOLD, UNAVAILABLE_THRESHOLD);
        result.observe(true, 0);
        assertEquals(true, result.observe(true, READY_THRESHOLD.toNanos()));
        return result;
    }

    private static long seconds(long seconds) {
        return Duration.ofSeconds(seconds).toNanos();
    }
}
