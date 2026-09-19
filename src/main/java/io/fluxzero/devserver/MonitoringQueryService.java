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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Adapts the bundled Auditlog API without exposing routes, type names or storage query access to callers. */
final class MonitoringQueryService {
    static final List<String> MESSAGE_TYPES = List.of("COMMAND", "EVENT", "QUERY", "RESULT", "ERROR", "WEBREQUEST",
            "WEBRESPONSE", "SCHEDULE", "NOTIFICATION", "CUSTOM", "METRICS");
    private static final List<String> AUDIT_TYPES = MESSAGE_TYPES.stream().filter(t -> !t.equals("METRICS")).toList();
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final String API = "io.fluxzero.auditlog.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Supplier<DevSession> sessions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    MonitoringQueryService(Supplier<DevSession> sessions) { this.sessions = sessions; }

    Map<String, Object> query(String tool, Map<String, Object> args) {
        var definition = MonitoringTools.definitions().stream().filter(d -> d.name().equals(tool)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown monitoring tool"));
        if (!definition.properties().keySet().containsAll(args.keySet())) throw new IllegalArgumentException("Unknown monitoring argument");
        DevSession session = sessions.get();
        var service = session.services().get("monitoring-auditlog");
        if (!tool.equals("get_resource_metrics") && (service == null || !"running".equals(service.state()) || !"running".equals(session.proxy().state()))) {
            throw new IllegalStateException("monitoring-unavailable: Devboard monitoring is disabled, stopped or still starting. Check get_status; this is not an empty result.");
        }
        int limit = integer(args, "limit", 25, 1, 100), offset = integer(args, "offset", 0, 0, 10000);
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1);
        result.put("projectDirectory", session.projectDirectory());
        result.put("sessionId", session.sessionId());
        result.put("source", "devboard");
        result.put("observedAt", Instant.now().toString());
        JsonNode data;
        switch (tool) {
            case "search_audit_trail", "search_application_logs" -> {
                boolean details = bool(args, "includeDetails");
                var window = window(args); result.put("window", window);
                var request = new LinkedHashMap<String, Object>();
                boolean logs = tool.equals("search_application_logs");
                request.put("messageTypes", logs ? MESSAGE_TYPES : messageTypes(args));
                request.put("term", optional(args, "query"));
                request.put("pagination", Map.of("from", offset, "count", limit + 1));
                request.put("sortableFilters", List.of(Map.of("path", "timestamp", "type", "instant", "min", window.get("start"), "maxExclusive", window.get("end"))));
                var facets = new ArrayList<Map<String, Object>>();
                facet(facets, "metadata.$traceId", optional(args, "traceId"));
                facet(facets, "application", optional(args, "application"));
                if (logs) {
                    facet(facets, "fzc.logCategory", "application_logs");
                    String level = optional(args, "level");
                    if (level != null && !Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(level)) throw new IllegalArgumentException("Invalid level");
                    facet(facets, "level", level);
                }
                request.put("facetFilters", facets); request.put("forceFlush", false);
                var response = post(session, "/logs/search", "publishers.api.SearchLog", request);
                var rows = array(response, "data");
                var selected = page(rows, offset, limit, false, result);
                data = details ? selected : summaries(selected);
                result.put("detailsIncluded", details);
                result.put("detailsTool", "get_message");
                // SearchLog.totalCount describes the unfiltered scope, not necessarily the returned selection.
            }
            case "get_message" -> {
                String type = required(args, "messageType");
                if (!MESSAGE_TYPES.contains(type)) throw new IllegalArgumentException("Invalid messageType");
                var request = new LinkedHashMap<String, Object>();
                String index = required(args, "messageIndex");
                if (!index.matches("[0-9]+L?")) throw new IllegalArgumentException("Invalid messageIndex");
                request.put("messageIndex", index.replaceFirst("L$", "")); request.put("messageType", type);
                request.put("topic", optional(args, "topic")); request.put("previewOnly", false);
                data = post(session, "/logs/message-at-index", "publishers.api.GetMessageAtIndex", request);
            }
            case "get_trace" -> {
                String traceId = required(args, "traceId");
                var response = post(session, "/logs/branches", "publishers.api.SearchTraceBranches",
                        Map.of("traceId", traceId, "forceFlush", false));
                var branches = sorted(array(response, "branches"), "branchId");
                var messages = post(session, "/logs/search", "publishers.api.SearchLog", Map.of(
                        "messageTypes", MESSAGE_TYPES, "forceFlush", false,
                        "facetFilters", List.of(Map.of("facetName", "metadata.$traceId", "values", List.of(traceId))),
                        "pagination", Map.of("from", offset, "count", limit + 1)));
                var output = JSON.createObjectNode(); output.put("traceId", traceId);
                output.set("branches", page(branches, 0, limit, false, new LinkedHashMap<>()));
                output.put("branchesTruncated", branches.size() > limit);
                output.set("messages", summaries(page(array(messages, "data"), offset, limit, false, result)));
                data = output; result.put("detailsTool", "get_message");
            }
            case "list_issues" -> {
                var window = window(args); result.put("window", window);
                String status = optional(args, "status"); status = status == null ? "OPEN" : status;
                if (!Set.of("OPEN", "RESOLVED", "MUTED", "ALL").contains(status)) throw new IllegalArgumentException("Invalid issue status");
                var request = new LinkedHashMap<String, Object>();
                request.put("query", optional(args, "query")); request.put("statuses", status.equals("ALL") ? List.of() : List.of(status));
                request.put("size", limit + 1); request.put("from", window.get("start")); request.put("to", window.get("end"));
                var rows = post(session, "/logs/issues/list", "issues.api.GetIssues", request);
                data = page(requireArray(rows), 0, limit, false, result);
                result.put("limitReached", result.remove("hasMore")); result.remove("nextOffset");
            }
            case "get_issue" -> {
                String id = required(args, "issueId");
                var issue = post(session, "/logs/issues/get", "issues.api.GetIssue", Map.of("issueId", id));
                var entries = post(session, "/logs/issues/entries", "issues.api.GetIssueEntries", Map.of("issueId", id, "size", limit + 1));
                var output = JSON.createObjectNode(); output.set("issue", issue);
                output.set("entries", page(requireArray(entries), 0, limit, false, result));
                result.put("limitReached", result.remove("hasMore")); result.remove("nextOffset"); data = output;
            }
            case "resolve_issue", "reopen_issue", "mute_issue", "unmute_issue" -> {
                String id = required(args, "issueId");
                String action = tool.substring(0, tool.length() - "_issue".length());
                String type = switch (tool) {
                    case "resolve_issue" -> "ResolveIssue";
                    case "reopen_issue" -> "ReopenIssue";
                    case "mute_issue" -> "MuteIssue";
                    default -> "UnmuteIssue";
                };
                String expectedStatus = switch (tool) {
                    case "resolve_issue" -> "RESOLVED";
                    case "mute_issue" -> "MUTED";
                    default -> "OPEN";
                };
                try {
                    data = post(session, "/logs/issues/" + action, "issues.api." + type, Map.of("issueId", id));
                    if (!id.equals(data.path("issueId").asText()) || !expectedStatus.equals(data.path("status").asText())) {
                        throw new IllegalStateException("Backend did not confirm the requested issue status");
                    }
                } catch (RuntimeException e) {
                    throw new IllegalStateException("issue-update-unconfirmed: " + e.getMessage()
                            + ". The issue may have changed; call get_issue before deciding whether to retry.");
                }
                result.put("action", action);
            }
            case "get_insights" -> {
                var window = window(args); result.put("window", window);
                boolean details = bool(args, "includeDetails");
                String application = optional(args, "application");
                var response = post(session, "/logs/insights", "insights.api.GetApplicationMetrics", window);
                var selected = JSON.createArrayNode();
                for (JsonNode sample : requireArray(response)) {
                    if (application == null || application.equals(sample.path("application").asText())) selected.add(sample);
                }
                data = page(sorted(selected, "start", "application", "applicationId"), offset, limit, true, result);
                omitHistograms(data);
                if (!details) for (JsonNode sample : data) if (sample instanceof ObjectNode object) object.remove(List.of("consumers", "handlers"));
                result.put("detailsIncluded", details);
                result.put("histogramsOmitted", true);
            }
            case "get_resource_metrics" -> {
                var status = getWorkspace(session);
                var resources = JSON.createObjectNode();
                resources.set("components", status.path("components"));
                resources.set("monitoring", status.path("monitoring").path("resources"));
                resources.set("resourceHistory", status.path("resourceHistory"));
                data = resources; result.put("scope", "workspace");
            }
            case "list_document_collections" -> {
                var response = post(session, "/search-collections/list", "searchcollections.api.ListSearchCollections", Map.of());
                result.put("namespace", response.path("namespace").asText());
                data = page(sorted(array(response, "collections"), "name"), offset, limit, true, result);
            }
            case "search_documents" -> {
                if (offset % limit != 0) throw new IllegalArgumentException("Document offset must be a multiple of limit; use nextOffset with the same limit");
                var request = new LinkedHashMap<String, Object>();
                request.put("collection", required(args, "collection")); request.put("query", optional(args, "query"));
                request.put("page", offset / limit); request.put("pageSize", limit); request.put("timestampDescending", true);
                String id = optional(args, "documentId");
                if (id != null) request.put("filters", List.of(Map.of("field", "$id", "value", id, "include", true, "facet", false)));
                var response = post(session, "/search-collections/search", "searchcollections.api.SearchCollectionDocuments", request);
                data = array(response, "documents").deepCopy();
                if (!bool(args, "includeContent")) {
                    for (JsonNode row : data) if (row instanceof ObjectNode object) object.remove("document");
                    result.put("contentOmitted", true);
                }
                boolean more = response.path("hasMore").asBoolean(); result.put("hasMore", more);
                if (more) result.put("nextOffset", offset + limit);
            }
            default -> throw new IllegalArgumentException("Unknown monitoring tool");
        }
        if (!session.sessionId().equals(sessions.get().sessionId())) {
            throw new IllegalStateException(MonitoringTools.mutating(tool)
                    ? "issue-update-unconfirmed: Session changed; the issue may have changed. Check the selected project and get_issue before retrying."
                    : "monitoring-session-changed: Retry the query in the new session");
        }
        var bounded = new MonitoringResultSanitizer();
        result.put("data", bounded.sanitize(data));
        result.put("truncated", bounded.truncated());
        result.put("redacted", bounded.redacted());
        return result;
    }

    private JsonNode post(DevSession session, String path, String type, Object body) {
        URI base = localUri(session.proxy().url());
        try {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header(DevNamespaceHeader.NAME, DevConsole.NAMESPACE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(List.of(API + type, body)))).build();
            return send(request);
        } catch (IOException e) { throw new IllegalStateException("monitoring-invalid-request"); }
    }

    private JsonNode getWorkspace(DevSession session) {
        URI base = localUri(session.gateway().url());
        return send(HttpRequest.newBuilder(base.resolve(DevConsole.ROOT + "status.json")).timeout(Duration.ofSeconds(10)).GET().build());
    }

    private static URI localUri(String url) {
        URI base;
        try { base = URI.create(url); }
        catch (RuntimeException e) { throw new IllegalStateException("monitoring-unavailable: No local endpoint"); }
        if (!"http".equals(base.getScheme()) || !Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost())
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalStateException("monitoring-unavailable: Expected the managed loopback endpoint");
        }
        return base;
    }

    private JsonNode send(HttpRequest request) {
        try {
            var response = http.send(request, info -> new BoundedBody());
            if (response.statusCode() != 200) throw new IllegalStateException("monitoring-query-failed: Backend returned HTTP " + response.statusCode() + "; no data returned");
            JsonNode result = JSON.readTree(response.body());
            if (result == null) throw new IOException("Missing JSON");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("monitoring-query-interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("monitoring-unavailable: Backend timed out, is unreachable or returned invalid/oversized data. Check get_status or narrow the query");
        }
    }

    /** Enforces the byte limit while receiving, with the request timeout covering the complete body. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> body = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_BODY) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Response too large")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    private static ArrayNode sorted(JsonNode values, String... fields) {
        var rows = new ArrayList<JsonNode>(); values.forEach(rows::add);
        rows.sort((left, right) -> {
            for (String field : fields) {
                int comparison = left.path(field).asText().compareTo(right.path(field).asText());
                if (comparison != 0) return comparison;
            }
            return 0;
        });
        return JSON.createArrayNode().addAll(rows);
    }

    private static void omitHistograms(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (name.endsWith("Histogram")) object.remove(name);
                else omitHistograms(object.get(name));
            }
        } else if (node.isArray()) node.forEach(MonitoringQueryService::omitHistograms);
    }

    private static ArrayNode summaries(JsonNode rows) {
        var result = JSON.createArrayNode();
        for (JsonNode row : rows) {
            var summary = JSON.createObjectNode();
            for (String field : List.of("messageId", "messageIndex", "messageType", "timestamp", "simpleType", "source", "target",
                    "message", "level", "application", "topic", "metadata.$traceId", "metadata.$correlationId", "metadata.$trace.$branchId")) {
                if (row.has(field)) summary.set(field, row.get(field));
            }
            for (String field : List.of("$traceId", "$correlationId")) {
                if (row.path("metadata").has(field)) summary.set("metadata." + field, row.path("metadata").get(field));
            }
            result.add(summary);
        }
        return result;
    }
    private static ArrayNode page(JsonNode rows, int offset, int limit, boolean local, Map<String, Object> result) {
        requireArray(rows);
        int start = local ? Math.min(offset, rows.size()) : 0;
        int end = Math.min(start + limit, rows.size());
        boolean more = rows.size() > end;
        var data = JSON.createArrayNode(); for (int i = start; i < end; i++) data.add(rows.get(i));
        result.put("hasMore", more); if (more) result.put("nextOffset", offset + limit);
        return data;
    }
    private static JsonNode array(JsonNode response, String field) { return requireArray(response.path(field)); }
    private static JsonNode requireArray(JsonNode value) {
        if (!value.isArray()) throw new IllegalStateException("monitoring-invalid-response: Expected a result list; backend version may be incompatible");
        return value;
    }
    private static List<String> messageTypes(Map<String, Object> args) {
        Object value = args.get("messageTypes"); if (value == null) return AUDIT_TYPES;
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 11 || !MESSAGE_TYPES.containsAll(list)) throw new IllegalArgumentException("Invalid messageTypes");
        return ((List<?>) value).stream().map(Object::toString).toList();
    }
    private static void facet(List<Map<String, Object>> facets, String name, String value) {
        if (value != null) facets.add(Map.of("facetName", name, "values", List.of(value)));
    }
    private static Map<String, String> window(Map<String, Object> args) {
        try {
            String endText = optional(args, "end"), startText = optional(args, "start");
            Instant end = endText == null ? Instant.now() : Instant.parse(endText);
            Instant start = startText == null ? end.minus(Duration.ofHours(1)) : Instant.parse(startText);
            if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0) throw new IllegalArgumentException();
            return Map.of("start", start.toString(), "end", end.toString());
        } catch (RuntimeException e) { throw new IllegalArgumentException("Use ISO-8601 start/end instants with start < end and a window of at most 7 days"); }
    }
    private static String optional(Map<String, Object> args, String key) {
        Object value = args.get(key); if (value == null) return null;
        if (!(value instanceof String text) || text.length() > 2000) throw new IllegalArgumentException(key + " must be a string of at most 2000 characters");
        return text.isBlank() ? null : text;
    }
    private static String required(Map<String, Object> args, String key) {
        String value = optional(args, key); if (value == null) throw new IllegalArgumentException(key + " is required"); return value;
    }
    private static boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return false;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean");
        return (Boolean) value;
    }
    private static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args.get(key); if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max)
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        return n.intValue();
    }
}
