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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

/** Writes only gateway-owned errors; upstream responses never pass through this handler. */
final class DevGatewayErrorPages extends ErrorHandler {
    private static final String STYLE = """
            :root{color-scheme:light dark;font-family:Roboto,-apple-system,BlinkMacSystemFont,"Segoe UI",Arial,sans-serif;
              background:#f6f8fc;color:#172b4d}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px}
            main{width:min(100%,540px);padding:40px;border:1px solid #dce5f0;border-radius:14px;background:#fff;box-shadow:0 12px 36px #10294d12}
            .brand{font-size:.85rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#0d6efd}
            .status{margin:28px 0 8px;font-size:.85rem;font-weight:600;color:#596d89}h1{margin:0;font-size:clamp(1.6rem,5vw,2.2rem);line-height:1.2}
            p{line-height:1.55;color:#52647e}.actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:28px}
            a{display:inline-block;min-height:44px;padding:11px 17px;border-radius:7px;font-weight:600;text-decoration:none}
            .retry{background:#eaf2ff;color:#084eae}.diagnostics{color:#0b58c4}a:hover{text-decoration:underline}a:focus-visible{outline:3px solid #0d6efd;outline-offset:3px}
            @media(prefers-color-scheme:dark){:root{background:#0f1d31;color:#eef4ff}main{background:#182a43;border-color:#344761;box-shadow:none}
              .brand{color:#81b4ff}.status,p{color:#bdcce0}.retry{background:#173d70;color:#dceaff}.diagnostics{color:#9ac4ff}}
            """;
    private volatile String diagnosticsUrl;
    private final Predicate<Request> frontendRoute;

    DevGatewayErrorPages(String diagnosticsUrl, Predicate<Request> frontendRoute) {
        this.diagnosticsUrl = diagnosticsUrl;
        this.frontendRoute = frontendRoute;
    }

    void diagnosticsUrl(String value) {
        this.diagnosticsUrl = value;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        write(request, response, callback, response.getStatus(), null);
        return true;
    }

    boolean notFound(Request request, Response response, Callback callback) {
        if (representation(request) == Representation.HTML) {
            write(request, response, callback, 404, null);
        } else {
            response.setStatus(404);
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
            callback.succeeded();
        }
        return true;
    }

    void write(Request request, Response response, Callback callback, int status, String existingPlainMessage) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        if ("HEAD".equals(request.getMethod())) {
            callback.succeeded();
            return;
        }
        Representation representation = representation(request);
        if (existingPlainMessage != null && representation != Representation.HTML) representation = Representation.PLAIN;
        String title = status == 500 ? "Internal Server Error" : HttpStatus.getMessage(status);
        if (title == null) title = "Request failed";
        String body;
        if (representation == Representation.HTML) {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8");
            response.getHeaders().put("Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'self'");
            String link = diagnosticsUrl;
            body = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>" + status + " " + title + " · Fluxzero Dev Server</title><style>" + STYLE + "</style></head><body>"
                    + "<main><div class=\"brand\">Fluxzero Dev Server</div><div class=\"status\">HTTP " + status + "</div>"
                    + "<h1>" + title + "</h1><p>" + explanation(status) + "</p><nav class=\"actions\" aria-label=\"Next steps\">"
                    + "<a class=\"retry\" href=\"\">Retry</a>"
                    + (link == null ? "" : "<a class=\"diagnostics\" href=\"" + link + "\">Open Devboard diagnostics</a>")
                    + "</nav></main></body></html>";
        } else if (representation == Representation.JSON) {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8");
            body = "{\"status\":" + status + ",\"error\":\"" + title + "\"}";
        } else {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
            body = existingPlainMessage == null ? status + " " + title : existingPlainMessage;
        }
        response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
    }

    private Representation representation(Request request) {
        String path = request.getHttpURI().getPath();
        if (path.startsWith(DevConsole.ROOT + "api/") || path.startsWith(DevConsole.ROOT + "actions/")
                || path.startsWith(DevConsole.ROOT + "projects/") || path.endsWith(".json")) return Representation.JSON;
        boolean document = (frontendRoute.test(request) || path.startsWith(DevConsole.ROOT))
                && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && !(path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".map")
                     || path.endsWith(".ico") || path.endsWith(".svg") || path.endsWith(".png")
                     || path.endsWith(".webp") || path.endsWith(".woff") || path.endsWith(".woff2")
                     || path.endsWith("/health") || path.endsWith("/ready") || path.endsWith("/readiness"));
        List<String> accepted = request.getHeaders().getQualityCSV(HttpHeader.ACCEPT);
        for (String type : accepted) {
            if ("application/json".equals(type)) return Representation.JSON;
            if ("text/plain".equals(type)) return Representation.PLAIN;
            if ("text/html".equals(type)) return document ? Representation.HTML : Representation.PLAIN;
        }
        return Representation.PLAIN;
    }

    private enum Representation { HTML, JSON, PLAIN }

    private static String explanation(int status) {
        return switch (status) {
            case 404 -> "The requested page was not found in this local development environment.";
            case 502, 503, 504 -> "A local service is temporarily unavailable. Try again shortly or check Devboard diagnostics.";
            default -> "The local development server could not complete this request. Try again or check Devboard diagnostics.";
        };
    }
}
