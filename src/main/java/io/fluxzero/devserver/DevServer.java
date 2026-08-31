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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Supervises a local Fluxzero development session.
 */
public class DevServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DevServer.class);
    private static final int MAX_DISPLAYED_TEST_SELECTORS = 4;
    private static final int MAX_TEST_SCOPE_LENGTH = 96;

    private volatile DevServerConfig config;
    private final Supplier<DevServerConfig> configReloader;
    private final IntPredicate dynamicPortConfirmation;
    private final TerminalProgress terminalProgress;
    private final DevSessionStore sessionStore;
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
    private final FrontendUpdateCoordinator frontendUpdates;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean projectActivationStarted = new AtomicBoolean();
    private volatile DevLogStore devLogStore;

    private volatile DevSession session;
    private volatile DevSessionStore.DevSessionLock sessionLock;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile DevServerLifetime lifetime;
    private volatile VersionAlignedDevRuntime devRuntime;
    private volatile DevGateway devGateway;
    private volatile int effectiveGatewayPort;
    private volatile ManagedIdpService idpService;
    private final Map<String, DevServiceProcess> serviceProcesses = new ConcurrentHashMap<>();
    private final Map<String, DevSession.ServiceStatus> serviceStatuses = new ConcurrentHashMap<>();
    private volatile DevPlaceholderResolver placeholderResolver;
    private final Map<String, FrontendProcess> frontendProcesses = new ConcurrentHashMap<>();
    private final Map<String, DevSession.ServiceStatus> frontendStatuses = new ConcurrentHashMap<>();
    private final Set<String> refreshingFrontends = ConcurrentHashMap.newKeySet();
    private volatile DevCommandPipeline commandPipeline;
    private final Map<String, ProjectRuntime> projects = new LinkedHashMap<>();
    private final Map<String, DevSession.ServiceStatus> projectCompileStatuses = new ConcurrentHashMap<>();
    private final Map<String, DevSession.ServiceStatus> projectReloadStatuses = new ConcurrentHashMap<>();
    private final Map<String, TestStatus> projectTestStatuses = new ConcurrentHashMap<>();
    private final Map<String, AppInstance> currentApps = new ConcurrentHashMap<>();
    private final Map<String, PendingReadiness> appReadiness = new ConcurrentHashMap<>();
    private final AppTerminalFilter appTerminalFilter = new AppTerminalFilter();
    private final FrontendTerminalFilter frontendTerminalFilter = new FrontendTerminalFilter();
    private volatile EmbeddedLogCapture embeddedLogCapture;
    private volatile AgentQueryService agentQueryService;
    private volatile DevMcpServer mcpServer;
    private volatile SourceWatcher greenfieldWatcher;
    private volatile String runtimeBaseUrl;
    private volatile String proxyUrl;
    private volatile String publicUrl;
    private volatile String publicFluxzeroUrl;
    private final AtomicBoolean browserReadyAnnounced = new AtomicBoolean();
    private final AtomicBoolean startupFailureAnnounced = new AtomicBoolean();
    private final CompletableFuture<String> shutdownRequested = new CompletableFuture<>();
    private final AtomicReference<String> shutdownDetail = new AtomicReference<>("dev server stopped");
    private volatile long startupStartedNanos;

    public DevServer(DevServerConfig config) {
        this(config, () -> config, DevServer::confirmDynamicPort, TerminalProgress.system());
    }

    DevServer(DevServerConfig config, IntPredicate dynamicPortConfirmation) {
        this(config, () -> config, dynamicPortConfirmation, TerminalProgress.system());
    }

    DevServer(DevServerConfig config, IntPredicate dynamicPortConfirmation, TerminalProgress terminalProgress) {
        this(config, () -> config, dynamicPortConfirmation, terminalProgress);
    }

    DevServer(DevServerConfig config, Supplier<DevServerConfig> configReloader) {
        this(config, configReloader, DevServer::confirmDynamicPort, TerminalProgress.system());
    }

    DevServer(DevServerConfig config, Supplier<DevServerConfig> configReloader,
              IntPredicate dynamicPortConfirmation, TerminalProgress terminalProgress) {
        this.config = Objects.requireNonNull(config, "config");
        this.configReloader = Objects.requireNonNull(configReloader, "configReloader");
        this.dynamicPortConfirmation = dynamicPortConfirmation;
        this.terminalProgress = terminalProgress;
        this.effectiveGatewayPort = config.gatewayPort();
        this.sessionStore = new DevSessionStore(config.projectDirectory());
        this.session = DevSession.empty(config);
        this.placeholderResolver = DevPlaceholderResolver.services(session.sessionId(), Map.of());
        this.frontendUpdates = new FrontendUpdateCoordinator(
                config.debounce().multipliedBy(2), scheduler,
                this::markFrontendUpdatesPending, this::refreshFrontends);
    }

    public synchronized DevServer start() {
        if (!started.compareAndSet(false, true)) {
            return this;
        }
        boolean greenfield = greenfieldBootstrap(config);
        if (config.backendEnabled() && (config.watch() || config.compileOnStart())) {
            DevProjectLayout.requireBuildProjectOrGreenfieldWorkspace(config);
        }
        if (!greenfield) {
            validatePublicPort();
        }
        sessionLock = sessionStore.acquireLock();
        OnePasswordEnvironment.cleanupReferenceFiles(config.projectDirectory());
        try {
            devLogStore = new DevLogStore(config.projectDirectory(), session.sessionId(), config.applicationName());
            embeddedLogCapture = EmbeddedLogCapture.start(devLogStore);
            cleanupPreviousSessionIfStale();
        } catch (RuntimeException e) {
            closeQuietly(embeddedLogCapture);
            embeddedLogCapture = null;
            closeQuietly(devLogStore);
            devLogStore = null;
            closeQuietly(sessionLock);
            sessionLock = null;
            throw e;
        }
        try {
            startupStartedNanos = System.nanoTime();
            terminalProgress.start("Starting Fluxzero dev environment");
            updateSession(current -> current.withStatus("starting"));
            startHeartbeat();
            lifetime = new DevServerLifetime(config, this::requestShutdown);
            lifetime.start(scheduler);
            startMcp();
            if (greenfield) {
                startGreenfieldBootstrap();
            } else {
                startProjectInfrastructure();
                finishProjectInfrastructureStartup();
            }
            return this;
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    private static boolean greenfieldBootstrap(DevServerConfig config) {
        return config.backendEnabled() && (config.watch() || config.compileOnStart())
               && DevProjectLayout.isGreenfieldWorkspace(config);
    }

    private void startGreenfieldBootstrap() {
        String detail = "waiting for a Maven or Gradle project to be generated in "
                        + config.projectDirectory() + "; keep this session and create the project in that exact root";
        updateSession(current -> current
                .withRuntime(waitingForProject("runtime", detail))
                .withProxy(waitingForProject("proxy", detail))
                .withIdp(waitingForProject("idp", detail))
                .withApp(waitingForProject("app", detail))
                .withReload(waitingForProject("reload", detail))
                .withCompile(waitingForProject("compile", detail))
                .withTests(waitingForProject("tests", detail))
                .withCommands(waitingForProject("commands", detail))
                .withStatus("running"));
        try {
            greenfieldWatcher = new SourceWatcher(config, scheduler, this::activateGeneratedProject);
            greenfieldWatcher.start();
        } catch (Exception e) {
            throw new DevServerStartupException("Could not watch the greenfield workspace", e);
        }
        record("[project] " + detail);
        recordBootstrapDetails();
        terminalProgress.stop();
    }

    private static DevSession.ServiceStatus waitingForProject(String name, String detail) {
        return DevSession.ServiceStatus.stopped(name).withState("waiting-for-project", detail);
    }

    private void activateGeneratedProject(Set<Path> ignoredChanges) {
        activity();
        if (closed.get() || !DevProjectLayout.isBuildProject(config.projectDirectory())
            || !projectActivationStarted.compareAndSet(false, true)) {
            return;
        }
        DevServerConfig refreshed;
        try {
            refreshed = configReloader.get();
            if (!refreshed.projectDirectory().equals(config.projectDirectory())) {
                throw new DevServerStartupException("Reloaded dev configuration changed the project root");
            }
            DevProjectLayout.requireBuildProjectOrGreenfieldWorkspace(refreshed);
            effectiveGatewayPort = refreshed.gatewayPort();
            validatePublicPort(refreshed);
        } catch (RuntimeException e) {
            String detail = summarize(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            try {
                devLogStore.observeStatus("project", "infrastructure", "project", null, "failed", detail);
                updateSession(current -> current.withCompile(
                        DevSession.ServiceStatus.failed("compile", "project activation deferred: " + detail)));
                print("[project] activation deferred: " + detail);
            } finally {
                projectActivationStarted.set(false);
            }
            return;
        }

        try {
            config = refreshed;
            restartLifetime();
            updateSession(current -> current
                    .withApp(DevSession.ServiceStatus.stopped("app"))
                    .withReload(DevSession.ServiceStatus.stopped("reload"))
                    .withCompile(DevSession.ServiceStatus.stopped("compile"))
                    .withTests(DevSession.ServiceStatus.stopped("tests"))
                    .withCommands(DevSession.ServiceStatus.stopped("commands"))
                    .withStatus("starting"));
            devLogStore.observeStatus("project", "infrastructure", "project", null, "starting",
                                      "Maven or Gradle project detected");
            record("[project] build project detected; starting the Fluxzero development environment");
            startProjectInfrastructure();
            closeQuietly(greenfieldWatcher);
            greenfieldWatcher = null;
            finishProjectInfrastructureStartup();
            devLogStore.observeStatus("project", "infrastructure", "project", null, "running",
                                      "project infrastructure started");
        } catch (RuntimeException | LinkageError e) {
            String detail = summarize(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            devLogStore.observeStatus("project", "infrastructure", "project", null, "failed", detail);
            updateSession(current -> current.withStatus("failed"));
            print("[project] infrastructure failed: " + detail);
            requestShutdown("project infrastructure failed: " + detail);
        }
    }

    private void restartLifetime() {
        closeQuietly(lifetime);
        lifetime = new DevServerLifetime(config, this::requestShutdown);
        lifetime.start(scheduler);
    }

    private void startProjectInfrastructure() {
        startServices();
        if (config.backendEnabled()) {
            startRuntimeInfrastructure();
        } else {
            skipBackend();
        }
        startFrontend();
        startGateway();
        launchFrontend();
        if (config.backendEnabled()) {
            startIdp();
            commandPipeline = new DevCommandPipeline(
                    config, sessionStore, runtimeBaseUrl, this::updateCommandStatus, this::print,
                    session.sessionId());
            updateCommandStatus(DevCommandStatus.empty(session.sessionId()));
            initializeProjects();
        }
    }

    private void finishProjectInfrastructureStartup() {
        updateSession(current -> current.withStatus("running"));
        recordEnvironmentDetails();
        List<ProjectRuntime> initialCompileProjects = config.backendEnabled() && config.compileOnStart()
                ? projects.values().stream().filter(ProjectRuntime::hasBuildProject).toList()
                : List.of();
        initialCompileProjects.forEach(ProjectRuntime::requestInitialCompile);
        if (initialCompileProjects.isEmpty() && config.frontends().isEmpty()) {
            terminalProgress.stop();
        }
        if (config.backendEnabled() && config.watch()) {
            projects.values().forEach(ProjectRuntime::startWatcher);
        }
        reportStartupOutcome();
    }

    private void recordBootstrapDetails() {
        if (mcpServer != null) {
            record("MCP:     " + mcpServer.url());
        }
        record("Session: " + sessionStore.directory().resolve(DevSessionStore.SESSION_FILE));
        record("Log:     " + devLogStore.combinedLog());
        record("Events:  " + devLogStore.eventsFile());
        record("Problems: " + devLogStore.diagnosticsFile());
    }

    private void initializeProjects() {
        for (DevBuildProject project : config.projects()) {
            DevServerConfig projectConfig = config.forProject(project);
            DevSessionStore projectStore = config.projects().size() == 1 ? sessionStore : sessionStore.scoped(project.id());
            ProjectRuntime runtime = new ProjectRuntime(project.id(), projectConfig, projectStore);
            projects.put(project.id(), runtime);
            projectCompileStatuses.put(project.id(), DevSession.ServiceStatus.stopped("compile"));
            projectReloadStatuses.put(project.id(), DevSession.ServiceStatus.stopped("reload"));
            projectTestStatuses.put(project.id(), TestStatus.idle());
        }
    }

    private void validatePublicPort() {
        validatePublicPort(config);
    }

    private void validatePublicPort(DevServerConfig candidate) {
        if (candidate.gatewayPort() == 0) {
            return;
        }
        try {
            DevGateway.requireAvailablePort(candidate.gatewayPort());
        } catch (DevServerStartupException e) {
            if (!dynamicPortConfirmation.test(candidate.gatewayPort())) {
                throw e;
            }
            effectiveGatewayPort = 0;
            System.err.println("Using a random free public port instead.");
        }
    }

    private static boolean confirmDynamicPort(int port) {
        java.io.Console console = System.console();
        if (console == null) {
            return false;
        }
        String answer = console.readLine(
                "Port %d is already in use.%nUse a random free port instead? [y/N] ", port);
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }

    public DevSession session() {
        return session;
    }

    AgentQueryService agentQueryService() {
        return agentQueryService;
    }

    private void startMcp() {
        agentQueryService = new AgentQueryService(() -> session, devLogStore);
        try {
            mcpServer = DevMcpServer.start(config.projectDirectory(), agentQueryService, devLogStore,
                                           this::mcpFailedUnexpectedly);
            updateMcpStatus(DevSession.ServiceStatus.running("mcp", mcpServer.url(), mcpServer.port(), null,
                                                             "read-only agent control plane")
                                    .withMetadata(java.util.Map.of(
                                            "transport", "streamable-http",
                                            "tokenFile", mcpServer.tokenFile().toString())));
        } catch (RuntimeException e) {
            updateMcpStatus(DevSession.ServiceStatus.failed("mcp", e.getMessage()));
            print("[mcp] failed to start: " + e.getMessage());
        }
    }

    private void mcpFailedUnexpectedly(Throwable failure) {
        String detail = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
        updateMcpStatus(DevSession.ServiceStatus.failed("mcp", detail));
        print("[mcp] failed while running: " + detail);
    }

    private void startServices() {
        if (config.services().isEmpty()) {
            updateSession(current -> current.withServices(Map.of()));
            return;
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        config.services().forEach((id, serviceConfig) -> {
            DevServiceProcess process = DevServiceProcess.prepare(
                    id, serviceConfig, config.projectDirectory(), session.sessionId(),
                    status -> updateServiceStatus(id, status),
                    output -> printServiceOutput(id, output));
            serviceProcesses.put(id, process);
            serviceStatuses.put(id, process.status());
            placeholders.putAll(process.placeholders());
        });
        placeholderResolver = DevPlaceholderResolver.services(session.sessionId(), placeholders);
        updateServicesSession();
        for (String id : config.services().keySet()) {
            DevServiceProcess process = serviceProcesses.get(id);
            terminalProgress.updateTask("service-" + id, "Service " + id, "starting");
            try {
                process.start();
            } catch (RuntimeException e) {
                announceStartupFailure("Service " + id, e.getMessage());
                throw e;
            }
        }
    }

    void requestCompile(Set<Path> changedFiles) {
        if (closed.get()) {
            return;
        }
        Map<ProjectRuntime, Set<Path>> routed = new LinkedHashMap<>();
        for (Path changed : changedFiles) {
            Path absolute = changed.isAbsolute() ? changed.toAbsolutePath().normalize()
                    : config.projectDirectory().resolve(changed).toAbsolutePath().normalize();
            ProjectRuntime project = projects.values().stream()
                    .filter(candidate -> absolute.startsWith(candidate.config.projectDirectory()))
                    .max(java.util.Comparator.comparingInt(
                            candidate -> candidate.config.projectDirectory().getNameCount()))
                    .orElse(projects.values().stream().findFirst().orElse(null));
            if (project != null) {
                routed.computeIfAbsent(project, ignored -> new LinkedHashSet<>()).add(absolute);
            }
        }
        routed.forEach(ProjectRuntime::requestCompile);
    }

    private void startRuntimeInfrastructure() {
        FluxzeroSdkVersionDetector.Selection selection = FluxzeroSdkVersionDetector.detect(config);
        if (!selection.fallbackProjects().isEmpty()) {
            record("[runtime] Fluxzero SDK version could not be determined for "
                   + String.join(", ", selection.fallbackProjects()) + "; using dev-server default "
                   + selection.version() + ". Set " + FluxzeroSdkVersionDetector.VERSION_OVERRIDE_ENV
                   + " for a custom build model.");
        }
        terminalProgress.updateTask("runtime", "Runtime", "resolving Fluxzero SDK " + selection.version());
        try {
            int requestedProxyPort = config.frontends().isEmpty() ? effectiveGatewayPort : 0;
            devRuntime = VersionAlignedDevRuntime.start(
                    config, session.sessionId(), selection, requestedProxyPort,
                    this::applicationRegistered, this::printRuntimeOutput, this::runtimeFailedUnexpectedly);
            VersionAlignedDevRuntime.Ready ready = devRuntime.awaitReady(config.startupTimeout());
            runtimeBaseUrl = "ws://localhost:" + ready.runtimePort();
            proxyUrl = "http://localhost:" + ready.proxyPort();

            Map<String, String> metadata = new LinkedHashMap<>(devRuntime.metadata());
            selection.projectVersions().forEach((project, version) ->
                    metadata.put("project." + project + ".sdkVersion", version));
            if (!selection.fallbackProjects().isEmpty()) {
                metadata.put("versionDetection", "fallback");
                metadata.put("fallbackProjects", String.join(",", selection.fallbackProjects()));
            } else {
                metadata.put("versionDetection", selection.overridden() ? "override" : "build");
            }
            String detail = "Fluxzero SDK " + ready.version() + " runtime";
            updateRuntimeStatus(DevSession.ServiceStatus.running(
                    "runtime", runtimeBaseUrl, ready.runtimePort(), devRuntime.pid(), detail)
                                        .withMetadata(metadata));
            updateProxyStatus(DevSession.ServiceStatus.running(
                    "proxy", proxyUrl, ready.proxyPort(), devRuntime.pid(), detail)
                                      .withMetadata(metadata));
            record("Runtime SDK: " + ready.version() + " (artifacts "
                   + (devRuntime.cached() ? "cached" : "resolved") + ")");
        } catch (RuntimeException e) {
            updateRuntimeStatus(DevSession.ServiceStatus.failed("runtime", oneLine(e.getMessage())));
            updateProxyStatus(DevSession.ServiceStatus.failed("proxy", oneLine(e.getMessage())));
            throw e;
        } finally {
            terminalProgress.removeTask("runtime");
        }
    }

    private void skipBackend() {
        String detail = "frontend-only profile";
        updateRuntimeStatus(DevSession.ServiceStatus.stopped("runtime").withState("skipped", detail));
        updateProxyStatus(DevSession.ServiceStatus.stopped("proxy").withState("skipped", detail));
        updateIdpStatus(DevSession.ServiceStatus.stopped("idp").withState("skipped", detail));
        updateSession(current -> current
                .withApp(DevSession.ServiceStatus.stopped("app").withState("skipped", detail))
                .withReload(DevSession.ServiceStatus.stopped("reload").withState("skipped", detail))
                .withCompile(DevSession.ServiceStatus.stopped("compile").withState("skipped", detail))
                .withTests(DevSession.ServiceStatus.stopped("tests").withState("skipped", detail))
                .withCommands(DevSession.ServiceStatus.stopped("commands").withState("skipped", detail)));
    }

    private void startIdp() {
        if (config.idpMode() == IdpMode.EXTERNAL) {
            updateIdpStatus(DevSession.ServiceStatus.stopped("idp")
                                    .withState("external", "managed IDP disabled; application configuration applies"));
            return;
        }
        try {
            idpService = ManagedIdpService.start(config, runtimeBaseUrl, publicFluxzeroUrl, this::print);
            updateIdpStatus(DevSession.ServiceStatus.running("idp", idpService.issuer(), null, null,
                                                             "managed local IDP"));
        } catch (RuntimeException e) {
            updateIdpStatus(DevSession.ServiceStatus.failed("idp", e.getMessage()));
            throw e;
        }
    }

    private void startFrontend() {
        if (config.frontends().isEmpty()) {
            updateFrontendStatus("frontend", DevSession.ServiceStatus.stopped("frontend"));
            return;
        }
        for (RoutedFrontend routed : config.frontends()) {
            try {
                FrontendProcess process = FrontendProcess.prepare(
                        config, routed.config().resolve(placeholderResolver),
                        session.sessionId() + "-frontend-" + routed.id(),
                        status -> updateFrontendStatus(routed.id(), status),
                        message -> printFrontendOutput(routed.id(), message));
                frontendProcesses.put(routed.id(), process);
                updateFrontendStatus(routed.id(), process.status());
            } catch (RuntimeException e) {
                updateFrontendStatus(routed.id(), DevSession.ServiceStatus.failed(routed.id(), e.getMessage()));
            }
        }
    }

    private void launchFrontend() {
        for (Map.Entry<String, FrontendProcess> entry : frontendProcesses.entrySet()) {
            String id = entry.getKey();
            FrontendProcess process = entry.getValue();
            terminalProgress.updateTask("frontend-" + id, "Frontend " + id, "starting dev server");
            Thread.ofVirtual().name("fluxzero-dev-frontend-launch-" + id).start(() -> {
                try {
                    process.launch(message -> printFrontendOutput(id, message));
                } catch (RuntimeException e) {
                    updateFrontendStatus(id, DevSession.ServiceStatus.failed(id, e.getMessage()));
                }
            });
        }
    }

    private void startGateway() {
        if (config.frontends().isEmpty() || frontendProcesses.isEmpty()) {
            publicUrl = proxyUrl;
            publicFluxzeroUrl = proxyUrl;
            updateGatewayStatus(DevSession.ServiceStatus.stopped("gateway")
                                        .withState("skipped", "no frontend configured"));
            return;
        }
        List<DevGateway.FrontendRoute> routes = config.frontends().stream().map(routed -> {
            FrontendProcess process = frontendProcesses.get(routed.id());
            return new DevGateway.FrontendRoute(
                    routed.id(), routed.path(), process.internalUrl(), process::ready);
        }).toList();
        devGateway = DevGateway.start(proxyUrl, routes, () -> !currentApps.isEmpty(),
                                      config.frontend().backendPaths(), effectiveGatewayPort, this::activity,
                                      config.backendEnabled());
        publicUrl = devGateway.url();
        publicFluxzeroUrl = devGateway.backendUrl();
        String detail = config.backendEnabled()
                ? "public dev URL; Fluxzero mounted at " + DevGateway.BACKEND_PREFIX
                  + " and pass-through paths " + config.frontend().backendPaths()
                : "public dev URL; all application traffic routed to the frontend";
        updateGatewayStatus(DevSession.ServiceStatus.running(
                "gateway", publicUrl, devGateway.port(), null, detail));
    }

    private void cleanupPreviousSessionIfStale() {
        sessionStore.reconcileUnexpectedStop().ifPresent(previous -> {
            boolean active = !"stopped".equals(previous.status());
            active = active && !"stopped-unexpectedly".equals(previous.status());
            if (active && ProcessUtils.isAlive(previous.pid())) {
                throw new IllegalStateException("Another Fluxzero dev environment is already active for "
                                                + config.projectDirectory() + " (pid " + previous.pid() + ")");
            }
            if ("stopped-unexpectedly".equals(previous.status())) {
                print("[session] previous dev session stopped unexpectedly: " + previous.sessionId());
            } else if (active) {
                print("[session] stale dev session detected: " + previous.sessionId());
            }
            boolean appCleaned = cleanupApplicationOrphans(previous.app(), previous.sessionId());
            boolean frontendCleaned = cleanupFrontendOrphans(previous.frontend(), previous.sessionId());
            boolean servicesCleaned = cleanupServiceOrphans(previous.services(), previous.sessionId());
            if (active || appCleaned || frontendCleaned || servicesCleaned) {
                sessionStore.writeSession(previous.withStoppedServices("stale dev session cleaned up"));
            }
        });
    }

    private boolean cleanupServiceOrphans(
            Map<String, DevSession.ServiceStatus> services, String ownershipMarker
    ) {
        boolean cleaned = false;
        for (Map.Entry<String, DevSession.ServiceStatus> entry : services.entrySet()) {
            cleaned |= cleanupOrphan(
                    "service " + entry.getKey(), entry.getValue(),
                    ownershipMarker + "-service-" + entry.getKey());
        }
        return cleaned;
    }

    private boolean cleanupApplicationOrphans(DevSession.ServiceStatus status, String ownershipMarker) {
        boolean cleaned = cleanupOrphan("app", status, ownershipMarker);
        if (status == null) {
            return cleaned;
        }
        for (Map.Entry<String, String> entry : status.metadata().entrySet()) {
            if (!entry.getKey().startsWith("application.") || !entry.getKey().endsWith(".pid")) {
                continue;
            }
            try {
                long pid = Long.parseLong(entry.getValue());
                String prefix = entry.getKey().substring(0, entry.getKey().length() - "pid".length());
                DevSession.ServiceStatus process = new DevSession.ServiceStatus(
                        entry.getKey(), "running", null, null, pid, "previous dev application",
                        processIdentityMetadata(status.metadata().get(prefix + ProcessUtils.PROCESS_STARTED_AT)));
                cleaned |= cleanupOrphan(entry.getKey(), process, ownershipMarker);
            } catch (NumberFormatException ignored) {
                // Ignore malformed stale metadata and leave unrelated processes untouched.
            }
        }
        return cleaned;
    }

    private boolean cleanupFrontendOrphans(DevSession.ServiceStatus status, String ownershipMarker) {
        boolean cleaned = cleanupOrphan("frontend", status, ownershipMarker);
        if (status == null) {
            return cleaned;
        }
        for (Map.Entry<String, String> entry : status.metadata().entrySet()) {
            if (!entry.getKey().startsWith("frontend.") || !entry.getKey().endsWith(".pid")) {
                continue;
            }
            try {
                long pid = Long.parseLong(entry.getValue());
                String prefix = entry.getKey().substring(0, entry.getKey().length() - "pid".length());
                DevSession.ServiceStatus process = new DevSession.ServiceStatus(
                        entry.getKey(), "running", null, null, pid, "previous frontend",
                        processIdentityMetadata(status.metadata().get(prefix + ProcessUtils.PROCESS_STARTED_AT)));
                cleaned |= cleanupOrphan(entry.getKey(), process, ownershipMarker + "-" + prefix.substring(0,
                        prefix.length() - 1).replace('.', '-'));
            } catch (NumberFormatException ignored) {
                // Ignore malformed stale metadata and leave unrelated processes untouched.
            }
        }
        return cleaned;
    }

    private boolean cleanupOrphan(String name, DevSession.ServiceStatus status, String ownershipMarker) {
        if (status == null || status.pid() == null) {
            return false;
        }
        Long processStartedAt = processStartedAt(status);
        boolean stopped = ProcessUtils.stopIfOwned(
                status.pid(), ownershipMarker, processStartedAt, Duration.ofSeconds(2));
        if (!stopped) {
            stopped = ProcessUtils.stopIfOwned(
                    status.pid(), config.projectDirectory().toString(), processStartedAt, Duration.ofSeconds(2));
        }
        if (stopped) {
            print("[session] stopped stale " + name + " process " + status.pid());
        } else if ("running".equals(status.state()) && ProcessUtils.isAlive(status.pid())) {
            print("[session] leaving " + name + " pid " + status.pid()
                  + " alone because it is not recognisable as owned by this project");
        }
        return stopped;
    }

    private static Map<String, String> processIdentityMetadata(String startedAt) {
        return startedAt == null ? Map.of() : Map.of(ProcessUtils.PROCESS_STARTED_AT, startedAt);
    }

    private static Long processStartedAt(DevSession.ServiceStatus status) {
        try {
            String value = status.metadata().get(ProcessUtils.PROCESS_STARTED_AT);
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ReloadTiming startCandidateApps(ProjectRuntime project, BuildSnapshot snapshot) {
        List<ApplicationBuild> discovered = snapshot.applications().isEmpty()
                ? List.of(new ApplicationBuild(project.config.applicationName(), ".", project.config.mainClass(),
                                               List.of(snapshot.classesDirectory()), snapshot.runtimeClasspath()))
                : snapshot.applications();
        List<ApplicationBuild> applications = projects.size() == 1 ? discovered
                : discovered.stream().map(application -> application.scopedTo(project.id)).toList();
        Map<String, AppInstance> candidates = new LinkedHashMap<>();
        Map<String, PendingReadiness> readiness = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        try {
            long reloadStarted = System.nanoTime();
            updateReloadStatus(project.id, DevSession.ServiceStatus.running(
                    "reload", null, null, null,
                    "starting build " + snapshot.buildNumber() + " for " + applications.size() + " app(s)"));
            for (ApplicationBuild application : applications) {
                PendingReadiness pending = new PendingReadiness(
                        project.appProcessRunner.clientId(snapshot, application), new CompletableFuture<>());
                readiness.put(application.launchId(), pending);
                appReadiness.put(pending.clientId(), pending);
                try {
                    AppInstance candidate = project.appProcessRunner.start(snapshot, application);
                    candidates.put(application.launchId(), candidate);
                    candidate.onExit().thenRun(() -> appExited(candidate));
                } catch (Exception e) {
                    failures.put(application.launchId(), e.getMessage());
                    readiness.remove(application.launchId());
                    appReadiness.remove(pending.clientId(), pending);
                }
            }
            long appStartMillis = elapsedMillis(reloadStarted);
            long readinessStarted = System.nanoTime();
            terminalProgress.update("Waiting for application readiness");
            for (Map.Entry<String, AppInstance> entry : List.copyOf(candidates.entrySet())) {
                try {
                    waitUntilReadyOrAlive(entry.getValue(), readiness.get(entry.getKey()));
                } catch (Exception e) {
                    failures.put(entry.getKey(), e.getMessage());
                    AppInstance failed = candidates.remove(entry.getKey());
                    failed.stop(project.config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(failed.applicationName(), failed.clientId(),
                                                "candidate app failed readiness");
                }
            }
            for (Map.Entry<String, PendingReadiness> entry : readiness.entrySet()) {
                if (entry.getValue().failure().get() != null && candidates.containsKey(entry.getKey())) {
                    failures.put(entry.getKey(), entry.getValue().failure().get());
                    AppInstance failed = candidates.remove(entry.getKey());
                    failed.stop(project.config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(failed.applicationName(), failed.clientId(),
                                                "candidate app reported startup failure");
                }
            }
            long readinessMillis = elapsedMillis(readinessStarted);
            if (candidates.isEmpty()) {
                throw new IllegalStateException(failureSummary(failures));
            }
            Set<String> registeredTypes = ApplicationTypeRegistry.read(applications);
            long switchStarted = System.nanoTime();
            for (AppInstance candidate : candidates.values()) {
                AppInstance previous = currentApps.put(candidate.launchId(), candidate);
                if (previous != null) {
                    previous.stop(project.config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(previous.applicationName(), previous.clientId(),
                                                "app instance replaced");
                }
            }
            Set<String> describedApplications = applications.stream()
                    .map(ApplicationBuild::launchId).collect(java.util.stream.Collectors.toSet());
            for (AppInstance removed : List.copyOf(currentApps.values())) {
                if (removed.launchId().startsWith(project.launchPrefix())
                    && !describedApplications.contains(removed.launchId())
                    && currentApps.remove(removed.launchId(), removed)) {
                    removed.stop(project.config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(removed.applicationName(), removed.clientId(),
                                                "application removed from reactor");
                }
            }
            project.compilePipeline.activate(snapshot, currentApps.values().stream()
                    .filter(app -> app.launchId().startsWith(project.launchPrefix()))
                    .map(AppInstance::buildNumber).collect(java.util.stream.Collectors.toSet()));
            commandPipeline.updateRegisteredTypes(project.id, registeredTypes);
            long switchMillis = elapsedMillis(switchStarted);
            long appTotalMillis = elapsedMillis(reloadStarted);
            long totalMillis = safeAdd(snapshot.compileTiming().millis(), appTotalMillis);
            project.failures = Map.copyOf(failures);
            project.appState = failures.isEmpty() ? "running" : "degraded";
            updateApplicationsStatus(project.id, "running build " + snapshot.buildNumber()
                                                  + " (" + candidates.size() + "/" + applications.size()
                                                  + " apps; app start " + CompileTiming.format(appStartMillis)
                                                  + ", readiness " + CompileTiming.format(readinessMillis)
                                                  + ", switch " + CompileTiming.format(switchMillis) + ")");
            updateReloadStatus(project.id, DevSession.ServiceStatus.running(
                    "reload", null, null, null, "build " + snapshot.buildNumber() + " ready")
                                       .withState(failures.isEmpty() ? "succeeded" : "degraded",
                                                  failures.isEmpty()
                                                          ? "build " + snapshot.buildNumber() + " activated"
                                                          : failureSummary(failures)));
            reportStartupOutcome();
            printProjectOutput(project.id, "[reload] build " + snapshot.buildNumber()
                  + " apps=" + candidates.size()
                  + " compile=" + snapshot.compileTiming().summary()
                  + ", appStart=" + CompileTiming.format(appStartMillis)
                  + ", readiness=" + CompileTiming.format(readinessMillis)
                  + ", switch=" + CompileTiming.format(switchMillis)
                  + ", total=" + CompileTiming.format(totalMillis)
                  + (failures.isEmpty() ? "" : ", failed=" + failures.keySet()));
            commandPipeline.requestRun();
            return new ReloadTiming(appStartMillis, readinessMillis, switchMillis, totalMillis);
        } catch (Exception e) {
            for (AppInstance candidate : candidates.values()) {
                candidate.stop(project.config.gracefulShutdownTimeout());
                devLogStore.resolveInstance(candidate.applicationName(), candidate.clientId(),
                                            "candidate app instance stopped");
            }
            project.compilePipeline.discard(snapshot);
            project.failures = Map.of(project.id, oneLine(e.getMessage()));
            project.appState = project.hasApps() ? "running" : "failed";
            updateReloadStatus(project.id, DevSession.ServiceStatus.failed("reload", e.getMessage()));
            updateApplicationsStatus(project.id, e.getMessage());
            if (!project.hasApps()) {
                announceStartupFailure(projects.size() == 1 ? "Application" : "Application " + project.id,
                                       e.getMessage());
            }
            printProjectOutput(project.id, "[reload] failed: " + oneLine(e.getMessage()));
            return null;
        } finally {
            readiness.values().forEach(pending -> appReadiness.remove(pending.clientId(), pending));
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String oneLine(String value) {
        return value == null ? "unknown application startup failure"
                : value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static long safeAdd(long first, long second) {
        return first < 0 || second < 0 ? -1 : first + second;
    }

    private void waitUntilReadyOrAlive(AppInstance candidate, PendingReadiness readiness) throws Exception {
        long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (!candidate.alive()) {
                throw new IllegalStateException("app process exited before readiness");
            }
            if (readiness.failure().get() != null) {
                throw new IllegalStateException(readiness.failure().get());
            }
            try {
                readiness.ready().get(100, TimeUnit.MILLISECONDS);
                long stableUntil = System.nanoTime() + Duration.ofMillis(300).toNanos();
                while (System.nanoTime() < stableUntil) {
                    if (!candidate.alive()) {
                        throw new IllegalStateException("app process exited after registration");
                    }
                    if (readiness.failure().get() != null) {
                        throw new IllegalStateException(readiness.failure().get());
                    }
                    Thread.sleep(25);
                }
                return;
            } catch (TimeoutException ignored) {
                // Keep polling process liveness until either readiness is observed or the startup timeout expires.
            }
        }
        if (!candidate.alive()) {
            throw new IllegalStateException("app process exited before readiness");
        }
        throw new TimeoutException("app " + candidate.clientId() + " did not register before readiness timeout");
    }

    private void applicationRegistered(String clientId) {
        activity();
        PendingReadiness pending = appReadiness.get(clientId);
        if (pending != null && matchesReadinessClient(pending.clientId(), clientId)) {
            pending.ready().complete(null);
        }
    }

    static boolean matchesReadinessClient(String expectedClientId, String connectedClientId) {
        return expectedClientId.equals(connectedClientId);
    }

    private void updateRuntimeStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withRuntime(status));
        observeStatus("runtime", "infrastructure", "runtime", null, status);
    }

    private void updateProxyStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withProxy(status));
        observeStatus("proxy", "infrastructure", "proxy", null, status);
    }

    private void updateGatewayStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withGateway(status));
        observeStatus("gateway", "infrastructure", "gateway", null, status);
    }

    private void updateIdpStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withIdp(status));
        observeStatus("idp", "infrastructure", "idp", null, status);
    }

    private void updateAppStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withApp(status));
        observeStatus("app", "application", config.applicationName(), null, status);
    }

    private void updateApplicationsStatus(String projectId, String detail) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("count", Integer.toString(currentApps.size()));
        projects.forEach((id, project) -> {
            metadata.put("project." + id + ".state", project.appState);
            project.failures.forEach((application, failure) ->
                    metadata.put("application." + application + ".failure", failure));
        });
        boolean allRunning = !projects.isEmpty() && projects.values().stream()
                .allMatch(project -> "running".equals(project.appState));
        boolean anyApps = !currentApps.isEmpty();
        boolean anyFailure = projects.values().stream()
                .anyMatch(project -> "failed".equals(project.appState) || "degraded".equals(project.appState));
        String state = allRunning ? "running" : anyApps ? "degraded" : anyFailure ? "failed" : "starting";
        currentApps.values().stream().sorted(java.util.Comparator.comparing(AppInstance::launchId))
                .forEach(app -> {
                    String prefix = "application." + app.launchId() + ".";
                    metadata.put(prefix + "pid", Long.toString(app.pid()));
                    metadata.put(prefix + "clientId", app.clientId());
                    metadata.put(prefix + "applicationName", app.applicationName());
                    app.startedAt().ifPresent(startedAt -> metadata.put(
                            prefix + ProcessUtils.PROCESS_STARTED_AT, Long.toString(startedAt)));
                    metadata.put(prefix + "environment",
                                 String.join(",", app.environmentNames().stream().sorted().toList()));
                    metadata.put(prefix + "secrets", String.join(",", app.secretNames().stream().sorted().toList()));
                    observeStatus("app", "application", app.applicationName(), app.clientId(),
                                  new DevSession.ServiceStatus("app", state, null, null, app.pid(), detail));
                });
        Long pid = currentApps.size() == 1 ? currentApps.values().iterator().next().pid() : null;
        if (currentApps.size() == 1) {
            currentApps.values().iterator().next().startedAt().ifPresent(startedAt -> metadata.put(
                    ProcessUtils.PROCESS_STARTED_AT, Long.toString(startedAt)));
        }
        String displayedDetail = projects.size() == 1 ? detail : projectId + ": " + oneLine(detail);
        updateAppStatus(new DevSession.ServiceStatus(
                "app", state, null, null, pid, displayedDetail).withMetadata(metadata));
        reportStartupOutcome();
    }

    private void updateReloadStatus(String projectId, DevSession.ServiceStatus status) {
        projectReloadStatuses.put(projectId, status);
        DevSession.ServiceStatus aggregate = aggregateProjectStatus("reload", projectReloadStatuses, "succeeded");
        updateSession(current -> current.withReload(aggregate));
        observeStatus("reload", "deployment", projectId, null, status);
    }

    private void updateCompileStatus(String projectId, DevSession.ServiceStatus status) {
        projectCompileStatuses.put(projectId, status);
        DevSession.ServiceStatus aggregate = aggregateProjectStatus("compile", projectCompileStatuses, "succeeded");
        updateSession(current -> current.withCompile(aggregate));
        observeStatus("compile", "build", projectId, null, status);
    }

    private DevSession.ServiceStatus aggregateProjectStatus(
            String name, Map<String, DevSession.ServiceStatus> statuses, String completedState
    ) {
        if (statuses.size() == 1) {
            Map.Entry<String, DevSession.ServiceStatus> entry = statuses.entrySet().iterator().next();
            DevSession.ServiceStatus status = entry.getValue();
            Map<String, String> metadata = new LinkedHashMap<>(status.metadata());
            metadata.put("project." + entry.getKey() + ".state", status.state());
            return status.withMetadata(metadata);
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        statuses.forEach((id, status) -> {
            metadata.put("project." + id + ".state", status.state());
            if (status.detail() != null) {
                metadata.put("project." + id + ".detail", status.detail());
            }
        });
        String state;
        if (statuses.values().stream().anyMatch(status -> "failed".equals(status.state()))) {
            state = "failed";
        } else if (statuses.values().stream().anyMatch(status -> "degraded".equals(status.state()))) {
            state = "degraded";
        } else if (!statuses.isEmpty() && statuses.values().stream()
                .allMatch(status -> completedState.equals(status.state()))) {
            state = completedState;
        } else if (statuses.values().stream().anyMatch(status -> "running".equals(status.state())
                                                                  || "starting".equals(status.state()))) {
            state = "running";
        } else if (statuses.values().stream().anyMatch(status -> !"stopped".equals(status.state()))) {
            state = "running";
        } else {
            state = "stopped";
        }
        String detail = statuses.entrySet().stream().filter(entry -> entry.getValue().detail() != null)
                .map(entry -> entry.getKey() + ": " + oneLine(entry.getValue().detail()))
                .collect(java.util.stream.Collectors.joining("; "));
        return new DevSession.ServiceStatus(name, state, null, null, null, detail).withMetadata(metadata);
    }

    private void updateTestStatus(String projectId, TestStatus status) {
        activity();
        projectTestStatuses.put(projectId, status);
        String aggregateState = projectTestStatuses.values().stream().anyMatch(value -> "failed".equals(value.state()))
                ? "failed" : projectTestStatuses.values().stream().anyMatch(value -> "running".equals(value.state()))
                        ? "running" : projectTestStatuses.values().stream().anyMatch(value -> "queued".equals(value.state()))
                                ? "queued" : projectTestStatuses.values().stream()
                                        .allMatch(value -> "passed".equals(value.state())) ? "passed" : "idle";
        Map<String, String> metadata = new LinkedHashMap<>();
        projectTestStatuses.forEach((id, value) -> {
            metadata.put("project." + id + ".state", value.state());
            if (value.reason() != null) {
                metadata.put("project." + id + ".reason", value.reason());
            }
        });
        String aggregateDetail = projects.size() == 1 ? status.reason() : projectId + ": " + status.reason();
        updateSession(current -> current.withTests(new DevSession.ServiceStatus(
                "tests", aggregateState, null, null, null, aggregateDetail)
                                                            .withMetadata(metadata)));
        devLogStore.observeStatus("test", "test", projectId, null, status.state(),
                                  status.detail() == null ? status.reason() : status.detail());
        if (!browserReadyAnnounced.get()) {
            return;
        }
        List<String> details = new java.util.ArrayList<>();
        if (projects.size() > 1) {
            details.add("Project: " + projectId);
        }
        details.add("Scope: " + testScope(status));
        if (status.reason() != null && !status.reason().isBlank()) {
            details.add("Reason: " + displayTestReason(status.reason()));
        }
        switch (status.state()) {
            case "running" -> terminalProgress.printActivity("Tests started", details);
            case "queued" -> terminalProgress.printActivity("Tests queued", details);
            case "passed" -> {
                details.add("Duration: " + CompileTiming.format(status.durationMillis()));
                terminalProgress.printSuccess("Tests passed", details);
            }
            case "failed" -> {
                details.add("Duration: " + CompileTiming.format(status.durationMillis()));
                details.add("Exit code: " + status.exitCode());
                if (status.failureSummary() != null && !status.failureSummary().isBlank()) {
                    details.add("Cause: " + status.failureSummary());
                }
                details.add("Details: " + terminalLogPath());
                terminalProgress.printFailure("Tests failed", details);
            }
            default -> {
            }
        }
    }

    static String testScope(TestStatus status) {
        if (status.selectors().isEmpty()) {
            return "module";
        }
        if (status.selectors().size() <= MAX_DISPLAYED_TEST_SELECTORS) {
            Map<String, Long> simpleNameCounts = status.selectors().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            DevServer::simpleTestClassName, java.util.stream.Collectors.counting()));
            String selectors = status.selectors().stream()
                    .map(selector -> displayTestSelector(selector, simpleNameCounts))
                    .collect(java.util.stream.Collectors.joining(", "));
            if (selectors.length() <= MAX_TEST_SCOPE_LENGTH) {
                return selectors;
            }
        }
        return status.selectors().size() + " selected tests";
    }

    private static String displayTestSelector(String selector, Map<String, Long> simpleNameCounts) {
        int methodSeparator = selector.indexOf('#');
        String className = methodSeparator < 0 ? selector : selector.substring(0, methodSeparator);
        String simpleName = simpleTestClassName(selector);
        String displayedClass = simpleNameCounts.getOrDefault(simpleName, 0L) > 1 ? className : simpleName;
        return methodSeparator < 0 ? displayedClass : displayedClass + selector.substring(methodSeparator);
    }

    private static String simpleTestClassName(String selector) {
        int methodSeparator = selector.indexOf('#');
        String className = methodSeparator < 0 ? selector : selector.substring(0, methodSeparator);
        int packageSeparator = className.lastIndexOf('.');
        return packageSeparator < 0 ? className : className.substring(packageSeparator + 1);
    }

    private static String displayTestReason(String reason) {
        return switch (reason) {
            case "changed app code fallback" -> "no observed test impact; using module fallback";
            case "build/resource change fallback" -> "build or resource change";
            case "initial test baseline" -> "initial test baseline";
            default -> reason;
        };
    }

    private void updateCommandStatus(DevCommandStatus status) {
        updateSession(current -> current.withCommands(new DevSession.ServiceStatus(
                "commands", status.state(), null, null, null, status.summary())));
        devLogStore.observeStatus("commands", "seed", config.applicationName(), null, status.state(),
                                  status.summary());
    }

    private void updateFrontendStatus(String id, DevSession.ServiceStatus status) {
        frontendStatuses.put(id, status);
        DevSession.ServiceStatus aggregate = aggregateFrontendStatus();
        updateSession(current -> current.withFrontend(aggregate));
        observeStatus("frontend", "infrastructure", id, null, status);
        if ("running".equals(status.state())) {
            terminalProgress.removeTask("frontend-" + id);
            if (refreshingFrontends.remove(id) && browserReadyAnnounced.get()) {
                terminalProgress.printSuccess("Frontend ready", List.of("Frontend: " + id));
            }
        } else if ("starting".equals(status.state())) {
            terminalProgress.updateTask("frontend-" + id, "Frontend " + id, status.detail());
        } else if ("degraded".equals(status.state())) {
            terminalProgress.updateTask("frontend-" + id, "Frontend " + id, status.detail());
        }
        if ("failed".equals(status.state()) || "exited".equals(status.state())) {
            terminalProgress.stop();
            if (!browserReadyAnnounced.get()) {
                announceStartupFailure("Frontend " + id, status.detail());
            }
        } else {
            reportStartupOutcome();
        }
    }

    private void markFrontendUpdatesPending(Set<String> frontendIds) {
        if (closed.get()) {
            return;
        }
        frontendIds.forEach(id -> {
            FrontendProcess process = frontendProcesses.get(id);
            if (process != null) {
                process.managedUpdateDetected();
            }
        });
        if (browserReadyAnnounced.get() && !frontendIds.isEmpty()) {
            terminalProgress.printActivity("Frontend update detected", List.of(
                    "Frontends: " + String.join(", ", frontendIds.stream().sorted().toList()),
                    "Action: waiting for the publishing pipeline to settle"));
        }
    }

    private void refreshFrontends(Set<String> frontendIds) {
        if (closed.get()) {
            return;
        }
        frontendIds.forEach(id -> {
            FrontendProcess process = frontendProcesses.get(id);
            if (process == null) {
                return;
            }
            refreshingFrontends.add(id);
            if (!process.refreshAfterManagedUpdate()) {
                refreshingFrontends.remove(id);
            }
        });
    }

    private DevSession.ServiceStatus aggregateFrontendStatus() {
        if (config.frontends().isEmpty()) {
            return DevSession.ServiceStatus.stopped("frontend");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        config.frontends().forEach(frontend -> {
            DevSession.ServiceStatus status = frontendStatuses.get(frontend.id());
            if (status != null) {
                String prefix = "frontend." + frontend.id() + ".";
                metadata.put(prefix + "state", status.state());
                if (status.detail() != null && !status.detail().isBlank()) {
                    metadata.put(prefix + "detail", status.detail());
                }
                if (status.pid() != null) {
                    metadata.put(prefix + "pid", Long.toString(status.pid()));
                }
                String startedAt = status.metadata().get(ProcessUtils.PROCESS_STARTED_AT);
                if (startedAt != null) {
                    metadata.put(prefix + ProcessUtils.PROCESS_STARTED_AT, startedAt);
                }
            }
        });
        Map.Entry<String, DevSession.ServiceStatus> failed = frontendStatuses.entrySet().stream()
                .filter(entry -> "failed".equals(entry.getValue().state())
                                 || "exited".equals(entry.getValue().state()))
                .findFirst().orElse(null);
        if (failed != null) {
            return DevSession.ServiceStatus.failed(
                    "frontend", failed.getKey() + ": " + failed.getValue().detail()).withMetadata(metadata);
        }
        Map.Entry<String, DevSession.ServiceStatus> degraded = frontendStatuses.entrySet().stream()
                .filter(entry -> "degraded".equals(entry.getValue().state()))
                .findFirst().orElse(null);
        if (degraded != null) {
            String detail = degraded.getKey() + ": " + degraded.getValue().detail();
            return DevSession.ServiceStatus.running(
                    "frontend", publicUrl, null, rootFrontendPid(), detail)
                    .withState("degraded", detail).withMetadata(metadata);
        }
        if (frontendsReady()) {
            return DevSession.ServiceStatus.running(
                    "frontend", publicUrl, null, rootFrontendPid(), config.frontends().size() + " frontends ready")
                    .withMetadata(metadata);
        }
        String detail = frontendStartupDetail(config.frontends(), frontendStatuses);
        return DevSession.ServiceStatus.running(
                "frontend", null, null, rootFrontendPid(), detail)
                .withState("starting", detail).withMetadata(metadata);
    }

    static String frontendStartupDetail(List<RoutedFrontend> frontends,
                                        Map<String, DevSession.ServiceStatus> statuses) {
        List<String> pending = frontends.stream().map(RoutedFrontend::id)
                .filter(id -> statuses.get(id) == null || !"running".equals(statuses.get(id).state()))
                .toList();
        long ready = frontends.size() - pending.size();
        if (pending.isEmpty()) {
            return "confirming frontend readiness (" + ready + "/" + frontends.size() + " ready)";
        }
        String target = pending.size() == 1
                ? "frontend " + pending.getFirst()
                : "frontends: " + String.join(", ", pending);
        return "waiting for " + target + " (" + ready + "/" + frontends.size() + " ready)";
    }

    private Long rootFrontendPid() {
        return config.frontends().stream().filter(frontend -> "/".equals(frontend.path()))
                .map(RoutedFrontend::id).map(frontendStatuses::get).filter(java.util.Objects::nonNull)
                .map(DevSession.ServiceStatus::pid).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private boolean frontendsReady() {
        return !config.frontends().isEmpty() && config.frontends().stream()
                .allMatch(frontend -> {
                    FrontendProcess process = frontendProcesses.get(frontend.id());
                    return process != null && process.ready();
                });
    }

    private void updateMcpStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withMcp(status));
        observeStatus("mcp", "infrastructure", "mcp", null, status);
    }

    private void updateServiceStatus(String id, DevSession.ServiceStatus status) {
        serviceStatuses.put(id, status);
        updateServicesSession();
        observeStatus("service", "support", id, null, status);
        switch (status.state()) {
            case "starting" -> terminalProgress.updateTask("service-" + id, "Service " + id, status.detail());
            case "running" -> terminalProgress.removeTask("service-" + id);
            case "degraded", "failed" -> {
                terminalProgress.removeTask("service-" + id);
                if (browserReadyAnnounced.get()) {
                    terminalProgress.printFailure("Service " + id + " " + status.state(), List.of(
                            "Cause: " + oneLine(status.detail()),
                            "Details: " + terminalLogPath()));
                }
            }
            default -> terminalProgress.removeTask("service-" + id);
        }
        reportStartupOutcome();
    }

    private void updateServicesSession() {
        Map<String, DevSession.ServiceStatus> ordered = new LinkedHashMap<>();
        config.services().keySet().forEach(id -> {
            DevSession.ServiceStatus status = serviceStatuses.get(id);
            if (status != null) {
                ordered.put(id, status);
            }
        });
        updateSession(current -> current.withServices(ordered));
    }

    private void observeStatus(String source, String serviceType, String serviceId, String instanceId,
                               DevSession.ServiceStatus status) {
        devLogStore.observeStatus(source, serviceType, serviceId, instanceId, status.state(), status.detail());
    }

    private synchronized void updateSession(UnaryOperator<DevSession> update) {
        if (closed.get()) {
            return;
        }
        DevSession next = update.apply(session);
        session = next;
        sessionStore.writeSession(next);
    }

    private synchronized void stopSession(String detail) {
        DevSession next = session.withStoppedServices(detail);
        session = next;
        sessionStore.writeSession(next);
    }

    private void startHeartbeat() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                updateSession(DevSession::withHeartbeat);
            } catch (RuntimeException e) {
                log.warn("Failed to write dev session heartbeat", e);
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void appExited(AppInstance app) {
        app.close();
        devLogStore.resolveInstance(app.applicationName(), app.clientId(), "app instance exited");
        if (!closed.get() && currentApps.remove(app.launchId(), app)) {
            projects.values().stream().filter(project -> app.launchId().startsWith(project.launchPrefix()))
                    .findFirst().ifPresent(project -> {
                        if (!project.hasApps()) {
                            project.appState = "exited";
                        }
                        updateApplicationsStatus(project.id, app.launchId() + " process exited");
                    });
        }
    }

    private void recordEnvironmentDetails() {
        record("Fluxzero dev environment infrastructure started");
        if (config.backendEnabled()) {
            record("Runtime: " + runtimeBaseUrl);
            record("Proxy:   " + proxyUrl + (devGateway == null ? "" : " (internal)"));
        } else {
            record("Backend: skipped (frontend-only profile)");
        }
        if (devGateway != null) {
            record("Browser: waiting for " + (config.backendEnabled() ? "application and frontend" : "frontend")
                   + " at " + publicUrl);
            if (config.backendEnabled()) {
                record("Backend: " + publicFluxzeroUrl);
                record("API:     " + config.frontend().backendPaths());
            }
        }
        if (idpService != null) {
            record("IDP:     " + idpService.issuer());
        }
        if (mcpServer != null) {
            record("MCP:     " + mcpServer.url());
        }
        serviceProcesses.forEach((id, service) -> record(
                "Service " + id + ": " + (service.url() == null ? service.ports() : service.url())));
        record("Session: " + sessionStore.directory().resolve(DevSessionStore.SESSION_FILE));
        record("Log:     " + devLogStore.combinedLog());
        record("Events:  " + devLogStore.eventsFile());
        record("Problems: " + devLogStore.diagnosticsFile());
    }

    private void reportStartupOutcome() {
        if (closed.get() || browserReadyAnnounced.get() || publicUrl == null
            || config.backendEnabled() && currentApps.isEmpty()) {
            return;
        }
        DevSession current = session;
        if (current.services().values().stream().anyMatch(
                status -> "failed".equals(status.state()) || "degraded".equals(status.state()))) {
            Map.Entry<String, DevSession.ServiceStatus> failure = current.services().entrySet().stream()
                    .filter(entry -> "failed".equals(entry.getValue().state())
                                     || "degraded".equals(entry.getValue().state()))
                    .findFirst().orElseThrow();
            announceStartupFailure("Service " + failure.getKey(), failure.getValue().detail());
            return;
        }
        if (current.services().size() != config.services().size()
            || current.services().values().stream().anyMatch(status -> !"running".equals(status.state()))) {
            return;
        }
        if (config.backendEnabled()) {
            if ("failed".equals(current.reload().state()) || "degraded".equals(current.reload().state())) {
                announceStartupFailure("Application", current.reload().detail());
                return;
            }
            if (!"succeeded".equals(current.reload().state()) || !"running".equals(current.app().state())
                || projects.values().stream().anyMatch(project -> !project.healthy())) {
                return;
            }
        }
        if (!config.frontends().isEmpty()) {
            if ("failed".equals(current.frontend().state()) || "exited".equals(current.frontend().state())) {
                announceStartupFailure("Frontend", current.frontend().detail());
                return;
            }
            if (!"running".equals(current.frontend().state())) {
                return;
            }
        }
        if (browserReadyAnnounced.compareAndSet(false, true)) {
            terminalProgress.stop();
            announceBrowserReady();
        }
    }

    private void announceBrowserReady() {
        if (closed.get()) {
            return;
        }
        String ready = "Fluxzero dev server ready in " + CompileTiming.format(elapsedMillis(startupStartedNanos));
        String target = config.frontends().isEmpty()
                ? "Backend: " + publicUrl : "Open in browser: " + publicUrl;
        record(ready);
        record(target);
        terminalProgress.printReady(ready, target);
    }

    private void announceStartupFailure(String source, String detail) {
        if (closed.get() || browserReadyAnnounced.get()
            || !startupFailureAnnounced.compareAndSet(false, true)) {
            return;
        }
        terminalProgress.stop();
        String summary = source + ": " + oneLine(detail);
        String problems = "Problems: " + devLogStore.diagnosticsFile();
        String logFile = "Log: " + devLogStore.combinedLog();
        String title = "Fluxzero dev could not start";
        record(title);
        record(summary);
        terminalProgress.printFailure(title, List.of(summary, problems, logFile, "Watching for changes."));
    }

    private void record(String message) {
        if (closed.get()) {
            return;
        }
        DevLogStore logStore = devLogStore;
        if (logStore != null) {
            logStore.accept(message);
        }
    }

    private void print(String message) {
        if (closed.get()) {
            return;
        }
        DevLogStore logStore = devLogStore;
        if (logStore != null) {
            logStore.accept(message);
        }
        if (browserReadyAnnounced.get() && terminalVisible(message)) {
            terminalProgress.println(terminalProgress.currentTime() + "  " + terminalMessage(message));
        }
    }

    private void printCompileOutput(ProjectRuntime project, String message) {
        activity();
        printProjectOutput(project.id, message);
        CompileProgress progress = project.activeCompileProgress;
        if (progress != null) {
            progress.update(message).ifPresent(value -> terminalProgress.updateTask(
                    "backend-" + project.id, "Backend " + project.id, value));
        }
    }

    private void printFrontendOutput(String id, String message) {
        record(message.replaceFirst("^\\[frontend]",
                                    "[frontend " + java.util.regex.Matcher.quoteReplacement(id) + "]"));
        String terminalLine = frontendTerminalFilter.visibleLine(message);
        if (browserReadyAnnounced.get() && terminalLine != null) {
            terminalProgress.println(terminalProgress.currentTime() + "  Frontend " + id + "  " + terminalLine);
        }
    }

    private void printServiceOutput(String id, ProcessUtils.ProcessOutput output) {
        if (closed.get()) {
            return;
        }
        DevLogStore logStore = devLogStore;
        if (logStore != null) {
            logStore.process("service", "support", id, null, output.stream(), output.line());
        }
        if (browserReadyAnnounced.get()) {
            terminalProgress.println(terminalProgress.currentTime() + "  Service " + id + "  "
                                     + summarize(output.line()));
        }
    }

    private void printAppOutput(String applicationName, String instanceId, String stream, String line) {
        if (closed.get()) {
            return;
        }
        DevLogStore logStore = devLogStore;
        DevLogStore.LogPosition logPosition = null;
        if (logStore != null) {
            logPosition = logStore.process("app", "application", applicationName, instanceId, stream, line);
        }
        PendingReadiness pending = appReadiness.get(instanceId);
        if (pending != null && AppTerminalFilter.errorHeader(line)) {
            pending.failure().compareAndSet(null, "startup error in " + applicationName + ": " + summarize(line));
        }
        String terminalLine = appTerminalFilter.visibleLine(instanceId, stream, line);
        if (browserReadyAnnounced.get() && terminalLine != null) {
            if (terminalLine.startsWith("Cause: ")) {
                terminalProgress.printFailure("Application error", List.of(
                        "Application: " + applicationDisplay(applicationName, instanceId),
                        terminalLine,
                        "Details: " + terminalLogPath(logPosition)));
            } else {
                terminalProgress.println(terminalProgress.currentTime() + "  "
                                         + applicationDisplay(applicationName, instanceId) + "  "
                                         + summarize(compactAppLine(terminalLine).replace("\\n", " | ")));
            }
        }
    }

    private void printRuntimeOutput(ProcessUtils.ProcessOutput output) {
        if (closed.get()) {
            return;
        }
        DevLogStore logStore = devLogStore;
        if (logStore != null) {
            logStore.process("runtime", "infrastructure", "runtime", null, output.stream(), output.line());
        }
    }

    private void runtimeFailedUnexpectedly(String detail) {
        String summary = "Fluxzero runtime stopped unexpectedly: " + oneLine(detail);
        updateRuntimeStatus(DevSession.ServiceStatus.failed("runtime", summary));
        updateProxyStatus(DevSession.ServiceStatus.failed("proxy", summary));
        requestShutdown(summary);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        terminalProgress.stop();
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        closeQuietly(greenfieldWatcher);
        sessionStore.invalidateCommandStatus(
                session.sessionId(), "runtime session stopped; command will run again in the next session");
        stopSession(shutdownDetail.get());
        closeQuietly(lifetime);
        projects.values().forEach(DevServer::closeQuietly);
        closeQuietly(frontendUpdates);
        scheduler.shutdownNow();
        closeQuietly(commandPipeline);
        // Stop accepting browser traffic before shutting down the processes and embedded services behind it.
        closeQuietly(devGateway);
        closeQuietly(mcpServer);
        frontendProcesses.values().forEach(DevServer::closeQuietly);
        frontendProcesses.clear();
        if (devLogStore != null) {
            currentApps.values().forEach(app -> devLogStore.resolveInstance(
                    app.applicationName(), app.clientId(), "dev server stopped"));
        }
        currentApps.values().forEach(DevServer::closeQuietly);
        currentApps.clear();
        serviceProcesses.values().forEach(DevServer::closeQuietly);
        serviceProcesses.clear();
        closeQuietly(idpService);
        closeQuietly(devRuntime);
        closeQuietly(sessionLock);
        closeQuietly(embeddedLogCapture);
        closeQuietly(devLogStore);
        closeQuietly(terminalProgress);
        embeddedLogCapture = null;
    }

    CompletableFuture<String> shutdownRequested() {
        return shutdownRequested;
    }

    private void requestShutdown(String detail) {
        if (closed.get() || !shutdownDetail.compareAndSet("dev server stopped", detail)) {
            return;
        }
        record("Stopping: " + detail);
        terminalProgress.printActivity("Fluxzero dev server stopping", List.of("Reason: " + detail));
        shutdownRequested.complete(detail);
    }

    private void activity() {
        DevServerLifetime current = lifetime;
        if (current != null) {
            current.activity();
        }
    }

    private static void awaitTermination(ExecutorService executor, Duration timeout) {
        try {
            executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Ignored failure while closing dev server resource", e);
        }
    }

    private boolean containsDevCommandChange(Set<Path> changes) {
        Path commandDirectory = config.projectDirectory().resolve(DevCommandPipeline.COMMAND_DIRECTORY)
                .toAbsolutePath().normalize();
        Path projectConfig = config.projectDirectory().resolve(DevProjectConfig.FILE).toAbsolutePath().normalize();
        return changes.stream().map(path -> path.isAbsolute() ? path : config.projectDirectory().resolve(path))
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> path.startsWith(commandDirectory) || path.equals(projectConfig)
                                  || commandPipeline != null && commandPipeline.references(path));
    }

    private static String failureSummary(Map<String, String> failures) {
        return failures.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static boolean terminalVisible(String message) {
        if (message.matches("^\\[compile(?: [^]]+)?] .*")) {
            String detail = message.substring(message.indexOf(']') + 1).stripLeading();
            return detail.startsWith("failed")
                   || detail.contains("[ERROR]");
        }
        if (message.matches("^\\[test(?: [^]]+)?] .*")) {
            return false;
        }
        if (message.matches("^\\[reload(?: [^]]+)?] .*")) {
            return message.substring(message.indexOf(']') + 1).stripLeading().startsWith("failed");
        }
        return true;
    }

    private static String terminalMessage(String message) {
        if (message.startsWith("[commands] ") && message.contains("\n")) {
            return summarize(message.lines().findFirst().orElse(message));
        }
        return message;
    }

    private static String summarize(String line) {
        return line.length() <= 240 ? line : line.substring(0, 240) + "...";
    }

    private static String displayCompileMode(String mode) {
        return switch (mode) {
            case "maven-compile" -> "Maven compile";
            case "maven-full" -> "Maven full build";
            case "javac-fast" -> "Fast javac";
            case "gradle-compile" -> "Gradle compile";
            default -> mode.replace('-', ' ');
        };
    }

    private static String applicationDisplay(String applicationName, String instanceId) {
        int buildSeparator = instanceId.lastIndexOf("-build-");
        return buildSeparator < 0 ? applicationName
                : applicationName + " (build " + instanceId.substring(buildSeparator + "-build-".length()) + ")";
    }

    private static String compactAppLine(String line) {
        return line.replaceFirst("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^]]+] ", "");
    }

    private String terminalLogPath() {
        Path logFile = devLogStore.combinedLog().toAbsolutePath().normalize();
        try {
            return config.projectDirectory().toAbsolutePath().normalize().relativize(logFile).toString();
        } catch (IllegalArgumentException ignored) {
            return logFile.toString();
        }
    }

    private String terminalLogPath(DevLogStore.LogPosition position) {
        if (position == null) {
            return terminalLogPath();
        }
        Path logFile = position.file().toAbsolutePath().normalize();
        String display;
        try {
            display = config.projectDirectory().toAbsolutePath().normalize().relativize(logFile).toString();
        } catch (IllegalArgumentException ignored) {
            display = logFile.toString();
        }
        return display + ":" + position.line();
    }

    private final class ProjectRuntime implements AutoCloseable {
        private final String id;
        private final DevServerConfig config;
        private final MavenBuildCoordinator buildCoordinator = new MavenBuildCoordinator();
        private final ExecutorService compileExecutor = Executors.newSingleThreadExecutor();
        private final Object compileLock = new Object();
        private final Set<Path> pendingCompileChanges = new LinkedHashSet<>();
        private final AtomicBoolean compileRunning = new AtomicBoolean();
        private final AtomicBoolean initialCompilePending = new AtomicBoolean();
        private final CompilePipeline compilePipeline;
        private final AppProcessRunner appProcessRunner;
        private final TestPipeline testPipeline;
        private volatile SourceWatcher sourceWatcher;
        private volatile CompileProgress activeCompileProgress;
        private volatile String appState = "starting";
        private volatile Map<String, String> failures = Map.of();

        private ProjectRuntime(String id, DevServerConfig config, DevSessionStore projectStore) {
            this.id = id;
            this.config = config;
            this.compilePipeline = new CompilePipeline(
                    config, buildCoordinator, message -> printCompileOutput(this, message));
            this.appProcessRunner = new AppProcessRunner(
                    config, runtimeBaseUrl, publicFluxzeroUrl, proxyUrl, session.sessionId(),
                    DevServer.this::printAppOutput, new OnePasswordEnvironment(config.projectDirectory()),
                    placeholderResolver);
            this.testPipeline = new TestPipeline(
                    config, projectStore, buildCoordinator, status -> updateTestStatus(id, status),
                    message -> printProjectOutput(id, message));
        }

        private String launchPrefix() {
            return projects.size() == 1 ? "" : id + "/";
        }

        private boolean hasApps() {
            return currentApps.keySet().stream().anyMatch(launchId -> launchId.startsWith(launchPrefix()));
        }

        private boolean hasBuildProject() {
            return DevProjectLayout.isBuildProject(config.projectDirectory());
        }

        private boolean healthy() {
            DevSession.ServiceStatus reload = projectReloadStatuses.get(id);
            return "running".equals(appState) && reload != null && "succeeded".equals(reload.state());
        }

        private void requestInitialCompile() {
            initialCompilePending.set(true);
            requestCompile(Set.of(initialBuildInput()));
        }

        private Path initialBuildInput() {
            return BuildTool.detect(config.projectDirectory()) == BuildTool.MAVEN
                    ? config.projectDirectory().resolve("pom.xml")
                    : Files.isRegularFile(config.projectDirectory().resolve("build.gradle.kts"))
                            ? config.projectDirectory().resolve("build.gradle.kts")
                            : config.projectDirectory().resolve("build.gradle");
        }

        private void requestCompile(Set<Path> changedFiles) {
            if (closed.get()) {
                return;
            }
            activity();
            synchronized (compileLock) {
                if (closed.get()) {
                    return;
                }
                pendingCompileChanges.addAll(changedFiles);
            }
            if (compileRunning.compareAndSet(false, true)) {
                submitCompileLoop();
            }
        }

        private void submitCompileLoop() {
            try {
                compileExecutor.submit(this::compileLoop);
            } catch (RejectedExecutionException ignored) {
                compileRunning.set(false);
            }
        }

        private void compileLoop() {
            try {
                while (!closed.get()) {
                    Set<Path> changes;
                    synchronized (compileLock) {
                        changes = Set.copyOf(pendingCompileChanges);
                        pendingCompileChanges.clear();
                    }
                    boolean initialCompile = initialCompilePending.getAndSet(false);
                    if (changes.isEmpty()) {
                        return;
                    }
                    DevSession.ServiceStatus previousCompileStatus = projectCompileStatuses.get(id);
                    if (!hasApps()) {
                        startupFailureAnnounced.set(false);
                    }
                    boolean existingEnvironment = hasApps();
                    ChangeSummary changeSummary = ChangeSummary.of(config.projectDirectory(), changes);
                    CompilePlan compilePlan = compilePipeline.plan(changes);
                    CompileProgress progress = compilePlan.appReload() ? new CompileProgress(existingEnvironment) : null;
                    activeCompileProgress = progress;
                    if (existingEnvironment && compilePlan.appReload()) {
                        List<String> details = new java.util.ArrayList<>();
                        if (projects.size() > 1) {
                            details.add("Project: " + id);
                        }
                        details.add("Changed: " + changeSummary.displayPaths());
                        details.add("Plan: " + displayCompileMode(compilePlan.mode()));
                        details.add("Reason: " + compilePlan.reason());
                        terminalProgress.printActivity("Backend change detected", details);
                    }
                    if (progress != null) {
                        terminalProgress.updateTask("backend-" + id, "Backend " + id, progress.initialMessage());
                        updateCompileStatus(id, DevSession.ServiceStatus.running(
                                "compile", null, null, null, "compiling"));
                    }
                    CompileResult result;
                    frontendUpdates.buildStarted(id);
                    try {
                        result = compilePipeline.compile(compilePlan, changes);
                        frontendUpdates.buildCompleted(id, result.success());
                    } catch (RuntimeException | Error e) {
                        frontendUpdates.buildCompleted(id, false);
                        throw e;
                    } finally {
                        activeCompileProgress = null;
                    }
                    if (closed.get() || Thread.currentThread().isInterrupted()) {
                        if (result.snapshot() != null) {
                            compilePipeline.discard(result.snapshot());
                        }
                        return;
                    }
                    if (result.success()) {
                        if (result.snapshot() == null && result.detail().startsWith("app compile skipped")) {
                            updateCompileStatus(id, previousCompileStatus);
                        } else {
                            updateCompileStatus(id, DevSession.ServiceStatus.running(
                                    "compile", null, null, null, result.detail())
                                    .withState("succeeded", result.detail()));
                        }
                        if (result.snapshot() != null) {
                            int applicationCount = result.snapshot().applications().isEmpty()
                                    ? 1 : result.snapshot().applications().size();
                            terminalProgress.updateTask("backend-" + id, "Backend " + id,
                                                        "starting " + applicationCount + " application"
                                                        + (applicationCount == 1 ? "" : "s"));
                            ReloadTiming reloadTiming = startCandidateApps(this, result.snapshot());
                            if (existingEnvironment && reloadTiming != null
                                && "succeeded".equals(projectReloadStatuses.get(id).state())) {
                                List<String> details = new java.util.ArrayList<>();
                                if (projects.size() > 1) {
                                    details.add("Project: " + id);
                                }
                                details.add("Compile: " + result.snapshot().compileTiming().summary());
                                details.add("App start: " + CompileTiming.format(reloadTiming.appStartMillis()));
                                details.add("Readiness: " + CompileTiming.format(reloadTiming.readinessMillis()));
                                details.add("Switch: " + CompileTiming.format(reloadTiming.switchMillis()));
                                details.add("Total: " + CompileTiming.format(reloadTiming.totalMillis()));
                                details.add("Applications: " + result.snapshot().applications().stream()
                                        .map(ApplicationBuild::launchId).sorted()
                                        .collect(java.util.stream.Collectors.joining(", ")));
                                terminalProgress.printSuccess("Backend ready", details);
                            }
                        }
                        if (initialCompile) {
                            testPipeline.requestInitial();
                        } else {
                            testPipeline.request(changes);
                        }
                    } else {
                        updateCompileStatus(id, DevSession.ServiceStatus.failed("compile", result.detail()));
                        if (!hasApps()) {
                            appState = "failed";
                            failures = Map.of(id, oneLine(result.detail()));
                            updateApplicationsStatus(id, result.detail());
                            announceStartupFailure(projects.size() == 1 ? "Compile" : "Compile " + id,
                                                   result.detail());
                        }
                        printProjectOutput(id, "[compile] failed: " + summarize(result.detail()));
                        if (initialCompile) {
                            testPipeline.requestInitial();
                        } else {
                            testPipeline.request(changes);
                        }
                    }
                    if (containsDevCommandChange(changes)) {
                        commandPipeline.requestRun();
                    }
                    synchronized (compileLock) {
                        if (pendingCompileChanges.isEmpty()) {
                            return;
                        }
                    }
                }
            } finally {
                compileRunning.set(false);
                synchronized (compileLock) {
                    if (!closed.get() && !pendingCompileChanges.isEmpty()
                        && compileRunning.compareAndSet(false, true)) {
                        submitCompileLoop();
                        return;
                    }
                }
                terminalProgress.removeTask("backend-" + id);
                if (projects.values().stream().noneMatch(project -> project.compileRunning.get())) {
                    if (!DevServer.this.config.frontends().isEmpty() && !frontendsReady()
                        && "starting".equals(session.frontend().state())) {
                        terminalProgress.update("Waiting for frontend");
                    } else {
                        terminalProgress.stop();
                    }
                }
            }
        }

        private void startWatcher() {
            try {
                sourceWatcher = new SourceWatcher(config, scheduler, this::observeChange, this::handleChanges);
                sourceWatcher.start();
            } catch (Exception e) {
                printProjectOutput(id, "[watch] failed: " + e.getMessage());
            }
        }

        private void observeChange(Path change) {
            if (sourceWatcher.frontendPath(change)) {
                frontendUpdates.frontendFilesChanged(sourceWatcher.frontendIds(Set.of(change)));
            }
        }

        private void handleChanges(Set<Path> changes) {
            activity();
            Set<Path> frontendChanges = changes.stream().filter(sourceWatcher::frontendPath)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!frontendChanges.isEmpty()) {
                ChangeSummary summary = ChangeSummary.of(config.projectDirectory(), frontendChanges);
                record("[frontend] change detected: " + summary.displayPaths());
                if (browserReadyAnnounced.get()) {
                    terminalProgress.printActivity("Frontend change detected", List.of(
                            "Changed: " + summary.displayPaths(),
                            "Action: delegated to frontend dev server"));
                }
            }
            Set<Path> backendChanges = changes.stream().filter(path -> !frontendChanges.contains(path))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!backendChanges.isEmpty()) {
                requestCompile(backendChanges);
            }
        }

        @Override
        public void close() {
            closeQuietly(sourceWatcher);
            compileExecutor.shutdownNow();
            closeQuietly(testPipeline);
            closeQuietly(buildCoordinator);
            awaitTermination(compileExecutor, Duration.ofMillis(750));
        }
    }

    private void printProjectOutput(String projectId, String message) {
        if (closed.get()) {
            return;
        }
        DevLogStore logStore = devLogStore;
        if (logStore != null) {
            logStore.accept(message, projectId);
        }
        String displayMessage = projects.size() == 1 ? message : message.replaceFirst(
                "^\\[([^]]+)]", "[$1 " + java.util.regex.Matcher.quoteReplacement(projectId) + "]");
        if (browserReadyAnnounced.get() && terminalVisible(displayMessage)) {
            terminalProgress.println(terminalProgress.currentTime() + "  " + terminalMessage(displayMessage));
        }
    }

    private record PendingReadiness(String clientId, CompletableFuture<Void> ready,
                                    AtomicReference<String> failure) {
        PendingReadiness(String clientId, CompletableFuture<Void> ready) {
            this(clientId, ready, new AtomicReference<>());
        }
    }

    private record ReloadTiming(long appStartMillis, long readinessMillis, long switchMillis, long totalMillis) {
    }
}
