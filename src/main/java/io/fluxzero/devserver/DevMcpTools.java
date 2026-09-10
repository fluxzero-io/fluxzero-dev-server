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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared project tool definitions and response encoding for HTTP and stdio. */
final class DevMcpTools {
    private DevMcpTools() {}

    static List<McpServerFeatures.SyncToolSpecification> tools(AgentQueryService queryService,
                                                                        ObjectMapper objectMapper) {
        return tools(request -> {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            try {
                Object value = switch (request.name()) {
                    case "get_status" -> queryService.getStatus();
                    case "get_active_problems" -> queryService.getActiveProblems(selector(arguments),
                            intValue(arguments, "limit", AgentQueryService.DEFAULT_LIMIT));
                    case "get_logs" -> queryService.getLogs(cursor(arguments, queryService), selector(arguments),
                            intValue(arguments, "limit", AgentQueryService.DEFAULT_LIMIT));
                    case "get_test_status" -> queryService.getTestStatus();
                    case "wait_for_change" -> queryService.waitForChange(cursor(arguments, queryService),
                            selector(arguments), Duration.ofMillis(longValue(arguments, "timeoutMs", 30_000)),
                            intValue(arguments, "limit", AgentQueryService.DEFAULT_LIMIT));
                    default -> throw new IllegalArgumentException("Unknown project tool: " + request.name());
                };
                return result(value, objectMapper);
            } catch (RuntimeException e) {
                return McpSchema.CallToolResult.builder().addTextContent(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()).isError(true).build();
            }
        });
    }

    static List<McpServerFeatures.SyncToolSpecification> tools(
            java.util.function.Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return List.of(
                projectTool("get_status", "Return current startup and service status for the Fluxzero dev environment.",
                            Map.of(), handler),
                projectTool("get_active_problems", "Return bounded unresolved problems, optionally filtered per app.",
                            selectorProperties(true), handler),
                projectTool("get_logs", "Return a bounded structured log delta after a session-aware cursor.",
                            logProperties(false), handler),
                projectTool("get_test_status", "Return background test and startup-command status.", Map.of(), handler),
                projectTool("wait_for_change", "Wait for matching events or active-problem transitions after a cursor. "
                            + "Apply problemChanges by id; use get_active_problems for full details or on "
                            + "activeProblemCount mismatch. After edits, pass sessionId and afterSequence from "
                            + "the prior cursor. Drain hasMore immediately using the returned cursor.",
                            logProperties(true), handler));
    }

    private static McpServerFeatures.SyncToolSpecification projectTool(
            String name, String description, Map<String, Object> properties,
            java.util.function.Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return new McpServerFeatures.SyncToolSpecification(definition(name, description, properties, false),
                                                           (exchange, request) -> handler.apply(request));
    }

    static McpServerFeatures.SyncToolSpecification tool(
            String name, String description, Map<String, Object> properties,
            java.util.function.Function<Map<String, Object>, Object> handler, ObjectMapper objectMapper,
            boolean openWorld) {
        McpSchema.Tool tool = definition(name, description, properties, openWorld);
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Object result = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                        return result(result, objectMapper);
                    } catch (RuntimeException e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    private static McpSchema.Tool definition(String name, String description,
                                             Map<String, Object> properties, boolean openWorld) {
        return McpSchema.Tool.builder(
                        name, Map.of("type", "object", "properties", properties, "additionalProperties", false))
                .description(description)
                .annotations(McpSchema.ToolAnnotations.builder()
                                     .readOnlyHint(true)
                                     .destructiveHint(false)
                                     .idempotentHint(true)
                                     .openWorldHint(openWorld)
                                     .build())
                .build();
    }

    static McpSchema.Resource diagnosticsResource() {
        return McpSchema.Resource.builder(DevMcpServer.DIAGNOSTICS_RESOURCE, "active-diagnostics")
                .title("Fluxzero active diagnostics")
                .description("Current unresolved problems for all services and app instances in the environment.")
                .mimeType("application/json").build();
    }

    static McpServerFeatures.SyncResourceSpecification diagnosticsResource(
            AgentQueryService queryService, ObjectMapper objectMapper) {
        McpSchema.Resource resource = diagnosticsResource();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            String json = json(queryService.getActiveProblems(AgentSelector.all(), AgentQueryService.MAX_LIMIT),
                               objectMapper);
            return McpSchema.ReadResourceResult.builder(List.of(
                    McpSchema.TextResourceContents.builder(DevMcpServer.DIAGNOSTICS_RESOURCE, json)
                            .mimeType("application/json")
                            .build())).build();
        });
    }

    static McpSchema.CallToolResult result(Object result, ObjectMapper objectMapper) {
        Object structured = objectMapper.convertValue(result, Object.class);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json(result, objectMapper))
                .structuredContent(structured)
                .build();
    }

    static String json(Object value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MCP response", e);
        }
    }

    private static AgentCursor cursor(Map<String, Object> arguments, AgentQueryService queryService) {
        String sessionId = stringValue(arguments.get("sessionId"));
        Object sequence = arguments.get("afterSequence");
        if (sessionId == null && sequence == null) {
            return null;
        }
        AgentCursor current = queryService.getStatus().cursor();
        return new AgentCursor(sessionId == null ? current.sessionId() : sessionId,
                               longValue(arguments, "afterSequence", 0));
    }

    private static AgentSelector selector(Map<String, Object> arguments) {
        String level = stringValue(arguments.get("minimumLevel"));
        DevLogEvent.Level minimumLevel = level == null ? null : DevLogEvent.Level.valueOf(level.toUpperCase());
        return new AgentSelector(strings(arguments.get("serviceIds")), strings(arguments.get("instanceIds")),
                                 strings(arguments.get("sources")), minimumLevel);
    }

    private static Map<String, Object> selectorProperties(boolean includeLimit) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("serviceIds", stringArray("Filter by application or infrastructure service id."));
        properties.put("instanceIds", stringArray("Filter by concrete application instance id."));
        properties.put("sources", stringArray("Filter by subsystem, for example app, compile, test, or runtime."));
        properties.put("minimumLevel", Map.of("type", "string", "enum", List.of("TRACE", "DEBUG", "INFO",
                                                                                  "WARN", "ERROR")));
        if (includeLimit) {
            properties.put("limit", integer("Maximum results, capped at " + AgentQueryService.MAX_LIMIT + "."));
        }
        return properties;
    }

    private static Map<String, Object> logProperties(boolean includeTimeout) {
        Map<String, Object> properties = new LinkedHashMap<>(selectorProperties(true));
        properties.put("sessionId", Map.of("type", "string", "description", "Cursor session id."));
        properties.put("afterSequence", integer("Return events after this sequence."));
        if (includeTimeout) {
            properties.put("timeoutMs", integer("Wait timeout in milliseconds, capped at 30000."));
        }
        return properties;
    }

    private static Map<String, Object> stringArray(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "minimum", 0, "description", description);
    }

    private static Set<String> strings(Object value) {
        if (value == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> result.add(String.valueOf(item)));
        } else {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private static int intValue(Map<String, Object> arguments, String name, int defaultValue) {
        return Math.toIntExact(longValue(arguments, name, defaultValue));
    }

    private static long longValue(Map<String, Object> arguments, String name, long defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

}
