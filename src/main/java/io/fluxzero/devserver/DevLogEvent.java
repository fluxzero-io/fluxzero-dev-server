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
 * One structured event in a Fluxzero dev environment timeline.
 */
public record DevLogEvent(
        long sequence,
        long timestamp,
        Level level,
        String source,
        String serviceType,
        String serviceId,
        String instanceId,
        String operationId,
        String stream,
        String message
) {
    /** Status messages are written as state followed by optional ": " detail. Keep detail in get_logs. */
    DevLogEvent compactStatus() {
        int detailStart = message.indexOf(": ");
        String state = detailStart < 0 ? message : message.substring(0, detailStart);
        return new DevLogEvent(sequence, timestamp, level, source, serviceType, serviceId, instanceId,
                               operationId, stream, state);
    }

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR;

        boolean atLeast(Level minimum) {
            return ordinal() >= minimum.ordinal();
        }
    }
}
