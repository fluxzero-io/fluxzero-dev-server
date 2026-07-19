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
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
        DevServer server;
        try {
            server = new DevServer(DevServerConfig.fromArgs(args));
            server.start();
        } catch (DevServerStartupException | IllegalArgumentException | LinkageError e) {
            System.err.println("Fluxzero dev could not start: " + startupFailureMessage(e));
            System.exit(2);
            return;
        }
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean shutdownStarted = new AtomicBoolean();
        AtomicBoolean shutdownReported = new AtomicBoolean();
        boolean launcherOwnsShutdown = Boolean.getBoolean(LAUNCHER_OWNS_SHUTDOWN_PROPERTY);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("fluxzero-dev-server-shutdown").unstarted(() -> {
            reportStopping(shutdownStarted, launcherOwnsShutdown);
            Thread watchdog = Thread.ofPlatform().daemon(true).name("fluxzero-dev-server-shutdown-watchdog")
                    .start(() -> haltAfterShutdownDeadline(shutdownReported, launcherOwnsShutdown));
            try {
                server.close();
            } finally {
                watchdog.interrupt();
                reportStopped(shutdownReported, launcherOwnsShutdown);
                shutdown.countDown();
            }
        }));
        shutdown.await();
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
}
