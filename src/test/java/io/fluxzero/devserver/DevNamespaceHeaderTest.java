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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DevNamespaceHeaderTest {
    @Test
    void keepsRawNamespace() {
        assertEquals("fluxzero_mp_prod-logs", DevNamespaceHeader.routingValue("fluxzero_mp_prod-logs"));
    }

    @Test
    void selectsSubjectFromJwtForLocalRouting() {
        assertEquals("fluxzero_mp_prod-logs", DevNamespaceHeader.routingValue(jwt("fluxzero_mp_prod-logs")));
    }

    @Test
    void keepsMalformedOrSubjectlessJwtUnchanged() {
        assertEquals("one.not-base64.three", DevNamespaceHeader.routingValue("one.not-base64.three"));
        String subjectless = encoded("{\"alg\":\"RS256\"}") + "." + encoded("{\"iss\":\"local\"}") + ".x";
        assertEquals(subjectless, DevNamespaceHeader.routingValue(subjectless));
        assertNull(DevNamespaceHeader.routingValue(null));
    }

    static String jwt(String subject) {
        return encoded("{\"alg\":\"RS256\"}") + "."
               + encoded("{\"sub\":\"" + subject + "\"}") + ".signature";
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
