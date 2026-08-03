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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;

/**
 * Selects a JWT subject for routing through the local raw-mode proxy. This does not authenticate the token: local
 * development already permits callers to provide the target namespace as a raw header value.
 */
final class DevNamespaceHeader {
    static final String NAME = "Fluxzero-Namespace";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DevNamespaceHeader() {
    }

    static String routingValue(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) {
            return value;
        }
        try {
            JsonNode payload = OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode subject = payload.get("sub");
            return subject != null && subject.isTextual() && !subject.textValue().isBlank()
                    ? subject.textValue() : value;
        } catch (RuntimeException | java.io.IOException ignored) {
            return value;
        }
    }
}
