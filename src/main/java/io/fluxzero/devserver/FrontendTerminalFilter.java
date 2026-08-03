/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

final class FrontendTerminalFilter {

    String visibleLine(String message) {
        if (!message.startsWith("[frontend] ")) {
            return null;
        }
        String detail = message.substring("[frontend] ".length());
        if (detail.startsWith("still waiting for ")) {
            int reasonSeparator = detail.indexOf(": ");
            return reasonSeparator < 0 ? "still waiting" : "still waiting: " + detail.substring(reasonSeparator + 2);
        }
        if (detail.startsWith("unavailable at ")) {
            return "unavailable";
        }
        if (detail.startsWith("process exited")
            || detail.startsWith("remained unavailable")
            || detail.startsWith("failed to restart")) {
            return detail;
        }
        return null;
    }
}
