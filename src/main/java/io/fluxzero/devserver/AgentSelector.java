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

import java.util.Set;

/**
 * Optional filters for agent queries across applications and infrastructure services.
 *
 * @param serviceIds  application or infrastructure service identifiers
 * @param instanceIds concrete application instance identifiers
 * @param sources     subsystems such as {@code compile}, {@code app}, {@code test}, or {@code runtime}
 * @param minimumLevel minimum event/problem level
 */
public record AgentSelector(
        Set<String> serviceIds,
        Set<String> instanceIds,
        Set<String> sources,
        DevLogEvent.Level minimumLevel
) {
    public AgentSelector {
        serviceIds = immutable(serviceIds);
        instanceIds = immutable(instanceIds);
        sources = immutable(sources);
    }

    public static AgentSelector all() {
        return new AgentSelector(Set.of(), Set.of(), Set.of(), null);
    }

    boolean matches(DevLogEvent event) {
        return includes(serviceIds, event.serviceId())
               && includes(instanceIds, event.instanceId())
               && includes(sources, event.source())
               && (minimumLevel == null || event.level().atLeast(minimumLevel));
    }

    boolean matches(DevProblem problem) {
        return includes(serviceIds, problem.serviceId())
               && includes(instanceIds, problem.instanceId())
               && includes(sources, problem.source())
               && (minimumLevel == null || problem.severity().atLeast(minimumLevel));
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static boolean includes(Set<String> values, String candidate) {
        return values.isEmpty() || values.contains(candidate);
    }
}
