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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record TestStatus(String state, List<String> selectors, String reason, Map<String, String> selectionReasons,
                  int exitCode, String detail, String failureSummary, long durationMillis, long updatedAt) {
    TestStatus {
        selectors = List.copyOf(selectors);
        selectionReasons = selectionReasons == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(selectionReasons));
    }

    static TestStatus idle() {
        return new TestStatus("idle", List.of(), null, Map.of(), 0, null, null, 0,
                              Instant.now().toEpochMilli());
    }

    static TestStatus running(List<String> selectors, String reason) {
        return running(selectors, reason, Map.of());
    }

    static TestStatus running(List<String> selectors, String reason, Map<String, String> selectionReasons) {
        return new TestStatus("running", selectors, reason, selectionReasons, 0, null, null, 0,
                              Instant.now().toEpochMilli());
    }

    static TestStatus queued(List<String> selectors, String reason, String detail) {
        return queued(selectors, reason, Map.of(), detail);
    }

    static TestStatus queued(List<String> selectors, String reason, Map<String, String> selectionReasons,
                             String detail) {
        return new TestStatus("queued", selectors, reason, selectionReasons, 0, detail, null, 0,
                              Instant.now().toEpochMilli());
    }

    static TestStatus completed(List<String> selectors, String reason, int exitCode, String detail) {
        return completed(selectors, reason, Map.of(), exitCode, detail, 0);
    }

    static TestStatus completed(List<String> selectors, String reason, Map<String, String> selectionReasons,
                                int exitCode, String detail, long durationMillis) {
        return completed(selectors, reason, selectionReasons, exitCode, detail, null, durationMillis);
    }

    static TestStatus completed(List<String> selectors, String reason, Map<String, String> selectionReasons,
                                int exitCode, String detail, String failureSummary, long durationMillis) {
        return new TestStatus(exitCode == 0 ? "passed" : "failed", selectors, reason, selectionReasons,
                              exitCode, detail, failureSummary, durationMillis, Instant.now().toEpochMilli());
    }
}
