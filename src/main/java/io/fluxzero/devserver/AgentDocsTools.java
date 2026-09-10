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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentDocsTools {
    private AgentDocsTools() {}

    static List<McpServerFeatures.SyncToolSpecification> tools(AgentDocsService docs, ObjectMapper mapper) {
        return List.of(
                DevMcpTools.tool("docs_start", "Discover documentation namespaces, project SDK versions and the selected "
                                               + "graph entry point. Does not read the whole corpus.", properties(),
                                  docs::start, mapper, true),
                DevMcpTools.tool("docs_search", "Search the selected graph; return bounded titles and summaries.",
                                  properties("query", string("Search terms, at most 256 characters."),
                                             "limit", integer(1, 20, "Maximum results; default 5.")),
                                  arguments -> docs.search(arguments, false), mapper, true),
                DevMcpTools.tool("docs_lookup_symbol", "Find articles for an exact SDK symbol such as @LocalOnly.",
                                  properties("symbol", string("Exact symbol, at most 256 characters."),
                                             "limit", integer(1, 20, "Maximum results; default 5.")),
                                  arguments -> docs.search(arguments, true), mapper, true),
                DevMcpTools.tool("docs_read", "Read one article from the selected graph. Follow nextOffset for more text.",
                                  properties("path", string("Logical article path returned by the docs tools."),
                                             "offset", integer(0, Integer.MAX_VALUE, "Character offset; default 0."),
                                             "maxChars", integer(1, 24_000, "Maximum characters; default 12000.")),
                                  arguments -> docs.read(arguments, false), mapper, true),
                DevMcpTools.tool("docs_links", "List a bounded page of links, retaining namespace and version identity.",
                                  properties("path", string("Logical article path returned by the docs tools."),
                                             "offset", integer(0, Integer.MAX_VALUE, "Link offset; default 0."),
                                             "limit", integer(1, 50, "Maximum links; default 20.")),
                                  arguments -> docs.read(arguments, true), mapper, true));
    }

    static Map<String, Object> properties(Object... additional) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespace", string("Documentation namespace; default sdk."));
        result.put("projectId", string("Project id returned by docs_start; never the runtime's highest-version choice."));
        result.put("version", string("Exact component version. Omit for the project SDK or latest release fallback; omit projectId for "
                                     + "an explicit version comparison."));
        for (int i = 0; i < additional.length; i += 2) {
            result.put((String) additional[i], additional[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(int min, int max, String description) {
        return Map.of("type", "integer", "minimum", min, "maximum", max, "description", description);
    }
}
