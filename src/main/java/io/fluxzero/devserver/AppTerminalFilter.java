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
import java.util.concurrent.ConcurrentHashMap;

final class AppTerminalFilter {
    private final Set<String> awaitingErrorDetails = ConcurrentHashMap.newKeySet();

    String visibleLine(String instanceId, String stream, String line) {
        if (errorHeader(line)) {
            awaitingErrorDetails.add(instanceId);
            return null;
        }
        if (!line.isBlank() && awaitingErrorDetails.remove(instanceId)) {
            String summary = exceptionSummary(line);
            if (summary != null) {
                return "Cause: " + summary;
            }
        }
        return normallyVisible(stream, line) ? line : null;
    }

    static boolean errorHeader(String line) {
        return line.contains("Application run failed") || line.contains("Uncaught exception");
    }

    private static boolean normallyVisible(String stream, String line) {
        return line.contains("level=ERROR") || line.contains("level=WARN")
               || line.contains(" ERROR ") || line.contains(" WARN ")
               || line.contains("Failed to start");
    }

    private static String exceptionSummary(String line) {
        String value = line.strip();
        if (value.startsWith("Caused by: ")) {
            value = value.substring("Caused by: ".length());
        }
        if (value.startsWith("Exception in thread ")) {
            return value;
        }
        int separator = value.indexOf(':');
        String type = separator < 0 ? value : value.substring(0, separator);
        if (!(type.endsWith("Exception") || type.endsWith("Error") || type.endsWith("Throwable"))) {
            return null;
        }
        int packageSeparator = type.lastIndexOf('.');
        String simpleType = packageSeparator < 0 ? type : type.substring(packageSeparator + 1);
        return separator < 0 ? simpleType : simpleType + value.substring(separator);
    }
}
