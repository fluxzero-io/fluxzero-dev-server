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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class DevCommandsDeserializer extends JsonDeserializer<Map<String, DevCommandConfig>> {

    @Override
    public Map<String, DevCommandConfig> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        ObjectCodec codec = parser.getCodec();
        JsonNode root = codec.readTree(parser);
        LinkedHashMap<String, DevCommandConfig> result = new LinkedHashMap<>();
        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                add(result, entry.getKey(), entry.getValue(), codec, context);
            }
            return result;
        }
        if (root.isArray()) {
            for (JsonNode entry : root) {
                if (entry.isTextual()) {
                    String file = entry.textValue();
                    add(result, file, entry, codec, context);
                    continue;
                }
                if (!entry.isObject() || entry.size() != 1) {
                    context.reportInputMismatch(Map.class,
                                                "Each commands list entry must be a file path or one named command");
                }
                Iterator<Map.Entry<String, JsonNode>> fields = entry.properties().iterator();
                Map.Entry<String, JsonNode> field = fields.next();
                add(result, field.getKey(), field.getValue(), codec, context);
            }
            return result;
        }
        context.reportInputMismatch(Map.class, "commands must be an object or an ordered list");
        return Map.of();
    }

    private static void add(Map<String, DevCommandConfig> target, String id, JsonNode value, ObjectCodec codec,
                            DeserializationContext context) throws IOException {
        if (id == null || id.isBlank()) {
            context.reportInputMismatch(Map.class, "Command id or file path must not be blank");
        }
        if (target.containsKey(id)) {
            context.reportInputMismatch(Map.class, "Duplicate command id or file path: %s", id);
        }
        if (!value.isTextual() && !value.isObject()) {
            context.reportInputMismatch(Map.class,
                                        "Command '%s' must be a file path or an inline command definition", id);
        }
        JsonNode definition = value;
        if (value.isObject() && fileReferenceId(id) && !value.has("file") && !value.has("type")) {
            ObjectNode fileDefinition = ((ObjectNode) value).deepCopy();
            fileDefinition.put("file", id);
            definition = fileDefinition;
        }
        DevCommandConfig command = definition.isTextual()
                ? new DevCommandConfig(definition.textValue(), null, null, null, Map.of(), null)
                : codec.treeToValue(definition, DevCommandConfig.class);
        target.put(id, command);
    }

    private static boolean fileReferenceId(String id) {
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".json") || normalized.indexOf('*') >= 0 || normalized.indexOf('?') >= 0;
    }
}
