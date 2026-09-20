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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** Local console shell. Modules own their assets and API; the shell only hosts and navigates them. */
final class DevConsole implements AutoCloseable {
    static final String CAPABILITY = "devConsoleVersion";
    static final String ROOT = "/_fluxzero/dev/";
    static final String UPDATES = ROOT + "updates";
    static final String MONITORING = ROOT + "monitoring/";
    static final String API = ROOT + "api/monitoring";
    static final String NAMESPACE = "_fluxzero_monitoring";
    final DevConsoleUpdates updates;
    private final Path monitoringAssets;
    private final DevEnvironmentRegistry environments;
    private final FolderOpener folderOpener;
    private final DevConsoleProjectStarter projectStarter;
    private final DevConsoleProjects projects;
    private java.util.function.Function<String, Runnable> maintenance;
    private Supplier<java.util.List<TestCatalog.Case>> testCases = java.util.List::of;
    @FunctionalInterface interface StartupJson { Object get(String sessionId, String id, String hash) throws Exception; }
    private StartupJson startupJson = (sessionId, id, hash) -> null;
    DevConsole withStartupJson(StartupJson supplier) {this.startupJson = supplier; return this;}
    DevConsole withTestCases(Supplier<java.util.List<TestCatalog.Case>> testCases) {
        this.testCases = testCases; return this;
    }

    DevConsole withMaintenance(java.util.function.Consumer<String> maintenance) {
        this.maintenance = action -> { maintenance.accept(action); return () -> {}; }; return this;
    }
    DevConsole withDeferredMaintenance(java.util.function.Function<String, Runnable> maintenance) {
        this.maintenance = maintenance; return this;
    }
    @FunctionalInterface interface FolderOpener { void open(Path directory) throws java.io.IOException; }

    DevConsole(Supplier<Map<String, Object>> status, Path monitoringAssets) {
        this(status, monitoringAssets, DevEnvironmentRegistry.global());
    }

    DevConsole(Supplier<Map<String, Object>> status, Path monitoringAssets, DevEnvironmentRegistry environments) {
        this(status, monitoringAssets, environments, ProjectFolderOpener::open);
    }

    DevConsole(Supplier<Map<String, Object>> status, Path monitoringAssets, DevEnvironmentRegistry environments,
               FolderOpener folderOpener) {
        this.folderOpener = folderOpener;
        this.environments = environments;
        this.projectStarter = new DevConsoleProjectStarter(environments);
        this.projects = new DevConsoleProjects(environments);
        this.updates = new DevConsoleUpdates(status, environments::listKnown);
        this.monitoringAssets = monitoringAssets;
    }

    void start() { updates.start(); }
    @Override public void close() { updates.close(); projectStarter.close();
        projects.close(); }

    boolean isApi(Request request) {
        String path = request.getHttpURI().getPath();
        return monitoringAssets != null && path.startsWith(API + "/");
    }

    boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getDecodedPath();
        if (!path.equals(ROOT.substring(0, ROOT.length() - 1)) && !path.startsWith(ROOT)) {
            return false;
        }
        if (isApi(request)) return false;
        if (path.equals(ROOT + "startup-command.json")) {
            if (!"POST".equals(request.getMethod())) return actionResult(response, callback, 405, "Use POST.");
            if (!localConsoleRequest(request)) return actionResult(response, callback, 403, "Requires the local console.");
            try (var input = org.eclipse.jetty.io.Content.Source.asInputStream(request)) {
                byte[] body = input.readNBytes(8193);
                if (body.length > 8192) return actionResult(response, callback, 413, "Request too large.");
                var json = new ObjectMapper().readTree(body);
                if (json == null || !json.path("sessionId").isTextual() || !json.path("id").isTextual() || !json.path("hash").isTextual())
                    return actionResult(response, callback, 400, "Select a startup command.");
                Object value = startupJson.get(json.path("sessionId").asText(), json.path("id").asText(), json.path("hash").asText());
                if (value == null) return actionResult(response, callback, 404, "This command is no longer available. Refresh and try again.");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
                response.getHeaders().put("X-Content-Type-Options", "nosniff");
                response.write(true, ByteBuffer.wrap(new ObjectMapper().writeValueAsBytes(value)), callback);
                return true;
            }
        }
        if (path.startsWith(ROOT + "actions/")) return maintenanceAction(path, request, response, callback);
        if (path.startsWith(ROOT + "projects/")) return projectAction(path, request, response, callback);
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
            response.setStatus(405);
            response.getHeaders().put("Allow", "GET, HEAD");
            callback.succeeded();
            return true;
        }
        if (path.equals(ROOT.substring(0, ROOT.length() - 1))) {
            response.setStatus(302);
            response.getHeaders().put(HttpHeader.LOCATION, ROOT);
            callback.succeeded();
            return true;
        }
        byte[] content;
        String type;
        if (path.equals(ROOT + "status.json")) {
            content = new ObjectMapper().writeValueAsBytes(updates.snapshot().get("status"));
            type = "application/json";
        } else if (path.equals(ROOT + "tests.json")) {
            var query = Request.extractQueryParameters(request);
            content = new ObjectMapper().writeValueAsBytes(TestCatalog.page(testCases.get(),
                    query.getValue("state"), query.getValue("q"), query.getValue("offset"),
                    "true".equals(query.getValue("grouped")), query.getValue("group")));
            type = "application/json";
        } else if (path.equals(ROOT + "environments.json")) {
            content = new ObjectMapper().writeValueAsBytes(Map.of("environments", environments.listKnown()));
            type = "application/json";
        } else if (path.startsWith(MONITORING) && monitoringAssets != null) {
            Path root = monitoringAssets.toRealPath();
            String suffix = path.substring(MONITORING.length());
            Path file = root.resolve(suffix).normalize();
            if (!file.startsWith(root) || (Files.exists(file) && !file.toRealPath().startsWith(root))) {
                response.setStatus(404); callback.succeeded(); return true;
            }
            if (!Files.isRegularFile(file)) {
                if (suffix.contains(".")) { response.setStatus(404); callback.succeeded(); return true; }
                file = root.resolve("index.html");
            }
            content = Files.readAllBytes(file);
            type = contentType(file.getFileName().toString());
            if (file.getFileName().toString().equals("index.html")) {
                String html = new String(content, StandardCharsets.UTF_8)
                        .replaceAll("<base href=\"[^\"]*\"[^>]*>", "<base href=\"" + MONITORING + "\">");
                html = html.replace("<head>", "<head><script>window.fluxzeroHost={kind:'dev',apiBase:'"
                        + API + "',navigation:'host',capabilities:['messages','logs','trace','issues','documents','insights','visualize']};</script>");
                content = html.getBytes(StandardCharsets.UTF_8);
            }
        } else {
            String name = path.equals(ROOT) ? "index.html" : path.substring(ROOT.length());
            if (!name.matches("[a-zA-Z0-9_./-]+") || name.contains("..")) {
                response.setStatus(404); callback.succeeded(); return true;
            }
            try (var input = DevConsole.class.getResourceAsStream("/dev-console/" + name)) {
                if (input == null) { response.setStatus(404); callback.succeeded(); return true; }
                content = input.readAllBytes();
            }
            type = contentType(name);
        }
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, type);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.write(true, request.getMethod().equals("HEAD") ? null : ByteBuffer.wrap(content), callback);
        return true;
    }

    private boolean maintenanceAction(String path, Request request, Response response, Callback callback) throws Exception {
        if (!"POST".equals(request.getMethod())) {
            response.getHeaders().put("Allow", "POST");
            return actionResult(response, callback, 405, "Use POST for maintenance actions.");
        }
        if (!localConsoleRequest(request)) return actionResult(response, callback, 403, "Maintenance requires the local console.");
        String action = path.substring((ROOT + "actions/").length());
        if (maintenance == null || !java.util.Set.of("truncate-testserver-data", "clear-monitoring-storage", "truncate-data", "restart-devserver", "update-devserver", "restart-application", "restart-app", "clear-test-output", "run-tests", "pause-tests", "resume-tests", "stop-workspace", "start-workspace", "stop-devserver", "pause-builds", "resume-builds", "switch-profile").contains(action))
            return actionResult(response, callback, 404, "Unknown maintenance action.");
        if ("update-devserver".equals(action)) {
            try (var input = org.eclipse.jetty.io.Content.Source.asInputStream(request)) {
                byte[] body = input.readNBytes(1025);
                if (body.length > 1024) return actionResult(response, callback, 413, "Update request is too large.");
                var json = new ObjectMapper().readTree(body);
                if (json == null || !json.path("version").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
                    return actionResult(response, callback, 400, "Select a released version.");
                action += ":" + json.path("version").asText();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return actionResult(response, callback, 400, "Select a released version.");
            }
        }
        if ("switch-profile".equals(action)) {
            try (var input = org.eclipse.jetty.io.Content.Source.asInputStream(request)) {
                byte[] body = input.readNBytes(4097);
                if (body.length > 4096) return actionResult(response, callback, 413, "Profile request is too large.");
                var json = new ObjectMapper().readTree(body);
                if (json == null || !json.path("profile").isTextual() || json.path("profile").asText().isBlank())
                    return actionResult(response, callback, 400, "Select a development profile.");
                action = "switch-profile:" + json.path("profile").asText();
            } catch (java.io.IOException e) { return actionResult(response, callback, 400, "Invalid profile request."); }
        }
        if ("restart-app".equals(action)) {
            try (var input = org.eclipse.jetty.io.Content.Source.asInputStream(request)) {
                byte[] body = input.readNBytes(4097);
                if (body.length > 4096) return actionResult(response, callback, 413, "Application request is too large.");
                var json = new ObjectMapper().readTree(body);
                if (json == null || !json.path("componentId").isTextual()
                    || !json.path("componentId").asText().startsWith("app-") || json.path("componentId").asText().length() > 512)
                    return actionResult(response, callback, 400, "Select a backend application.");
                action = "restart-app:" + json.path("componentId").asText();
            } catch (java.io.IOException e) { return actionResult(response, callback, 400, "Invalid application request."); }
        }
        Runnable accepted;
        try { accepted = maintenance.apply(action); }
        catch (IllegalStateException e) { return actionResult(response, callback, 409, e.getMessage()); }
        // A restart may close this gateway. Flush its acceptance before starting the operation.
        return actionResult(response, new Callback() {
            @Override public void succeeded() { try { callback.succeeded(); } finally { accepted.run(); } }
            @Override public void failed(Throwable failure) { try { callback.failed(failure); } finally { accepted.run(); } }
        }, 202, null);
    }

    private boolean projectAction(String path, Request request, Response response, Callback callback) throws Exception {
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        if (!"POST".equals(request.getMethod())) {
            response.getHeaders().put("Allow", "POST");
            return actionResult(response, callback, 405, "Use POST for project actions.");
        }
        if (!localConsoleRequest(request)) return actionResult(response, callback, 403, "Project actions require the local console.");
        String operation = path.substring((ROOT + "projects/").length());
        if (java.util.Set.of("folders", "open", "create").contains(operation)) {
            try (var input = org.eclipse.jetty.io.Content.Source.asInputStream(request)) {
                byte[] body = input.readNBytes(8193);
                if (body.length > 8192) return actionResult(response, callback, 413, "Project request is too large.");
                var data = new ObjectMapper().readTree(body);
                if (data == null || !data.isObject()) return actionResult(response, callback, 400, "Provide project details.");
                if ("create".equals(operation)) {
                    var creation = projects.create(data.path("path").asText(), data.path("name").asText());
                    request.addIdleTimeoutListener(timeout -> creation.isDone());
                    creation.whenComplete((result, error) -> {
                        try {
                            if (error != null) actionResult(response, callback, 400,
                                    error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                            else projectJson(response, callback, result);
                        } catch (Exception failure) { callback.failed(failure); }
                    });
                    return true;
                }
                return projectJson(response, callback, "folders".equals(operation)
                        ? projects.folders(data.path("path").asText()) : projects.open(data.path("path").asText()));
            } catch (IllegalArgumentException | java.io.IOException e) {
                return actionResult(response, callback, 400, e instanceof IllegalArgumentException ? e.getMessage()
                        : "Unable to read this folder. Check the path and permissions.");
            }
        }
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(ROOT) + "projects/([a-f0-9]{64})/(open-folder|forget|rename|start|stop)").matcher(path);
        if (!match.matches()) return actionResult(response, callback, 404, "Unknown project action.");
        var project = environments.findKnown(match.group(1)).orElse(null);
        if (project == null) return actionResult(response, callback, 404, "Project is no longer listed.");
        if ("stop".equals(match.group(2))) {
            String currentDirectory = updates.snapshot().path("status").path("projectDirectory").asText();
            if (!currentDirectory.isBlank() && Files.isSameFile(Path.of(project.projectDirectory()), Path.of(currentDirectory)))
                return actionResult(response, callback, 409, "Use Workspace to stop this project while keeping its controls available.");
            var stopping = projects.stop(project.id());
            request.addIdleTimeoutListener(timeout -> stopping.isDone());
            stopping.whenComplete((ignored, error) -> {
                try { actionResult(response, callback, error == null ? 204 : 409,
                        error == null ? null : "Could not stop this project. Open its Workspace page to try again."); }
                catch (Exception failure) { callback.failed(failure); }
            });
            return true;
        }
        if ("start".equals(match.group(2))) {
            try {
                var startup = projectStarter.start(project.id());
                // Bootstrap has its own two-minute deadline; keep this request alive while waiting.
                request.addIdleTimeoutListener(timeout -> startup.isDone());
                startup.whenComplete((started, error) -> {
                    try {
                        if (error != null) actionResult(response, callback, 409,
                                "Unable to start the dev server. Check the project configuration and .fluxzero/dev/bootstrap.log.");
                        else {
                            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                            response.write(true, ByteBuffer.wrap(new ObjectMapper().writeValueAsBytes(started)), callback);
                        }
                    } catch (Exception failure) { callback.failed(failure); }
                });
                return true;
            } catch (IllegalArgumentException e) { return actionResult(response, callback, 404, e.getMessage()); }
            catch (IllegalStateException e) { return actionResult(response, callback, 409, e.getMessage()); }
        }
        if ("rename".equals(match.group(2))) {
            try (var input = org.eclipse.jetty.io.Content.Source.asInputStream(request)) {
                byte[] body = input.readNBytes(4097);
                if (body.length > 4096) return actionResult(response, callback, 413, "Dev server name request is too large.");
                var name = new ObjectMapper().readTree(body);
                if (name == null || !name.path("name").isTextual()) return actionResult(response, callback, 400, "Provide a dev server name.");
                var renamed = environments.rename(project.id(), name.path("name").textValue());
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.write(true, ByteBuffer.wrap(new ObjectMapper().writeValueAsBytes(renamed)), callback);
                return true;
            } catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException e) {
                return actionResult(response, callback, 400, "Use a name of at most 100 characters without control characters.");
            }
        }
        if ("forget".equals(match.group(2))) {
            try { environments.forget(project.id()); }
            catch (IllegalArgumentException e) { return actionResult(response, callback, 404, e.getMessage()); }
            catch (IllegalStateException e) { return actionResult(response, callback, 409, e.getMessage()); }
        } else {
            Path directory = Path.of(project.projectDirectory());
            if (!Files.isDirectory(directory)) return actionResult(response, callback, 404, "The project folder no longer exists.");
            try { folderOpener.open(directory); }
            catch (java.io.IOException e) { return actionResult(response, callback, 503, "Unable to open the file manager."); }
        }
        return actionResult(response, callback, 204, null);
    }

    private static boolean projectJson(Response response, Callback callback, Object value) throws Exception {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        response.write(true, ByteBuffer.wrap(new ObjectMapper().writeValueAsBytes(value)), callback);
        return true;
    }

    private static boolean localConsoleRequest(Request request) {
        return "1".equals(request.getHeaders().get("X-Fluxzero-Console")) && localConsoleOrigin(request);
    }

    static boolean localConsoleOrigin(Request request) {
        try {
            String host = request.getHeaders().get("Host");
            var uri = java.net.URI.create("http://" + host);
            return java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getRawPath().isEmpty()
                    && ("http://" + host).equals(request.getHeaders().get("Origin"))
                    && request.getConnectionMetaData().getRemoteSocketAddress() instanceof java.net.InetSocketAddress remote
                    && remote.getAddress().isLoopbackAddress();
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean actionResult(Response response, Callback callback, int status, String error) throws Exception {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        byte[] bytes = error == null ? new byte[0] : new ObjectMapper().writeValueAsBytes(Map.of("error", error));
        response.write(true, ByteBuffer.wrap(bytes), callback);
        return true;
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
