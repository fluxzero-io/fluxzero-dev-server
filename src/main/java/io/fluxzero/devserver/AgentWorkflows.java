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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Versioned workflow guidance owned by the serving dev-server distribution. */
final class AgentWorkflows {
    static final List<String> TOPICS = List.of("overview", "setup", "development", "preview", "monitoring", "progress", "startup");

    static Map<String, Object> read(Map<String, Object> arguments) {
        if (arguments.keySet().stream().anyMatch(key -> !"topic".equals(key)))
            throw new IllegalArgumentException("Only topic is supported.");
        Object topic = arguments.getOrDefault("topic", "overview");
        if (!(topic instanceof String name) || !TOPICS.contains(name))
            throw new IllegalArgumentException("Choose a workflow topic: " + TOPICS);
        try (var stream = AgentWorkflows.class.getResourceAsStream("/agent-workflows/" + topic + ".md")) {
            if (stream == null) throw new IllegalStateException("Workflow resource is missing.");
            return Map.of("version", DevServerVersion.current(), "topic", topic, "topics", TOPICS,
                    "content", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new IllegalStateException("Unable to read workflow.", e); }
    }

    static McpServerFeatures.SyncToolSpecification tool(ObjectMapper mapper,
            Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        var definition = DevMcpTools.tool("get_workflow",
                "Read current dev-server workflow guidance before development. Topics: setup, development, preview, "
                + "monitoring, progress, startup; omit topic for overview. Does not start development. "
                + "Reuse for the same project/version; use docs_* separately for SDK knowledge.",
                Map.of("topic", Map.of("type", "string", "enum", TOPICS)), AgentWorkflows::read, mapper, false);
        return handler == null ? definition : new McpServerFeatures.SyncToolSpecification(definition.tool(),
                (exchange, request) -> handler.apply(request));
    }
}
