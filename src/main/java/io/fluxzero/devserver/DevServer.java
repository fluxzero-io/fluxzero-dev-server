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

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.ConnectEvent;
import io.fluxzero.proxy.ProxyServer;
import io.fluxzero.proxy.ProxyServerConfig;
import io.fluxzero.testserver.TestServer;
import io.fluxzero.testserver.metrics.TestServerMetricsMonitor;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import java.util.function.UnaryOperator;

/**
 * Supervises a local Fluxzero development session.
 */
public class DevServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DevServer.class);
    private static final int MAX_DISPLAYED_TEST_SELECTORS = 4;
    private static final int MAX_TEST_SCOPE_LENGTH = 96;

    private final DevServerConfig config;
    private final IntPredicate dynamicPortConfirmation;
    private final TerminalProgress terminalProgress;
    private final DevSessionStore sessionStore;
    private final MavenBuildCoordinator buildCoordinator = new MavenBuildCoordinator();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
    private final ExecutorService compileExecutor = Executors.newSingleThreadExecutor();
    private final Object compileLock = new Object();
    private final Set<Path> pendingCompileChanges = new LinkedHashSet<>();
    private final AtomicBoolean compileRunning = new AtomicBoolean();
    private final AtomicBoolean initialCompilePending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile DevLogStore devLogStore;

    private volatile DevSession session;
    private volatile DevSessionStore.DevSessionLock sessionLock;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile Server runtimeServer;
    private volatile ProxyServer proxyServer;
    private volatile DevGateway devGateway;
    private volatile int effectiveGatewayPort;
    private volatile ManagedIdpService idpService;
    private volatile SourceWatcher sourceWatcher;
    private volatile FrontendProcess frontendProcess;
    private volatile TestPipeline testPipeline;
    private volatile DevCommandPipeline commandPipeline;
    private volatile CompilePipeline compilePipeline;
    private volatile CompileProgress activeCompileProgress;
    private volatile AppProcessRunner appProcessRunner;
    private final Map<String, AppInstance> currentApps = new ConcurrentHashMap<>();
    private final Map<String, PendingReadiness> appReadiness = new ConcurrentHashMap<>();
    private final AppTerminalFilter appTerminalFilter = new AppTerminalFilter();
    private volatile Registration metricsRegistration = Registration.noOp();
    private volatile EmbeddedLogCapture embeddedLogCapture;
    private volatile AgentQueryService agentQueryService;
    private volatile DevMcpServer mcpServer;
    private volatile String runtimeBaseUrl;
    private volatile String proxyUrl;
    private volatile String publicUrl;
    private volatile String publicFluxzeroUrl;
    private final AtomicBoolean frontendLaunched = new AtomicBoolean();
    private final AtomicBoolean browserReadyAnnounced = new AtomicBoolean();
    private final AtomicBoolean startupFailureAnnounced = new AtomicBoolean();
    private volatile long startupStartedNanos;

    public DevServer(DevServerConfig config) {
        this(config, DevServer::confirmDynamicPort);
    }

    DevServer(DevServerConfig config, IntPredicate dynamicPortConfirmation) {
        this(config, dynamicPortConfirmation, TerminalProgress.system());
    }

    DevServer(DevServerConfig config, IntPredicate dynamicPortConfirmation, TerminalProgress terminalProgress) {
        this.config = config;
        this.dynamicPortConfirmation = dynamicPortConfirmation;
        this.terminalProgress = terminalProgress;
        this.effectiveGatewayPort = config.gatewayPort();
        this.sessionStore = new DevSessionStore(config.projectDirectory());
        this.session = DevSession.empty(config);
    }

    public synchronized DevServer start() {
        if (runtimeServer != null) {
            return this;
        }
        validatePublicPort();
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
            startMcp();
            registerReadinessMonitor();
            startRuntime();
            startProxy();
            startFrontend();
            startGateway();
            launchFrontend();
            startIdp();
            compilePipeline = new CompilePipeline(config, buildCoordinator, this::printCompileOutput);
            appProcessRunner = new AppProcessRunner(config, runtimeBaseUrl, publicFluxzeroUrl, proxyUrl,
                                                    session.sessionId(),
                                                    this::printAppOutput);
            testPipeline = new TestPipeline(config, sessionStore, buildCoordinator, this::updateTestStatus, this::print);
            commandPipeline = new DevCommandPipeline(config, sessionStore, runtimeBaseUrl, this::updateCommandStatus,
                                                     this::print, session.sessionId());
            updateCommandStatus(DevCommandStatus.empty(session.sessionId()));
            updateSession(current -> current.withStatus("running"));
            recordEnvironmentDetails();
            if (config.compileOnStart()) {
                initialCompilePending.set(true);
                requestCompile(Set.of(initialBuildInput()));
            } else {
                terminalProgress.stop();
            }
            if (config.watch()) {
                startWatcher();
            }
            return this;
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    private Path initialBuildInput() {
        return BuildTool.detect(config.projectDirectory()) == BuildTool.MAVEN
                ? config.projectDirectory().resolve("pom.xml")
                : Files.isRegularFile(config.projectDirectory().resolve("build.gradle.kts"))
                        ? config.projectDirectory().resolve("build.gradle.kts")
                        : config.projectDirectory().resolve("build.gradle");
    }

    private void validatePublicPort() {
        if (config.gatewayPort() == 0) {
            return;
        }
        if (config.frontend().mode() == FrontendConfig.Mode.NONE) {
            throw new DevServerStartupException("--port requires a frontend command or frontend URL");
        }
        try {
            DevGateway.requireAvailablePort(config.gatewayPort());
        } catch (DevServerStartupException e) {
            if (!dynamicPortConfirmation.test(config.gatewayPort())) {
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
            mcpServer = DevMcpServer.start(config.projectDirectory(), agentQueryService, devLogStore);
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

    void requestCompile(Set<Path> changedFiles) {
        if (closed.get()) {
            return;
        }
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
                DevSession.ServiceStatus previousCompileStatus = session.compile();
                if (currentApps.isEmpty()) {
                    startupFailureAnnounced.set(false);
                }
                boolean existingEnvironment = !currentApps.isEmpty();
                ChangeSummary changeSummary = ChangeSummary.of(config.projectDirectory(), changes);
                CompilePlan compilePlan = compilePipeline.plan(changes);
                CompileProgress progress = compilePlan.appReload() ? new CompileProgress(existingEnvironment) : null;
                activeCompileProgress = progress;
                if (existingEnvironment && compilePlan.appReload()) {
                    terminalProgress.printActivity("Backend change detected", List.of(
                            "Changed: " + changeSummary.displayPaths(),
                            "Plan: " + displayCompileMode(compilePlan.mode()),
                            "Reason: " + compilePlan.reason()));
                }
                if (progress != null) {
                    terminalProgress.start(progress.initialMessage());
                    updateCompileStatus(DevSession.ServiceStatus.running("compile", null, null, null, "compiling"));
                }
                CompileResult result;
                try {
                    result = compilePipeline.compile(compilePlan, changes);
                } finally {
                    if (activeCompileProgress == progress) {
                        activeCompileProgress = null;
                    }
                }
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    if (result.snapshot() != null) {
                        compilePipeline.discard(result.snapshot());
                    }
                    return;
                }
                if (result.success()) {
                    if (result.snapshot() == null && result.detail().startsWith("app compile skipped")) {
                        updateCompileStatus(previousCompileStatus);
                    } else {
                        updateCompileStatus(DevSession.ServiceStatus.running(
                                "compile", null, null, null, result.detail()).withState("succeeded", result.detail()));
                    }
                    if (result.snapshot() != null) {
                        int applicationCount = result.snapshot().applications().isEmpty()
                                ? 1 : result.snapshot().applications().size();
                        terminalProgress.update("Starting " + applicationCount + " application"
                                                + (applicationCount == 1 ? "" : "s"));
                        ReloadTiming reloadTiming = startCandidateApps(result.snapshot());
                        if (config.frontend().mode() != FrontendConfig.Mode.NONE && frontendProcess != null
                            && !browserReadyAnnounced.get() && !startupFailureAnnounced.get()
                            && !frontendProcess.ready() && "starting".equals(session.frontend().state())) {
                            terminalProgress.update("Waiting for frontend");
                        } else {
                            terminalProgress.stop();
                        }
                        if (existingEnvironment && reloadTiming != null
                            && "succeeded".equals(session.reload().state())) {
                            terminalProgress.printSuccess("Backend ready", List.of(
                                    "Compile: " + result.snapshot().compileTiming().summary(),
                                    "App start: " + CompileTiming.format(reloadTiming.appStartMillis()),
                                    "Readiness: " + CompileTiming.format(reloadTiming.readinessMillis()),
                                    "Switch: " + CompileTiming.format(reloadTiming.switchMillis()),
                                    "Total: " + CompileTiming.format(reloadTiming.totalMillis()),
                                    "Applications: " + result.snapshot().applications().stream()
                                            .map(ApplicationBuild::launchId).sorted()
                                            .collect(java.util.stream.Collectors.joining(", "))));
                        }
                    } else {
                        terminalProgress.stop();
                    }
                    if (initialCompile) {
                        testPipeline.requestInitial();
                    } else {
                        testPipeline.request(changes);
                    }
                } else {
                    terminalProgress.stop();
                    updateCompileStatus(DevSession.ServiceStatus.failed("compile", result.detail()));
                    if (currentApps.isEmpty()) {
                        announceStartupFailure("Compile", result.detail());
                    }
                    print("[compile] failed: " + summarize(result.detail()));
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
                }
            }
        }
    }

    private void startRuntime() {
        runtimeServer = TestServer.startServer(0);
        int port = localPort(runtimeServer);
        runtimeBaseUrl = "ws://localhost:" + port;
        updateRuntimeStatus(DevSession.ServiceStatus.running("runtime", runtimeBaseUrl, port, null, "embedded"));
    }

    private void startProxy() {
        ProxyServerConfig proxyConfig = ProxyServerConfig.forRuntime(runtimeBaseUrl)
                .withNamespace(config.namespace())
                .withMetricsEnabled(false);
        proxyServer = ProxyServer.start(proxyConfig);
        proxyUrl = "http://localhost:" + proxyServer.getPort();
        updateProxyStatus(DevSession.ServiceStatus.running("proxy", proxyUrl, proxyServer.getPort(), null, "embedded"));
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
        try {
            frontendProcess = FrontendProcess.prepare(
                    config, session.sessionId(), this::updateFrontendStatus, this::print);
            updateFrontendStatus(frontendProcess.status());
        } catch (RuntimeException e) {
            updateFrontendStatus(DevSession.ServiceStatus.failed("frontend", e.getMessage()));
        }
    }

    private void launchFrontend() {
        if (frontendProcess == null || !frontendLaunched.compareAndSet(false, true)) {
            return;
        }
        try {
            if (config.frontend().mode() != FrontendConfig.Mode.NONE) {
                terminalProgress.updateTask("frontend", "Frontend", "starting dev server");
            }
            frontendProcess.launch(this::printFrontendOutput);
        } catch (RuntimeException e) {
            updateFrontendStatus(DevSession.ServiceStatus.failed("frontend", e.getMessage()));
        }
    }

    private void startGateway() {
        if (config.frontend().mode() == FrontendConfig.Mode.NONE || frontendProcess == null) {
            publicUrl = proxyUrl;
            publicFluxzeroUrl = proxyUrl;
            updateGatewayStatus(DevSession.ServiceStatus.stopped("gateway")
                                        .withState("skipped", "no frontend configured"));
            return;
        }
        devGateway = DevGateway.start(proxyUrl, frontendProcess.internalUrl(), frontendProcess::ready,
                                      () -> !currentApps.isEmpty(), config.frontend().backendPaths(),
                                      effectiveGatewayPort);
        publicUrl = devGateway.url();
        publicFluxzeroUrl = devGateway.backendUrl();
        updateGatewayStatus(DevSession.ServiceStatus.running(
                "gateway", publicUrl, devGateway.port(), null,
                "public dev URL; Fluxzero mounted at " + DevGateway.BACKEND_PREFIX
                + " and pass-through paths " + config.frontend().backendPaths()));
    }

    private void startWatcher() {
        try {
            sourceWatcher = new SourceWatcher(config, scheduler, this::handleProjectChanges);
            sourceWatcher.start();
        } catch (Exception e) {
            print("[watch] failed: " + e.getMessage());
        }
    }

    private void handleProjectChanges(Set<Path> changes) {
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
            boolean frontendCleaned = cleanupOrphan("frontend", previous.frontend(), previous.sessionId());
            if (active || appCleaned || frontendCleaned) {
                sessionStore.writeSession(previous.withStoppedServices("stale dev session cleaned up"));
            }
        });
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

    private ReloadTiming startCandidateApps(BuildSnapshot snapshot) {
        List<ApplicationBuild> applications = snapshot.applications().isEmpty()
                ? List.of(new ApplicationBuild(config.applicationName(), ".", config.mainClass(),
                                               List.of(snapshot.classesDirectory()), snapshot.runtimeClasspath()))
                : snapshot.applications();
        Map<String, AppInstance> candidates = new LinkedHashMap<>();
        Map<String, PendingReadiness> readiness = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        try {
            long reloadStarted = System.nanoTime();
            updateReloadStatus(DevSession.ServiceStatus.running(
                    "reload", null, null, null,
                    "starting build " + snapshot.buildNumber() + " for " + applications.size() + " app(s)"));
            for (ApplicationBuild application : applications) {
                PendingReadiness pending = new PendingReadiness(
                        application.applicationName(), appProcessRunner.clientId(snapshot, application),
                        new CompletableFuture<>());
                readiness.put(application.launchId(), pending);
                appReadiness.put(pending.clientId(), pending);
                try {
                    AppInstance candidate = appProcessRunner.start(snapshot, application);
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
                    failed.stop(config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(failed.applicationName(), failed.clientId(),
                                                "candidate app failed readiness");
                }
            }
            for (Map.Entry<String, PendingReadiness> entry : readiness.entrySet()) {
                if (entry.getValue().failure().get() != null && candidates.containsKey(entry.getKey())) {
                    failures.put(entry.getKey(), entry.getValue().failure().get());
                    AppInstance failed = candidates.remove(entry.getKey());
                    failed.stop(config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(failed.applicationName(), failed.clientId(),
                                                "candidate app reported startup failure");
                }
            }
            long readinessMillis = elapsedMillis(readinessStarted);
            if (candidates.isEmpty()) {
                throw new IllegalStateException(failureSummary(failures));
            }
            long switchStarted = System.nanoTime();
            for (AppInstance candidate : candidates.values()) {
                AppInstance previous = currentApps.put(candidate.launchId(), candidate);
                if (previous != null) {
                    previous.stop(config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(previous.applicationName(), previous.clientId(),
                                                "app instance replaced");
                }
            }
            Set<String> describedApplications = applications.stream()
                    .map(ApplicationBuild::launchId).collect(java.util.stream.Collectors.toSet());
            for (AppInstance removed : List.copyOf(currentApps.values())) {
                if (!describedApplications.contains(removed.launchId())
                    && currentApps.remove(removed.launchId(), removed)) {
                    removed.stop(config.gracefulShutdownTimeout());
                    devLogStore.resolveInstance(removed.applicationName(), removed.clientId(),
                                                "application removed from reactor");
                }
            }
            compilePipeline.activate(snapshot, currentApps.values().stream()
                    .map(AppInstance::buildNumber).collect(java.util.stream.Collectors.toSet()));
            long switchMillis = elapsedMillis(switchStarted);
            long appTotalMillis = elapsedMillis(reloadStarted);
            long totalMillis = safeAdd(snapshot.compileTiming().millis(), appTotalMillis);
            String state = failures.isEmpty() ? "running" : "degraded";
            updateApplicationsStatus(state, "running build " + snapshot.buildNumber()
                                                   + " (" + candidates.size() + "/" + applications.size()
                                                   + " apps; app start " + CompileTiming.format(appStartMillis)
                                                   + ", readiness " + CompileTiming.format(readinessMillis)
                                                   + ", switch " + CompileTiming.format(switchMillis) + ")",
                                     failures);
            updateReloadStatus(DevSession.ServiceStatus.running(
                    "reload", null, null, null, "build " + snapshot.buildNumber() + " ready")
                                       .withState(failures.isEmpty() ? "succeeded" : "degraded",
                                                  failures.isEmpty()
                                                          ? "build " + snapshot.buildNumber() + " activated"
                                                          : failureSummary(failures)));
            reportStartupOutcome();
            print("[reload] build " + snapshot.buildNumber()
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
                candidate.stop(config.gracefulShutdownTimeout());
                devLogStore.resolveInstance(candidate.applicationName(), candidate.clientId(),
                                            "candidate app instance stopped");
            }
            compilePipeline.discard(snapshot);
            updateReloadStatus(DevSession.ServiceStatus.failed("reload", e.getMessage()));
            if (currentApps.isEmpty()) {
                updateAppStatus(DevSession.ServiceStatus.failed("app", e.getMessage()));
                announceStartupFailure("Application", e.getMessage());
            }
            print("[reload] failed: " + oneLine(e.getMessage()));
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

    private void registerReadinessMonitor() {
        metricsRegistration = TestServerMetricsMonitor.monitor((event, metadata) -> {
            if (event instanceof ConnectEvent connectEvent) {
                PendingReadiness pending = appReadiness.get(connectEvent.getClientId());
                if (pending != null && pending.applicationName().equals(connectEvent.getClient())) {
                    pending.ready().complete(null);
                }
            }
        });
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

    private void updateApplicationsStatus(String state, String detail) {
        updateApplicationsStatus(state, detail, Map.of());
    }

    private void updateApplicationsStatus(String state, String detail, Map<String, String> failures) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("count", Integer.toString(currentApps.size()));
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
        failures.forEach((application, failure) -> metadata.put("application." + application + ".failure", failure));
        Long pid = currentApps.size() == 1 ? currentApps.values().iterator().next().pid() : null;
        if (currentApps.size() == 1) {
            currentApps.values().iterator().next().startedAt().ifPresent(startedAt -> metadata.put(
                    ProcessUtils.PROCESS_STARTED_AT, Long.toString(startedAt)));
        }
        updateAppStatus(new DevSession.ServiceStatus("app", state, null, null, pid, detail).withMetadata(metadata));
    }

    private void updateReloadStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withReload(status));
        observeStatus("reload", "deployment", config.applicationName(), null, status);
    }

    private void updateCompileStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withCompile(status));
        observeStatus("compile", "build", config.applicationName(), null, status);
    }

    private void updateTestStatus(TestStatus status) {
        updateSession(current -> current.withTests(new DevSession.ServiceStatus(
                "tests", status.state(), null, null, null, status.reason())));
        devLogStore.observeStatus("test", "test", config.applicationName(), null, status.state(),
                                  status.detail() == null ? status.reason() : status.detail());
        if (!browserReadyAnnounced.get()) {
            return;
        }
        List<String> details = new java.util.ArrayList<>();
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

    private void updateFrontendStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withFrontend(status));
        observeStatus("frontend", "infrastructure", "frontend", null, status);
        if ("running".equals(status.state())) {
            terminalProgress.removeTask("frontend");
        } else if ("starting".equals(status.state())) {
            terminalProgress.updateTask("frontend", "Frontend", status.detail());
        }
        if ("failed".equals(status.state()) || "exited".equals(status.state())) {
            terminalProgress.stop();
            if (!browserReadyAnnounced.get()) {
                announceStartupFailure("Frontend", status.detail());
            }
        } else {
            reportStartupOutcome();
        }
    }

    private void updateMcpStatus(DevSession.ServiceStatus status) {
        updateSession(current -> current.withMcp(status));
        observeStatus("mcp", "infrastructure", "mcp", null, status);
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
            updateApplicationsStatus("exited", app.launchId() + " process exited");
        }
    }

    private void recordEnvironmentDetails() {
        record("Fluxzero dev environment infrastructure started");
        record("Runtime: " + runtimeBaseUrl);
        record("Proxy:   " + proxyUrl + (devGateway == null ? "" : " (internal)"));
        if (devGateway != null) {
            record("Browser: waiting for application and frontend at " + publicUrl);
            record("Backend: " + publicFluxzeroUrl);
            record("API:     " + config.frontend().backendPaths());
        }
        if (idpService != null) {
            record("IDP:     " + idpService.issuer());
        }
        if (mcpServer != null) {
            record("MCP:     " + mcpServer.url());
        }
        record("Session: " + sessionStore.directory().resolve(DevSessionStore.SESSION_FILE));
        record("Log:     " + devLogStore.combinedLog());
        record("Events:  " + devLogStore.eventsFile());
        record("Problems: " + devLogStore.diagnosticsFile());
    }

    private void reportStartupOutcome() {
        if (closed.get() || browserReadyAnnounced.get() || currentApps.isEmpty() || publicUrl == null) {
            return;
        }
        DevSession current = session;
        if ("failed".equals(current.reload().state()) || "degraded".equals(current.reload().state())) {
            announceStartupFailure("Application", current.reload().detail());
            return;
        }
        if (!"succeeded".equals(current.reload().state()) || !"running".equals(current.app().state())) {
            return;
        }
        if (config.frontend().mode() != FrontendConfig.Mode.NONE) {
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
        String target = config.frontend().mode() == FrontendConfig.Mode.NONE
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

    private void printCompileOutput(String message) {
        print(message);
        CompileProgress progress = activeCompileProgress;
        if (progress != null) {
            progress.update(message).ifPresent(terminalProgress::update);
        }
    }

    private void printFrontendOutput(String message) {
        print(message);
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

    private static int localPort(Server server) {
        if (server.getConnectors().length == 0 || !(server.getConnectors()[0] instanceof ServerConnector connector)) {
            throw new IllegalStateException("Test server has no TCP connector");
        }
        return connector.getLocalPort();
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
        sessionStore.invalidateCommandStatus(
                session.sessionId(), "runtime session stopped; command will run again in the next session");
        stopSession("dev server stopped");
        closeQuietly(sourceWatcher);
        compileExecutor.shutdownNow();
        scheduler.shutdownNow();
        closeQuietly(testPipeline);
        closeQuietly(buildCoordinator);
        awaitTermination(compileExecutor, Duration.ofMillis(750));
        closeQuietly(commandPipeline);
        // Stop accepting browser traffic before shutting down the processes and embedded services behind it.
        closeQuietly(devGateway);
        closeQuietly(mcpServer);
        closeQuietly(frontendProcess);
        if (devLogStore != null) {
            currentApps.values().forEach(app -> devLogStore.resolveInstance(
                    app.applicationName(), app.clientId(), "dev server stopped"));
        }
        currentApps.values().forEach(DevServer::closeQuietly);
        currentApps.clear();
        closeQuietly(idpService);
        cancelQuietly(metricsRegistration);
        cancelQuietly(proxyServer);
        if (runtimeServer != null) {
            try {
                runtimeServer.stop();
            } catch (Exception e) {
                log.debug("Ignored failure while stopping embedded test server", e);
            }
        }
        closeQuietly(sessionLock);
        closeQuietly(embeddedLogCapture);
        closeQuietly(devLogStore);
        closeQuietly(terminalProgress);
        embeddedLogCapture = null;
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

    private static void cancelQuietly(Registration registration) {
        if (registration == null) {
            return;
        }
        try {
            registration.cancel();
        } catch (Exception e) {
            log.warn("Failed to cancel dev server registration", e);
        }
    }

    private boolean containsDevCommandChange(Set<Path> changes) {
        Path commandDirectory = config.projectDirectory().resolve(DevCommandPipeline.COMMAND_DIRECTORY)
                .toAbsolutePath().normalize();
        Path projectConfig = config.projectDirectory().resolve(DevProjectConfig.FILE).toAbsolutePath().normalize();
        return changes.stream().map(path -> path.isAbsolute() ? path : config.projectDirectory().resolve(path))
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> path.startsWith(commandDirectory) || path.equals(projectConfig));
    }

    private static String failureSummary(Map<String, String> failures) {
        return failures.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static boolean terminalVisible(String message) {
        if (message.startsWith("[compile] ")) {
            String detail = message.substring("[compile] ".length());
            return detail.startsWith("failed")
                   || detail.contains("[ERROR]");
        }
        if (message.startsWith("[test] ")) {
            return false;
        }
        if (message.startsWith("[frontend] ")) {
            return message.startsWith("[frontend] still waiting")
                   || message.startsWith("[frontend] unavailable")
                   || message.startsWith("[frontend] process exited")
                   || message.startsWith("[frontend] remained unavailable")
                   || message.startsWith("[frontend] failed to restart");
        }
        if (message.startsWith("[reload] ")) {
            return message.startsWith("[reload] failed");
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

    private record PendingReadiness(String applicationName, String clientId, CompletableFuture<Void> ready,
                                    AtomicReference<String> failure) {
        PendingReadiness(String applicationName, String clientId, CompletableFuture<Void> ready) {
            this(applicationName, clientId, ready, new AtomicReference<>());
        }
    }

    private record ReloadTiming(long appStartMillis, long readinessMillis, long switchMillis, long totalMillis) {
    }
}
