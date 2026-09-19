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
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.regex.Pattern;

/** Bounds model context and masks credential-shaped fields, including JSON encoded inside payload strings. */
final class MonitoringResultSanitizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("(?i).*(password|passwd|secret|authorization|cookie|access[_.-]?token|refresh[_.-]?token|api[_.-]?key|private[_.-]?key|credential).*|(?i)token");
    private static final Pattern TEXT = Pattern.compile("(?i)(bearer\\s+)[^\\s\"',;]+|((?:password|secret|token|api[_-]?key)\\s*[=:]\\s*)[^\\s\"',;]+|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]+|whsec_[A-Za-z0-9]+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private int remaining = 24000;
    private boolean truncated, redacted;

    boolean truncated() { return truncated; }
    boolean redacted() { return redacted; }
    JsonNode sanitize(JsonNode value) { return sanitize(value, 0); }

    private JsonNode sanitize(JsonNode value, int depth) {
        if (remaining <= 0 || depth > 12) { truncated = true; return TextNode.valueOf("[TRUNCATED]"); }
        remaining -= 4;
        if (value.isTextual()) {
            String text = value.asText();
            if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
                try { return sanitize(JSON.readTree(text), depth + 1); }
                catch (Exception ignored) { /* Plain text remains plain text. */ }
            }
            String safe = TEXT.matcher(text).replaceAll("[REDACTED]");
            redacted |= !safe.equals(text);
            int max = Math.max(0, Math.min(4000, remaining));
            if (safe.length() > max) { safe = safe.substring(0, max) + "[TRUNCATED]"; truncated = true; }
            remaining -= safe.length(); return TextNode.valueOf(safe);
        }
        if (value.isObject()) {
            var result = JSON.createObjectNode(); var fields = value.fields(); int count = 0;
            while (fields.hasNext()) {
                if (++count > 100 || remaining <= 0) { result.put("_truncated", true); truncated = true; break; }
                var entry = fields.next();
                String key = entry.getKey();
                if (key.length() > 256) { key = key.substring(0, 256) + "[TRUNCATED]"; truncated = true; }
                remaining -= key.length();
                if (KEY.matcher(entry.getKey()).matches()) { result.put(key, "[REDACTED]"); redacted = true; }
                else result.set(key, sanitize(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode(); int count = 0;
            for (var item : value) {
                if (++count > 100 || remaining <= 0) { truncated = true; break; }
                result.add(sanitize(item, depth + 1));
            }
            return result;
        }
        return value;
    }
}
