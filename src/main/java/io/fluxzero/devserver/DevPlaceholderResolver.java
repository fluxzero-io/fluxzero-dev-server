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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DevPlaceholderResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    private final Map<String, String> values;
    private final Set<String> strictPrefixes;

    DevPlaceholderResolver(Map<String, String> values, Set<String> strictPrefixes) {
        this.values = Map.copyOf(values);
        this.strictPrefixes = Set.copyOf(strictPrefixes);
    }

    static DevPlaceholderResolver services(String sessionId, Map<String, String> serviceValues) {
        Map<String, String> values = new LinkedHashMap<>(serviceValues);
        values.put("session.id", sessionId);
        return new DevPlaceholderResolver(values, Set.of("services.", "session."));
    }

    DevPlaceholderResolver with(Map<String, String> additionalValues, Set<String> additionalStrictPrefixes) {
        Map<String, String> combinedValues = new LinkedHashMap<>(values);
        combinedValues.putAll(additionalValues);
        java.util.LinkedHashSet<String> combinedPrefixes = new java.util.LinkedHashSet<>(strictPrefixes);
        combinedPrefixes.addAll(additionalStrictPrefixes);
        return new DevPlaceholderResolver(combinedValues, combinedPrefixes);
    }

    String resolve(String value) {
        if (value == null || value.indexOf('{') < 0) {
            return value;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.get(key);
            if (replacement == null) {
                if (strictPrefixes.stream().anyMatch(key::startsWith)) {
                    throw new DevServerStartupException("Unknown development placeholder: {" + key + "}");
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    Map<String, String> resolve(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, resolve(value)));
        return result;
    }
}
