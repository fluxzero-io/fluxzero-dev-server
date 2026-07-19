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

import java.time.Instant;
import java.util.List;

/**
 * Machine-readable status for retryable dev seed commands.
 */
record DevCommandStatus(
        String state,
        String sessionId,
        int total,
        int succeeded,
        int failed,
        int blocked,
        int pending,
        List<Entry> commands,
        long updatedAt
) {
    static DevCommandStatus empty() {
        return empty(null);
    }

    static DevCommandStatus empty(String sessionId) {
        return new DevCommandStatus("idle", sessionId, 0, 0, 0, 0, 0, List.of(), Instant.now().toEpochMilli());
    }

    String summary() {
        if (total == 0) {
            return "no dev seed commands";
        }
        return "total=" + total + ", succeeded=" + succeeded + ", failed=" + failed + ", blocked=" + blocked
               + ", pending=" + pending;
    }

    DevCommandStatus invalidated(String detail) {
        List<Entry> invalidated = commands.stream().map(entry -> entry.withState("stale", detail)).toList();
        return new DevCommandStatus("stale", sessionId, total, 0, 0, 0, total, invalidated,
                                    Instant.now().toEpochMilli());
    }

    record Entry(
            String path,
            String hash,
            String type,
            String state,
            String detail,
            long lastAttemptAt
    ) {
        Entry withState(String state, String detail) {
            return new Entry(path, hash, type, state, detail, Instant.now().toEpochMilli());
        }

        Entry blockedBy(String blockingPath) {
            return new Entry(path, hash, type, "blocked", "blocked by " + blockingPath, lastAttemptAt);
        }
    }
}
