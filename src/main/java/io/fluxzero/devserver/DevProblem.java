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

/**
 * A currently active problem associated with a service or a specific service instance.
 */
public record DevProblem(
        String id,
        DevLogEvent.Level severity,
        String category,
        String source,
        String serviceType,
        String serviceId,
        String instanceId,
        String operationId,
        String summary,
        String detail,
        int occurrences,
        long firstSeenAt,
        long lastSeenAt,
        long lastEventSequence
) {
    DevProblem observedAgain(String detail, long timestamp, long eventSequence) {
        return new DevProblem(id, severity, category, source, serviceType, serviceId, instanceId, operationId,
                              summary, detail, occurrences + 1, firstSeenAt, timestamp, eventSequence);
    }
}
