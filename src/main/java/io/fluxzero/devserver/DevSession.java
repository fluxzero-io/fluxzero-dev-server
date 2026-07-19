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

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Machine-readable session snapshot written under {@code .fluxzero/dev/session.json}.
 */
public record DevSession(
        String sessionId,
        long pid,
        String projectDirectory,
        Observability observability,
        String status,
        ServiceStatus runtime,
        ServiceStatus proxy,
        ServiceStatus gateway,
        ServiceStatus idp,
        ServiceStatus app,
        ServiceStatus reload,
        ServiceStatus compile,
        ServiceStatus tests,
        ServiceStatus commands,
        ServiceStatus frontend,
        ServiceStatus mcp,
        long startedAt,
        long heartbeatAt,
        long updatedAt
) {
    public DevSession {
        observability = observability == null ? Observability.forSession(Path.of(projectDirectory), sessionId)
                : observability;
        runtime = defaultStatus(runtime, "runtime");
        proxy = defaultStatus(proxy, "proxy");
        gateway = defaultStatus(gateway, "gateway");
        idp = defaultStatus(idp, "idp");
        app = defaultStatus(app, "app");
        reload = defaultStatus(reload, "reload");
        compile = defaultStatus(compile, "compile");
        tests = defaultStatus(tests, "tests");
        commands = defaultStatus(commands, "commands");
        frontend = defaultStatus(frontend, "frontend");
        mcp = defaultStatus(mcp, "mcp");
    }

    static DevSession empty(DevServerConfig config) {
        long now = Instant.now().toEpochMilli();
        String sessionId = UUID.randomUUID().toString();
        return new DevSession(sessionId, currentPid(), config.projectDirectory().toString(),
                              Observability.forSession(config.projectDirectory(), sessionId),
                              "starting",
                              ServiceStatus.stopped("runtime"),
                              ServiceStatus.stopped("proxy"),
                              ServiceStatus.stopped("gateway"),
                              ServiceStatus.stopped("idp"),
                              ServiceStatus.stopped("app"),
                              ServiceStatus.stopped("reload"),
                              ServiceStatus.stopped("compile"),
                              ServiceStatus.stopped("tests"),
                              ServiceStatus.stopped("commands"),
                              ServiceStatus.stopped("frontend"),
                              ServiceStatus.stopped("mcp"),
                              now, now, now);
    }

    DevSession withStatus(String status) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withStoppedServices(String detail) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, "stopped",
                              runtime.asStopped(detail),
                              proxy.asStopped(detail),
                              gateway.asStopped(detail),
                              idp.asStopped(detail),
                              app.asStopped(detail),
                              reload.asStopped(detail),
                              compile.asStopped(detail),
                              tests.asStopped(detail),
                              commands.asStopped(detail),
                              frontend.asStopped(detail),
                              mcp.asStopped(detail),
                              startedAt, heartbeatAt, now);
    }

    DevSession withHeartbeat() {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, now, now);
    }

    DevSession withRuntime(ServiceStatus runtime) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withProxy(ServiceStatus proxy) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withGateway(ServiceStatus gateway) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests, commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withIdp(ServiceStatus idp) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withApp(ServiceStatus app) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withReload(ServiceStatus reload) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withCompile(ServiceStatus compile) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withTests(ServiceStatus tests) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withCommands(ServiceStatus commands) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withFrontend(ServiceStatus frontend) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload,
                              compile, tests,
                              commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    DevSession withMcp(ServiceStatus mcp) {
        long now = Instant.now().toEpochMilli();
        return new DevSession(sessionId, pid, projectDirectory, observability, status, runtime, proxy, gateway, idp,
                              app, reload, compile, tests, commands, frontend, mcp, startedAt, heartbeatAt, now);
    }

    private static ServiceStatus defaultStatus(ServiceStatus status, String name) {
        return status == null ? ServiceStatus.stopped(name) : status;
    }

    private static long currentPid() {
        return ManagementFactory.getRuntimeMXBean().getPid();
    }

    public record Observability(String sessionDirectory, String combinedLog, String events, String problems,
                                String diagnostics) {
        static Observability forSession(Path projectDirectory, String sessionId) {
            Path devDirectory = projectDirectory.resolve(DevSessionStore.DEV_DIRECTORY);
            Path sessionDirectory = devDirectory.resolve(DevLogStore.LOGS_DIRECTORY).resolve(sessionId);
            return new Observability(
                    sessionDirectory.toString(),
                    sessionDirectory.resolve(DevLogStore.COMBINED_LOG_FILE).toString(),
                    sessionDirectory.resolve(DevLogStore.EVENTS_FILE).toString(),
                    sessionDirectory.resolve(DevLogStore.PROBLEMS_FILE).toString(),
                    devDirectory.resolve(DevLogStore.DIAGNOSTICS_FILE).toString());
        }
    }

    public record ServiceStatus(String name, String state, String url, Integer port, Long pid, String detail,
                                java.util.Map<String, String> metadata) {
        public ServiceStatus {
            metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        }

        public ServiceStatus(String name, String state, String url, Integer port, Long pid, String detail) {
            this(name, state, url, port, pid, detail, java.util.Map.of());
        }

        static ServiceStatus stopped(String name) {
            return new ServiceStatus(name, "stopped", null, null, null, null);
        }

        static ServiceStatus running(String name, String url, Integer port, Long pid, String detail) {
            return new ServiceStatus(name, "running", url, port, pid, detail);
        }

        static ServiceStatus failed(String name, String detail) {
            return new ServiceStatus(name, "failed", null, null, null, detail);
        }

        ServiceStatus withState(String state, String detail) {
            return new ServiceStatus(name, state, url, port, pid, detail, metadata);
        }

        ServiceStatus asStopped(String detail) {
            return new ServiceStatus(name, "stopped", url, port, pid, detail, metadata);
        }

        ServiceStatus withMetadata(java.util.Map<String, String> metadata) {
            return new ServiceStatus(name, state, url, port, pid, detail, metadata);
        }
    }
}
