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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MonitoringQueryServiceTest {
    @TempDir Path project;
    final ObjectMapper json = new ObjectMapper();
    HttpServer server;
    MonitoringQueryService service;
    AtomicReference<DevSession> session = new AtomicReference<>();
    AtomicReference<JsonNode> request = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>(), namespace = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    Map<String,String> responses = new java.util.HashMap<>();
    String response = "{}";
    int status = 200;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath());
            namespace.set(exchange.getRequestHeaders().getFirst(DevNamespaceHeader.NAME));
            byte[] body = exchange.getRequestBody().readAllBytes(); request.set(body.length == 0 ? null : json.readTree(body));
            byte[] output = responses.getOrDefault(exchange.getRequestURI().getPath(), response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, output.length);
            try (var out = exchange.getResponseBody()) { out.write(output); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        session.set(DevSession.empty(DevServerConfig.defaults(project))
                .withProxy(new DevSession.ServiceStatus("proxy", "running", url, null, null, null))
                .withGateway(new DevSession.ServiceStatus("gateway", "running", url, null, null, null))
                .withServices(Map.of("monitoring-auditlog", new DevSession.ServiceStatus("monitoring-auditlog", "running", null, null, null, null))));
        service = new MonitoringQueryService(session::get);
    }
    @AfterEach void close() { server.stop(0); }

    @Test void searchUsesFixedNamespaceBoundedRangeAndSummaryPagination() {
        response = """
                {"data":[{"messageIndex":"10L","messageType":"COMMAND","payload":"private payload","metadata":{"$traceId":"trace"}},
                         {"messageIndex":"11L"}],"totalCount":900}
                """;
        var result = service.query("search_audit_trail", Map.of("limit",1,"offset",2,"traceId","trace"));
        assertEquals("/logs/search", path.get()); assertEquals(DevConsole.NAMESPACE, namespace.get());
        assertEquals("io.fluxzero.auditlog.publishers.api.SearchLog", request.get().get(0).asText());
        var body = request.get().get(1);
        assertEquals(2, body.path("pagination").path("count").asInt());
        assertEquals(2, body.path("pagination").path("from").asInt());
        assertFalse(body.path("forceFlush").asBoolean());
        assertTrue(body.path("sortableFilters").get(0).has("min"));
        assertEquals(true, result.get("hasMore")); assertEquals(3, result.get("nextOffset"));
        assertFalse(result.toString().contains("private payload")); assertFalse(result.containsKey("totalCount"));
        assertTrue(result.toString().contains("trace"));
        assertEquals(project.toString(), result.get("projectDirectory"));
    }

    @Test void applicationLogScopeIsAlwaysApplied() {
        response = "{\"data\":[]}";
        service.query("search_application_logs", Map.of("level","ERROR","application","orders"));
        String query = request.get().get(1).toString();
        assertTrue(query.contains("application_logs")); assertTrue(query.contains("orders")); assertTrue(query.contains("ERROR"));
    }

    @Test void detailIsExplicitAndSanitizesEmbeddedJsonAndCredentials() {
        response = """
                {"content":"{\\"metadata\\":{\\"Authorization\\":\\"secret value\\"},\\"payload\\":{\\"password\\":\\"hidden\\",\\"name\\":\\"visible\\"}}"}
                """;
        var result = service.query("get_message", Map.of("messageType","COMMAND","messageIndex","123L"));
        assertEquals("123", request.get().get(1).path("messageIndex").asText());
        assertEquals(true, result.get("redacted")); assertTrue(result.toString().contains("visible"));
        assertFalse(result.toString().contains("secret value")); assertFalse(result.toString().contains("hidden"));
    }

    @Test void tracesAndCollectionsPageWithoutDroppingTheirIdentity() {
        response = "{\"branches\":[{\"branchId\":\"a\"},{\"branchId\":\"b\"}],\"data\":[{\"messageIndex\":\"10L\"},{\"messageIndex\":\"11L\"}]}";
        var result = service.query("get_trace", Map.of("traceId","trace","offset",1,"limit",1));
        assertEquals("a", ((JsonNode)result.get("data")).path("branches").get(0).path("branchId").asText());
        assertEquals("10L", ((JsonNode)result.get("data")).path("messages").get(0).path("messageIndex").asText());
        assertEquals(2,result.get("nextOffset"));
        response = "{\"namespace\":\"public\",\"collections\":[{\"name\":\"Orders\"}]}";
        result = service.query("list_document_collections",Map.of());
        assertEquals("public",result.get("namespace")); assertEquals(false,result.get("hasMore"));
    }

    @Test void documentContentRequiresOptInAndExactIdUsesStructuredFilter() {
        response = "{\"documents\":[{\"id\":\"abc\",\"document\":{\"token\":\"hidden\",\"name\":\"Order\"}}],\"hasMore\":true}";
        var args = Map.<String,Object>of("collection","Orders","documentId","abc","limit",1);
        var result = service.query("search_documents",args);
        assertFalse(result.toString().contains("Order\"")); assertEquals(1,result.get("nextOffset"));
        assertEquals("$id",request.get().get(1).path("filters").get(0).path("field").asText());
        result = service.query("search_documents",Map.of("collection","Orders","includeContent",true));
        assertTrue(result.toString().contains("Order")); assertFalse(result.toString().contains("hidden"));
    }

    @Test void issueListsExposeLimitsInsteadOfPretendingToHaveAnOffset() {
        response = "[{\"issueId\":\"a\"},{\"issueId\":\"b\"}]";
        var result = service.query("list_issues",Map.of("limit",1));
        assertEquals(true,result.get("limitReached")); assertFalse(result.containsKey("nextOffset"));
        assertEquals("OPEN",request.get().get(1).path("statuses").get(0).asText());
    }

    @Test void workspaceResourcesUseDevboardSamplesAndNotOptionalMetricsService() {
        response = "{\"components\":[{\"id\":\"app\",\"memoryBytes\":123}],\"monitoring\":{\"resources\":{\"storageBytes\":456}}}";
        var result = service.query("get_resource_metrics",Map.of());
        assertEquals("/_fluxzero/dev/status.json",path.get()); assertNull(request.get());
        assertTrue(result.toString().contains("456")); assertEquals("workspace",result.get("scope"));
    }

    @Test void unavailableAndFailuresAreNotEmptySuccessesOrBackendBodyLeaks() {
        response = "backend secret value"; status=500;
        var error = assertThrows(IllegalStateException.class, () -> service.query("list_issues",Map.of()));
        assertTrue(error.getMessage().contains("HTTP 500")); assertFalse(error.getMessage().contains("secret"));
        session.set(session.get().withServices(Map.of()));
        error = assertThrows(IllegalStateException.class, () -> service.query("list_issues",Map.of()));
        assertTrue(error.getMessage().contains("monitoring-unavailable")); assertEquals(1,calls.get());
    }

    @Test void rejectsUnboundedInputsAndArbitraryRoutesBeforeNetwork() {
        assertThrows(IllegalArgumentException.class, () -> service.query("search_audit_trail",Map.of("limit",101)));
        assertThrows(IllegalArgumentException.class, () -> service.query("search_audit_trail",Map.of("start","2025-01-01T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> service.query("search_audit_trail",Map.of("url","https://example.com")));
        assertThrows(IllegalArgumentException.class, () -> service.query("get_message",Map.of()));
        session.set(session.get().withProxy(new DevSession.ServiceStatus("proxy","running","https://example.com",null,null,null)));
        assertThrows(IllegalStateException.class, () -> service.query("list_issues",Map.of()));
        assertEquals(0,calls.get());
    }

    @Test void issueDetailsIncludeOccurrencesWithoutMutationRoutes() {
        responses.put("/logs/issues/get", "{\"issueId\":\"issue\",\"status\":\"OPEN\"}");
        responses.put("/logs/issues/entries", "[{\"traceId\":\"trace\"},{\"traceId\":\"older\"}]");
        var result = service.query("get_issue",Map.of("issueId","issue","limit",1));
        var data = (JsonNode) result.get("data");
        assertEquals("issue",data.path("issue").path("issueId").asText());
        assertEquals("trace",data.path("entries").get(0).path("traceId").asText());
        assertEquals(true,result.get("limitReached")); assertEquals(2,calls.get());
    }

    @Test void traceWithoutBranchesStillReturnsMessages() {
        responses.put("/logs/branches", "{\"branches\":[]}");
        responses.put("/logs/search", "{\"data\":[{\"messageIndex\":\"12L\"}]}");
        var result = service.query("get_trace",Map.of("traceId","trace"));
        assertEquals(1,((JsonNode)result.get("data")).path("messages").size());
        assertEquals(false,result.get("hasMore"));
    }

    @Test void explicitlyRequestedStoredLogDetailsAreRedacted() {
        response = "{\"data\":[{\"messageIndex\":\"1L\",\"payload\":{\"message\":\"failure detail\",\"apiKey\":\"hidden\"}}]}";
        var result = service.query("search_application_logs",Map.of("includeDetails",true));
        assertTrue(result.toString().contains("failure detail")); assertFalse(result.toString().contains("hidden"));
        assertEquals(true,result.get("redacted"));
    }

    @Test void rejectsOversizedAndNonJsonResponsesWithoutLeakingThem() {
        response = "x".repeat(4*1024*1024+1);
        assertThrows(IllegalStateException.class, () -> service.query("get_insights",Map.of()));
        response = "<html>private failure</html>";
        var error = assertThrows(IllegalStateException.class, () -> service.query("get_insights",Map.of()));
        assertFalse(error.getMessage().contains("private failure"));
    }

    @Test void insightsStartWithApplicationTotalsAndAllowFocusedDetails() {
        response = "[{\"application\":\"orders\",\"errorCount\":2,\"consumers\":[{\"consumer\":\"payments\",\"handleDurationHistogram\":\"encoded\"}]},{\"application\":\"mail\",\"errorCount\":0}]";
        var summary = service.query("get_insights",Map.of("application","orders"));
        var rows = (JsonNode) summary.get("data");
        assertEquals(1,rows.size()); assertEquals(2,rows.get(0).path("errorCount").asInt());
        assertFalse(rows.get(0).has("consumers"));
        var details = service.query("get_insights",Map.of("application","orders","includeDetails",true));
        assertTrue(details.toString().contains("payments")); assertFalse(details.toString().contains("encoded"));
    }

    @Test void sanitizerMarksOversizedResultsAndHandlesNestedSecretFields() throws Exception {
        var sanitizer = new MonitoringResultSanitizer();
        var result = sanitizer.sanitize(json.readTree("{\"text\":\"" + "x".repeat(10000) + "\",\"nested\":{\"api_key\":\"hidden\"}}"));
        assertTrue(sanitizer.truncated()); assertTrue(sanitizer.redacted()); assertFalse(result.toString().contains("hidden"));
        assertTrue(result.toString().length()<4500);
    }
}
