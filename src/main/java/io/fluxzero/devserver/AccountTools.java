/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

final class AccountTools {
    private AccountTools() {}

    static List<McpServerFeatures.SyncToolSpecification> tools(DevAccount account, ObjectMapper mapper) {
        return List.of(tool("get_account", "Read non-secret local account status. Does not contact Cloud, access credentials, "
                        + "open dialogs or start development. Account login is optional for local development.", "status", true, account, mapper),
                tool("start_login", "Start optional Fluxzero account sign-in in this computer's browser after user intent. "
                        + "Returns immediately; poll get_account. No credentials are returned. Does not change the local app's identity or data.", "login", false, account, mapper),
                tool("logout", "Sign out of Fluxzero account features in CLI and Devboards on this computer. "
                        + "Local development and the application's own login are unaffected.", "logout", false, account, mapper));
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, String description, String action,
                                                               boolean readOnly, DevAccount account, ObjectMapper mapper) {
        var definition = McpSchema.Tool.builder(name, Map.of("type", "object", "properties",
                "start_login".equals(name) ? Map.of("useAnotherAccount", Map.of("type", "boolean", "description",
                        "Request a fresh sign-in after the user chooses another account.")) : Map.of(), "additionalProperties", false))
                .description(description).annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly)
                        .destructiveHint("logout".equals(name)).idempotentHint(readOnly || "logout".equals(name)).openWorldHint(!readOnly).build()).build();
        return new McpServerFeatures.SyncToolSpecification(definition, (exchange, request) -> {
            var arguments = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
            if (!arguments.isEmpty() && (!"start_login".equals(name) || !arguments.keySet().equals(java.util.Set.of("useAnotherAccount"))
                    || !(arguments.get("useAnotherAccount") instanceof Boolean)))
                return McpSchema.CallToolResult.builder().isError(true).addTextContent("Use only the supported account options.").build();
            return DevMcpTools.result(account.call(Boolean.TRUE.equals(arguments.get("useAnotherAccount")) ? "login-another" : action), mapper);
        });
    }
}
