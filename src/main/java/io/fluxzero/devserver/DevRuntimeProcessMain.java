/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * JDK-only bootstrap that hosts a version-selected TestServer and Proxy in an isolated class loader.
 */
public final class DevRuntimeProcessMain {
    static final String CONTROL_PREFIX = "FZDEV-RUNTIME\t";

    private DevRuntimeProcessMain() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        AtomicBoolean closed = new AtomicBoolean();
        Object[] resources = new Object[3];
        try (URLClassLoader loader = isolatedLoader(arguments.classpathFile())) {
            Thread.currentThread().setContextClassLoader(loader);
            validateIntegrationApi(loader, arguments.version());
            resources[0] = monitorConnections(loader);
            resources[1] = startRuntime(loader, arguments.lookbackMillis());
            int runtimePort = localPort(resources[1]);
            resources[2] = startProxy(loader, "ws://localhost:" + runtimePort,
                                      arguments.proxyPort(), arguments.namespace());
            int proxyPort = (int) resources[2].getClass().getMethod("getPort").invoke(resources[2]);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("fluxzero-dev-runtime-shutdown").unstarted(
                    () -> close(resources, closed)));
            monitorParent(arguments.parentPid());
            control("READY", Integer.toString(runtimePort), Integer.toString(proxyPort), arguments.version());
            awaitStop();
            close(resources, closed);
        } catch (Throwable e) {
            control("ERROR", message(e));
            e.printStackTrace(System.err);
            close(resources, closed);
            System.exit(1);
        }
    }

    private static URLClassLoader isolatedLoader(Path classpathFile) throws Exception {
        List<URL> urls = Files.readAllLines(classpathFile, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(Path::of).map(Path::toAbsolutePath).map(Path::normalize)
                .map(DevRuntimeProcessMain::url).toList();
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid runtime classpath entry " + path, e);
        }
    }

    private static Object monitorConnections(ClassLoader loader) throws Exception {
        Class<?> monitor = loader.loadClass("io.fluxzero.testserver.metrics.TestServerMetricsMonitor");
        BiConsumer<Object, Object> listener = (event, metadata) -> {
            if (event != null && "io.fluxzero.common.api.ConnectEvent".equals(event.getClass().getName())) {
                try {
                    Object clientId = event.getClass().getMethod("getClientId").invoke(event);
                    if (clientId != null) {
                        control("CONNECT", clientId.toString());
                    }
                } catch (Exception e) {
                    control("WARNING", "Could not inspect application registration: " + message(e));
                }
            }
        };
        return monitor.getMethod("monitor", BiConsumer.class).invoke(null, listener);
    }

    private static void validateIntegrationApi(ClassLoader loader, String version) {
        try {
            Class<?> monitor = loader.loadClass("io.fluxzero.testserver.metrics.TestServerMetricsMonitor");
            monitor.getMethod("monitor", BiConsumer.class);
            Class<?> testServer = loader.loadClass("io.fluxzero.testserver.TestServer");
            testServer.getMethod("startServer", int.class);
            Class<?> config = loader.loadClass("io.fluxzero.proxy.ProxyServerConfig");
            config.getMethod("forRuntime", String.class);
            config.getMethod("withMetricsEnabled", boolean.class);
            config.getMethod("withNamespace", String.class);
            config.getMethod("withPort", int.class);
            loader.loadClass("io.fluxzero.proxy.ProxyServer").getMethod("start", config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Fluxzero SDK " + version + " does not expose the local runtime integration API required by "
                    + "this dev server. Upgrade the application's Fluxzero SDK.", e);
        }
    }

    private static Object startRuntime(ClassLoader loader, long lookbackMillis) throws Exception {
        Class<?> testServer = loader.loadClass("io.fluxzero.testserver.TestServer");
        try {
            Method configurable = testServer.getMethod("startServer", int.class, Duration.class);
            return configurable.invoke(null, 0, Duration.ofMillis(lookbackMillis));
        } catch (NoSuchMethodException ignored) {
            return testServer.getMethod("startServer", int.class).invoke(null, 0);
        }
    }

    private static Object startProxy(ClassLoader loader, String runtimeUrl, int port, String namespace)
            throws Exception {
        Class<?> configType = loader.loadClass("io.fluxzero.proxy.ProxyServerConfig");
        Object config = configType.getMethod("forRuntime", String.class).invoke(null, runtimeUrl);
        config = configType.getMethod("withMetricsEnabled", boolean.class).invoke(config, false);
        if (namespace != null && !namespace.isBlank()) {
            config = configType.getMethod("withNamespace", String.class).invoke(config, namespace);
        }
        config = configType.getMethod("withPort", int.class).invoke(config, port);
        Class<?> proxyType = loader.loadClass("io.fluxzero.proxy.ProxyServer");
        return proxyType.getMethod("start", configType).invoke(null, config);
    }

    private static int localPort(Object server) throws Exception {
        Object connectors = server.getClass().getMethod("getConnectors").invoke(server);
        if (Array.getLength(connectors) == 0) {
            throw new IllegalStateException("TestServer has no TCP connector");
        }
        Object connector = Array.get(connectors, 0);
        return (int) connector.getClass().getMethod("getLocalPort").invoke(connector);
    }

    private static void awaitStop() throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                String line = input.readLine();
                if (line == null || "stop".equalsIgnoreCase(line.strip())) {
                    return;
                }
            }
        }
    }

    private static void monitorParent(long parentPid) {
        ProcessHandle parent = ProcessHandle.of(parentPid).orElseThrow(
                () -> new IllegalStateException("Dev Server supervisor process " + parentPid + " is not running"));
        parent.onExit().thenRun(() -> Runtime.getRuntime().halt(0));
    }

    private static void close(Object[] resources, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        invoke(resources[2], "cancel");
        invoke(resources[1], "stop");
        invoke(resources[0], "cancel");
    }

    private static void invoke(Object target, String method) {
        if (target == null) {
            return;
        }
        try {
            target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            System.err.println("Failed to invoke " + method + " during runtime shutdown: " + message(e));
        }
    }

    private static synchronized void control(String type, String... values) {
        StringBuilder line = new StringBuilder(CONTROL_PREFIX).append(type);
        for (String value : values) {
            line.append('\t').append(Base64.getUrlEncoder().withoutPadding()
                                             .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        System.out.println(line);
        System.out.flush();
    }

    static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record Arguments(Path classpathFile, long parentPid, int proxyPort, String namespace,
                             long lookbackMillis, String version) {
        static Arguments parse(String[] args) {
            Path classpath = null;
            long parentPid = -1;
            int proxyPort = 0;
            String namespace = null;
            long lookbackMillis = 10_000;
            String version = "unknown";
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--classpath-file" -> classpath = Path.of(requireValue(args, ++i, "--classpath-file"));
                    case "--parent-pid" -> parentPid = Long.parseLong(requireValue(args, ++i, "--parent-pid"));
                    case "--proxy-port" -> proxyPort = Integer.parseInt(requireValue(args, ++i, "--proxy-port"));
                    case "--namespace" -> namespace = requireValue(args, ++i, "--namespace");
                    case "--lookback-millis" -> lookbackMillis = Long.parseLong(
                            requireValue(args, ++i, "--lookback-millis"));
                    case "--version" -> version = requireValue(args, ++i, "--version");
                    default -> throw new IllegalArgumentException("Unknown runtime option " + args[i]);
                }
            }
            if (classpath == null) {
                throw new IllegalArgumentException("Missing --classpath-file");
            }
            if (parentPid <= 0) {
                throw new IllegalArgumentException("Missing --parent-pid");
            }
            return new Arguments(classpath.toAbsolutePath().normalize(), parentPid, proxyPort, namespace,
                                 lookbackMillis, version);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
