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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompileTimingTest {

    @ParameterizedTest
    @CsvSource({
            "-1, unknown",
            "0, 0ms",
            "842, 842ms",
            "1000, 1s",
            "29154, 29.2s",
            "59949, 59.9s",
            "59950, 1m",
            "72345, 1m 12.3s",
            "120000, 2m"
    })
    void formatsDurationsForHumans(long millis, String expected) {
        assertEquals(expected, CompileTiming.format(millis));
    }
}
