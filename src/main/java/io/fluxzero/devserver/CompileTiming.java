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

record CompileTiming(String mode, long millis) {

    static CompileTiming unknown() {
        return new CompileTiming("unknown", -1);
    }

    String summary() {
        return mode + " " + format(millis);
    }

    static String format(long millis) {
        if (millis < 0) {
            return "unknown";
        }
        if (millis < 1_000) {
            return millis + "ms";
        }
        long totalTenths = (millis + 50) / 100;
        if (totalTenths < 600) {
            return formatTenths(totalTenths) + "s";
        }
        long minutes = totalTenths / 600;
        long secondsTenths = totalTenths % 600;
        return secondsTenths == 0
                ? minutes + "m"
                : minutes + "m " + formatTenths(secondsTenths) + "s";
    }

    private static String formatTenths(long tenths) {
        long whole = tenths / 10;
        long fraction = tenths % 10;
        return fraction == 0 ? Long.toString(whole) : whole + "." + fraction;
    }
}
