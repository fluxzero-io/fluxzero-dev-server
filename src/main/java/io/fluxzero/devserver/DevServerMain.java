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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone launcher for the Fluxzero local dev server.
 */
public final class DevServerMain {
    static final String STOPPING_MESSAGE = "Stopping Fluxzero dev server and all started applications...";
    static final String STOPPED_MESSAGE = "Fluxzero dev server stopped.";
    private static final long MAX_SHUTDOWN_SECONDS = 3;
    private static final String LAUNCHER_OWNS_SHUTDOWN_PROPERTY = "fluxzero.dev.launcherOwnsShutdown";

    private DevServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println(versionLine());
            return;
        }
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
        Restart restart = null;
        do {
            restart = run(restart == null ? args : restart.arguments(args));
        } while (restart != null);
    }

    record Restart(int port, String profile) {
        String[] arguments(String[] original) {
            var arguments = new java.util.ArrayList<>(java.util.List.of(original));
            arguments.addAll(java.util.List.of("--port", Integer.toString(port)));
            if (profile != null) arguments.addAll(java.util.List.of("--profile=" + profile));
            return arguments.toArray(String[]::new);
        }
    }

    private static Restart run(String[] args) throws Exception {
        DevServer server;
        try {
            String[] launchArguments = args.clone();
            server = new DevServer(DevServerConfig.fromArgs(launchArguments),
                                   () -> DevServerConfig.fromArgs(launchArguments)).withRestartSupport(
                    (profile, port) -> DevServerConfig.fromArgs(new Restart(port, profile).arguments(launchArguments)));
        } catch (IllegalArgumentException | LinkageError e) {
            reportStartupFailure(e);
            return null;
        }
        DevEnvironmentRegistry registry = DevEnvironmentRegistry.global();
        AtomicBoolean registered = new AtomicBoolean();
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean shutdownStarted = new AtomicBoolean();
        AtomicBoolean shutdownReported = new AtomicBoolean();
        boolean launcherOwnsShutdown = Boolean.getBoolean(LAUNCHER_OWNS_SHUTDOWN_PROPERTY);
        Thread shutdownHook = Thread.ofPlatform().name("fluxzero-dev-server-shutdown").unstarted(() -> {
            reportStopping(shutdownStarted, launcherOwnsShutdown);
            Thread watchdog = Thread.ofPlatform().daemon(true).name("fluxzero-dev-server-shutdown-watchdog")
                    .start(() -> haltAfterShutdownDeadline(shutdownReported, launcherOwnsShutdown));
            try {
                if (registered.compareAndSet(true, false)) {
                    try {
                        registry.unregister(server.session());
                    } catch (RuntimeException ignored) {
                        // A stale registration is reconciled by the next global list operation.
                    }
                }
                server.close();
            } finally {
                watchdog.interrupt();
                reportStopped(shutdownReported, launcherOwnsShutdown);
                shutdown.countDown();
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            server.start();
            server.shutdownRequested().thenAccept(reason -> {
                if (DevServer.RESTART_REQUESTED.equals(reason) || DevServer.STOP_REQUESTED.equals(reason)) shutdown.countDown();
                else System.exit(0);
            });
            try {
                registry.register(server.session());
                registered.set(true);
            } catch (RuntimeException e) {
                System.err.println("Warning: could not register this Fluxzero dev environment: " + e.getMessage());
            }
        } catch (DevServerStartupException | IllegalArgumentException | LinkageError e) {
            removeShutdownHook(shutdownHook);
            server.close();
            reportStartupFailure(e);
            return null;
        }
        shutdown.await();
        String reason = server.shutdownRequested().getNow(null);
        if (!DevServer.RESTART_REQUESTED.equals(reason) && !DevServer.STOP_REQUESTED.equals(reason)) return null;
        Integer port = server.session().gateway().port();
        try {
            if (registered.compareAndSet(true, false)) registry.unregister(server.session());
        } finally {
            server.close();
            removeShutdownHook(shutdownHook);
        }
        return DevServer.RESTART_REQUESTED.equals(reason) ? new Restart(port, server.restartProfile()) : null;
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // Shutdown is already in progress and the hook owns cleanup from here.
        }
    }

    private static void reportStartupFailure(Throwable failure) {
        System.err.println("Fluxzero dev could not start: " + startupFailureMessage(failure));
        System.exit(2);
    }

    private static void reportStopping(AtomicBoolean shutdownStarted, boolean launcherOwnsShutdown) {
        if (!launcherOwnsShutdown && shutdownStarted.compareAndSet(false, true)) {
            System.out.println();
            System.out.println(STOPPING_MESSAGE);
            System.out.flush();
        }
    }

    private static void haltAfterShutdownDeadline(AtomicBoolean shutdownReported, boolean launcherOwnsShutdown) {
        try {
            TimeUnit.SECONDS.sleep(MAX_SHUTDOWN_SECONDS);
            reportStopped(shutdownReported, launcherOwnsShutdown);
            Runtime.getRuntime().halt(0);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reportStopped(AtomicBoolean shutdownReported, boolean launcherOwnsShutdown) {
        if (!launcherOwnsShutdown && shutdownReported.compareAndSet(false, true)) {
            System.out.println(STOPPED_MESSAGE);
            System.out.flush();
        }
    }

    static String startupFailureMessage(Throwable failure) {
        if (failure instanceof NoClassDefFoundError && failure.getMessage() != null) {
            return "required class " + failure.getMessage().replace('/', '.')
                   + " is missing from the resolved dependencies. Reinstall matching Fluxzero dev-server artifacts.";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    static String versionLine() {
        return "Fluxzero Dev Server " + DevServerVersion.current();
    }
}
