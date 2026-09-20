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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentWorkflowsTest {
    @Test void allPublishedTopicsAreAvailableWithoutAProject() {
        for (String topic : AgentWorkflows.TOPICS) {
            var workflow = AgentWorkflows.read(Map.of("topic", topic));
            assertEquals(DevServerVersion.current(), workflow.get("version"));
            assertFalse(workflow.get("content").toString().isBlank());
        }
        assertEquals("overview", AgentWorkflows.read(Map.of()).get("topic"));
    }
    @Test void noActiveProjectReturnsLabelledGuidanceWithoutStartingAnything(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        try (var client = new DevMcpProjectClient(directory, new com.fasterxml.jackson.databind.ObjectMapper())) {
            var result = client.workflow(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    "get_workflow", Map.of("topic", "setup")));
            assertFalse(Boolean.TRUE.equals(result.isError()));
            assertEquals("stdio-distribution", ((Map<?, ?>) result.structuredContent()).get("source"));
            assertFalse(java.nio.file.Files.exists(directory.resolve(".fluxzero/dev/session.json")));
        }
    }

    @Test void rejectsUnknownTopicsAndTraversal() {
        assertThrows(IllegalArgumentException.class, () -> AgentWorkflows.read(Map.of("topic", "../logback")));
        assertThrows(IllegalArgumentException.class, () -> AgentWorkflows.read(Map.of("topic", 42)));
    }
}
