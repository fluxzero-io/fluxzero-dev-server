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

import java.time.Duration;

final class StableReadiness {
    private final long readyThresholdNanos;
    private final long unavailableThresholdNanos;
    private final long recoveryThresholdNanos;

    private boolean ready;
    private boolean everReady;
    private Boolean candidate;
    private long candidateSinceNanos;

    StableReadiness(Duration readyThreshold, Duration unavailableThreshold) {
        this(readyThreshold, unavailableThreshold, readyThreshold);
    }

    StableReadiness(Duration readyThreshold, Duration unavailableThreshold, Duration recoveryThreshold) {
        readyThresholdNanos = readyThreshold.toNanos();
        unavailableThresholdNanos = unavailableThreshold.toNanos();
        recoveryThresholdNanos = recoveryThreshold.toNanos();
    }

    /**
     * Returns the newly confirmed state, or {@code null} when no stable transition occurred.
     */
    Boolean observe(boolean observedReady, long nowNanos) {
        if (observedReady == ready) {
            candidate = null;
            return null;
        }
        if (candidate == null || candidate != observedReady) {
            candidate = observedReady;
            candidateSinceNanos = nowNanos;
            return thresholdNanos(observedReady) == 0 ? confirm(observedReady) : null;
        }
        if (nowNanos - candidateSinceNanos < thresholdNanos(observedReady)) {
            return null;
        }
        return confirm(observedReady);
    }

    private Boolean confirm(boolean confirmedReady) {
        ready = confirmedReady;
        everReady |= confirmedReady;
        candidate = null;
        return confirmedReady;
    }

    private long thresholdNanos(boolean targetReady) {
        return targetReady ? everReady ? recoveryThresholdNanos : readyThresholdNanos : unavailableThresholdNanos;
    }
}
