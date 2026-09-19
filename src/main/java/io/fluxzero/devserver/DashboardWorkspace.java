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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Small, non-technical dashboard projection of current lifecycle state, not historical log errors. */
final class DashboardWorkspace {
    private static final Set<String> FAILURES = Set.of("failed", "exited", "incomplete");

    record StartupAction(String id, String name, String state, String hash) {}
    record Startup(String state, List<StartupAction> actions, String sessionId) {}

    static Startup startup(DevCommandStatus status, String sessionId) {
        if (status == null || !java.util.Objects.equals(status.sessionId(), sessionId)) return new Startup("idle", List.of(), sessionId);
        return new Startup(status.state(), status.commands().stream()
                .map(entry -> new StartupAction(entry.path(), commandName(entry), entry.state(), entry.hash())).toList(), sessionId);
    }

    private static String commandName(DevCommandStatus.Entry entry) {
        String name = entry.path().replace('\\', '/');
        name = name.startsWith("commands.") ? name.substring(9) : name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceFirst("(?i)\\.json$", "").replaceFirst("^\\d+[-_. ]+", "");
        if (name.isBlank()) {
            name = java.util.Objects.toString(entry.type(), "Startup action");
            name = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
        }
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z])([A-Z][a-z])", "$1 $2")
                .replaceAll("[-_]+", " ").replaceAll("\\s+", " ").strip();
        name = java.util.regex.Pattern.compile("\\b[A-Z][a-z]+\\b").matcher(name)
                .replaceAll(match -> match.group().toLowerCase(Locale.ROOT));
        return name.isBlank() ? "Startup action" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static Map<String, String> versions(DevSession session) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("devServer", session.devServerVersion());
        String sdk = session.runtime().metadata().get("runtimeSdkVersion");
        if (sdk != null && !sdk.isBlank()) result.put("fluxzero", sdk);
        return result;
    }

    static String issue(DevSession session) {
        if (Set.of("stopping", "stopped", "idle", "shutdown", "waiting-for-project").contains(session.status())) return "";
        // A multi-app workspace can be degraded while the remaining apps are still starting.
        boolean appFailure = failed(session.app()) || session.app().metadata().entrySet().stream()
                .anyMatch(e -> e.getKey().endsWith(".state")
                        && (FAILURES.contains(e.getValue()) || "degraded".equals(e.getValue())));
        if (appFailure || failed(session.frontend())) return "Part of your app is unavailable.";
        if (failed(session.compile()) || failed(session.reload()) || "degraded".equals(session.reload().state())) {
            boolean appRunning = "running".equals(session.app().state())
                    || Integer.parseInt(session.app().metadata().getOrDefault("count", "0")) > 0;
            return appRunning ? "Your latest changes could not be applied. A previous version is still running."
                    : "Your app could not start.";
        }
        if (failed(session.commands())) return "Your workspace could not finish getting ready.";
        if (Stream.of(session.runtime(), session.proxy(), session.gateway(), session.idp(), session.mcp()).anyMatch(DashboardWorkspace::failed)
                || session.services().values().stream().anyMatch(DashboardWorkspace::failed)
                || "failed".equals(session.status())) return "Part of your workspace is unavailable.";
        return "";
    }

    private static boolean failed(DevSession.ServiceStatus status) { return FAILURES.contains(status.state()); }
}
