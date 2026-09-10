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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Project-aware selective retrieval; never selects documentation from the shared runtime override. */
final class AgentDocsService implements AutoCloseable {
    private final Supplier<DevServerConfig> config;
    private final AgentDocsStore store;

    AgentDocsService(Supplier<DevServerConfig> config, AgentDocsStore store) {
        this.config = config;
        this.store = store;
    }

    Object start(Map<String, Object> arguments) {
        List<Project> projects = projects(arguments);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespaces", store.namespaces().stream().sorted().toList());
        result.put("projects", projects);
        result.put("instructions", "Select the project and exact component version. Search or look up a symbol, read "
                                   + "one article, and follow only relevant links. Never upgrade a project to match docs.");
        Selection selection;
        try {
            selection = select(arguments, projects);
        } catch (SelectionRequired e) {
            result.put("status", "selection-required");
            result.put("detail", e.getMessage());
            return result;
        }
        AgentDocsStore.Loaded loaded = store.load(selection.identity());
        AgentDocsGraph graph = loaded.graph();
        result.putAll(metadata(loaded, selection));
        result.put("status", "ready");
        result.put("articleCount", graph.size());
        result.put("root", summary(graph, graph.article(graph.root())));
        result.put("links", links(graph, graph.article(graph.root()), 0, 20));
        return result;
    }

    Object search(Map<String, Object> arguments, boolean symbol) {
        String query = required(arguments, symbol ? "symbol" : "query", 256);
        int limit = bounded(arguments, "limit", 5, 1, 20);
        Selection selection = select(arguments, projects(arguments));
        AgentDocsStore.Loaded loaded = store.load(selection.identity());
        AgentDocsGraph graph = loaded.graph();
        List<AgentDocsGraph.Article> found = symbol ? graph.lookup(query, limit + 1) : graph.search(query, limit + 1);
        Map<String, Object> result = metadata(loaded, selection);
        result.put("results", found.stream().limit(limit).map(article -> summary(graph, article)).toList());
        result.put("hasMore", found.size() > limit);
        return result;
    }

    Object read(Map<String, Object> arguments, boolean onlyLinks) {
        String path = AgentDocsGraph.path(required(arguments, "path", 512));
        int offset = bounded(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = onlyLinks ? bounded(arguments, "limit", 20, 1, 50)
                : bounded(arguments, "maxChars", 12_000, 1, 24_000);
        Selection selection = select(arguments, projects(arguments));
        AgentDocsStore.Loaded loaded = store.load(selection.identity());
        AgentDocsGraph graph = loaded.graph();
        AgentDocsGraph.Article article = graph.article(path);
        Map<String, Object> result = metadata(loaded, selection);
        result.putAll(summary(graph, article));
        if (onlyLinks) {
            if (offset > article.links().size()) {
                throw new IllegalArgumentException("Link offset exceeds the number of links");
            }
            result.put("links", links(graph, article, offset, limit));
            int end = Math.min(article.links().size(), offset + limit);
            result.put("hasMore", end < article.links().size());
            if (end < article.links().size()) {
                result.put("nextOffset", end);
            }
        } else {
            String content = article.content();
            if (offset > content.length() || offset > 0 && offset < content.length()
                                               && Character.isLowSurrogate(content.charAt(offset))) {
                throw new IllegalArgumentException("Invalid article character offset");
            }
            int end = (int) Math.min(content.length(), (long) offset + limit);
            if (end < content.length() && end > offset && Character.isHighSurrogate(content.charAt(end - 1))) {
                end--;
            }
            if (end == offset && end < content.length()) {
                throw new IllegalArgumentException("maxChars is too small for the next Unicode character");
            }
            result.put("content", content.substring(offset, end));
            result.put("offset", offset);
            result.put("hasMore", end < content.length());
            if (end < content.length()) {
                result.put("nextOffset", end);
            }
            result.put("links", links(graph, article, 0, 10));
            result.put("linkCount", article.links().size());
        }
        return result;
    }

    private List<Project> projects(Map<String, Object> arguments) {
        try {
            return config.get().projects().stream().map(project -> {
                Set<String> versions = FluxzeroSdkVersionDetector.detectDeclared(project.directory());
                return new Project(project.id(), project.directory().toString(), versions.stream().sorted().toList());
            }).toList();
        } catch (RuntimeException e) {
            // Explicit retrieval remains usable while an agent repairs invalid project configuration.
            if (optional(arguments, "version", 128) != null && optional(arguments, "projectId", 256) == null) {
                return List.of();
            }
            throw new SelectionRequired("Cannot inspect project configuration. Select an exact documentation version "
                    + "without projectId while repairing the configuration.");
        }
    }

    private Selection select(Map<String, Object> arguments, List<Project> projects) {
        String namespace = optional(arguments, "namespace", 64);
        namespace = namespace == null ? "sdk" : namespace;
        if (!store.namespaces().contains(namespace)) {
            throw new IllegalArgumentException("Unavailable documentation namespace: " + namespace);
        }
        String requestedProjectId = optional(arguments, "projectId", 256);
        String projectId = requestedProjectId;
        Project selected = projectId == null ? null : projects.stream().filter(p -> p.id().equals(requestedProjectId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown documentation project: " + requestedProjectId));
        String version = optional(arguments, "version", 128);
        if (version != null) {
            if (selected != null && namespace.equals("sdk") && !selected.sdkVersions().isEmpty()
                && !selected.sdkVersions().contains(version)) {
                throw new IllegalArgumentException("Requested documentation version does not match the selected project. "
                                                   + "Omit projectId for an explicit version comparison.");
            }
            return new Selection(new AgentDocsGraph.Identity(namespace, version), projectId, "explicit-version", null);
        }
        if (!namespace.equals("sdk")) {
            throw new SelectionRequired("Select an explicit version for namespace " + namespace);
        }
        List<Project> candidates = selected == null ? projects : List.of(selected);
        Set<String> versions = candidates.stream().flatMap(p -> p.sdkVersions().stream()).collect(
                java.util.stream.Collectors.toSet());
        if (versions.isEmpty() && candidates.size() <= 1) {
            AgentDocsReleases.Release release = store.latest(namespace);
            return new Selection(new AgentDocsGraph.Identity(namespace, release.version()), projectId,
                                 "latest-release", release);
        }
        if (candidates.stream().anyMatch(p -> p.sdkVersions().isEmpty())) {
            throw new SelectionRequired("Several projects include unknown SDK versions. Select projectId or version.");
        }
        if (versions.size() != 1) {
            throw new SelectionRequired("Several project SDK versions are available. Select projectId and/or version "
                                        + "explicitly; documentation does not use the highest shared runtime version.");
        }
        if (projectId == null && candidates.size() == 1) {
            projectId = candidates.getFirst().id();
        }
        return new Selection(new AgentDocsGraph.Identity(namespace, versions.iterator().next()), projectId,
                             "project-sdk", null);
    }

    private static Map<String, Object> metadata(AgentDocsStore.Loaded loaded, Selection selection) {
        AgentDocsGraph graph = loaded.graph();
        Map<String, Object> result = identity(graph, null);
        result.put("sourceCommit", graph.sourceCommit());
        result.put("contentHash", graph.contentHash());
        result.put("source", loaded.source());
        result.put("selection", selection.source());
        if (selection.release() != null) {
            result.put("releaseResolution", Map.of("source", selection.release().source(),
                    "checkedAt", selection.release().checkedAt().toString()));
        }
        if (selection.projectId() != null) {
            result.put("projectId", selection.projectId());
        }
        return result;
    }

    private static Map<String, Object> summary(AgentDocsGraph graph, AgentDocsGraph.Article article) {
        Map<String, Object> result = identity(graph, article.path());
        result.put("title", compact(article.title(), 240));
        result.put("summary", compact(article.summary(), 1000));
        return result;
    }

    private static List<Map<String, Object>> links(AgentDocsGraph graph, AgentDocsGraph.Article article,
                                                   int offset, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        article.links().stream().skip(offset).limit(limit).forEach(link -> {
            Map<String, Object> item = identity(graph, link.path());
            item.put("description", compact(link.description(), 400));
            result.add(item);
        });
        return result;
    }

    private static Map<String, Object> identity(AgentDocsGraph graph, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespace", graph.identity().namespace());
        result.put("version", graph.identity().version());
        if (path != null) {
            result.put("path", path);
        }
        return result;
    }

    private static String compact(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length) + "…";
    }

    private static String required(Map<String, Object> arguments, String key, int maximum) {
        String result = optional(arguments, key, maximum);
        if (result == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return result;
    }

    private static String optional(Map<String, Object> arguments, String key, int maximum) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(key + " must be a nonempty string of at most " + maximum + " characters");
        }
        return text.strip();
    }

    private static int bounded(Map<String, Object> arguments, String key, int fallback, int min, int max) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
            || number.longValue() < min) {
            throw new IllegalArgumentException(key + " must be an integer at least " + min);
        }
        return (int) Math.min(max, number.longValue());
    }

    @Override
    public void close() { store.close(); }

    record Project(String id, String directory, List<String> sdkVersions) {}
    private record Selection(AgentDocsGraph.Identity identity, String projectId, String source,
                             AgentDocsReleases.Release release) {}
    private static final class SelectionRequired extends IllegalArgumentException {
        private SelectionRequired(String message) { super(message); }
    }
}
