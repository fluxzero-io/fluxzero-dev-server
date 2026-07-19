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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class AppProcessRunner {
    private final DevServerConfig config;
    private final String runtimeBaseUrl;
    private final String proxyUrl;
    private final String internalProxyUrl;
    private final String sessionId;
    private final AppProcessOutput output;
    private final OnePasswordEnvironment onePassword;

    AppProcessRunner(DevServerConfig config, String runtimeBaseUrl, String proxyUrl, String sessionId,
                     Consumer<String> output) {
        this(config, runtimeBaseUrl, proxyUrl, proxyUrl, sessionId,
             (applicationName, instanceId, stream, line) -> output.accept("[app] "
                                                                         + ("stderr".equals(stream)
                                                                                 ? "[stderr] " : "") + line),
             new OnePasswordEnvironment(config.projectDirectory()));
    }

    AppProcessRunner(DevServerConfig config, String runtimeBaseUrl, String proxyUrl, String sessionId,
                     AppProcessOutput output) {
        this(config, runtimeBaseUrl, proxyUrl, proxyUrl, sessionId, output,
             new OnePasswordEnvironment(config.projectDirectory()));
    }

    AppProcessRunner(DevServerConfig config, String runtimeBaseUrl, String proxyUrl, String internalProxyUrl,
                     String sessionId, AppProcessOutput output) {
        this(config, runtimeBaseUrl, proxyUrl, internalProxyUrl, sessionId, output,
             new OnePasswordEnvironment(config.projectDirectory()));
    }

    AppProcessRunner(DevServerConfig config, String runtimeBaseUrl, String proxyUrl, String internalProxyUrl,
                     String sessionId, AppProcessOutput output, OnePasswordEnvironment onePassword) {
        this.config = config;
        this.runtimeBaseUrl = runtimeBaseUrl;
        this.proxyUrl = proxyUrl;
        this.internalProxyUrl = internalProxyUrl;
        this.sessionId = sessionId;
        this.output = output;
        this.onePassword = onePassword;
    }

    AppInstance start(BuildSnapshot snapshot) throws IOException {
        ApplicationBuild application = snapshot.applications().isEmpty()
                ? new ApplicationBuild(config.applicationName(), ".", config.mainClass(),
                                       List.of(snapshot.classesDirectory()), snapshot.runtimeClasspath())
                : snapshot.applications().getFirst();
        return start(snapshot, application);
    }

    AppInstance start(BuildSnapshot snapshot, ApplicationBuild application) throws IOException {
        String mainClass = application.mainClass() == null || application.mainClass().isBlank()
                ? MainClassDetector.detect(application.classesDirectory()) : application.mainClass();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dfluxzero.dev.session=" + sessionId);
        command.addAll(environmentSystemProperties(application));
        command.add("-cp");
        command.add(classpath(application));
        if (application.testApplication()) {
            command.add(TestApplicationLauncher.class.getName());
            command.add(mainClass);
        } else {
            command.add(mainClass);
        }
        command.addAll(config.appArgs());

        String clientId = clientId(snapshot, application);
        output.accept(application.applicationName(), clientId, "lifecycle",
                      "configuration " + application.launchId() + ", module " + application.module()
                      + ", main class " + mainClass
                      + (application.environment().isEmpty() ? ""
                              : ", environment " + application.environment().keySet())
                      + (application.secretReferences().isEmpty() ? ""
                              : ", secrets " + application.secretReferences().keySet() + " via 1Password"));
        Map<String, String> environment = new HashMap<>(application.environment());
        environment.put("ENVIRONMENT", config.environment());
        environment.put("FLUXZERO_BASE_URL", runtimeBaseUrl);
        environment.put("FLUX_BASE_URL", runtimeBaseUrl);
        environment.put("FLUX_PORT", Integer.toString(port(runtimeBaseUrl)));
        environment.put("FLUXZERO_APPLICATION_NAME", application.applicationName());
        environment.put("FLUX_APPLICATION_NAME", application.applicationName());
        environment.put("FLUXZERO_PROXY_URL", proxyUrl);
        environment.put("PROXY_PORT", Integer.toString(port(internalProxyUrl)));
        environment.put("FLUXZERO_DEV_SESSION_ID", sessionId);
        environment.put("FLUXZERO_TASK_ID", clientId);
        environment.put("FLUX_TASK_ID", clientId);
        if (config.namespace() != null) {
            environment.put("FLUXZERO_NAMESPACE", config.namespace());
        }
        OnePasswordEnvironment.PreparedCommand prepared = null;
        try {
            prepared = onePassword.prepare(application.launchId(), command, application.secretReferences());
            Process process = ProcessUtils.startWithStreams(
                    prepared.command(), config.projectDirectory(), environment,
                    line -> output.accept(application.applicationName(), clientId, line.stream(), line.line()));
            return new AppInstance(application.launchId(), application.applicationName(), snapshot.buildNumber(),
                                   clientId, process, prepared.cleanupFiles(), application.environment().keySet(),
                                   application.secretReferences().keySet());
        } catch (IOException e) {
            if (prepared != null) {
                prepared.cleanupFiles().forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // The reference-only file remains in the ignored dev directory at worst.
                    }
                });
            }
            if (!application.secretReferences().isEmpty()) {
                throw new IOException("Could not start configuration " + application.launchId()
                                      + " through the 1Password CLI: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    String clientId(BuildSnapshot snapshot) {
        ApplicationBuild application = snapshot.applications().isEmpty()
                ? new ApplicationBuild(config.applicationName(), ".", config.mainClass(), List.of(), List.of())
                : snapshot.applications().getFirst();
        return clientId(snapshot, application);
    }

    String clientId(BuildSnapshot snapshot, ApplicationBuild application) {
        return sessionId + "-" + safeId(application.launchId()) + "-build-" + snapshot.buildNumber();
    }

    private List<String> environmentSystemProperties(ApplicationBuild application) {
        List<String> properties = new ArrayList<>();
        addSystemProperty(properties, "ENVIRONMENT", config.environment());
        addSystemProperty(properties, "environment", config.environment());
        addSystemProperty(properties, "FLUX_PORT", Integer.toString(port(runtimeBaseUrl)));
        addSystemProperty(properties, "PROXY_PORT", Integer.toString(port(internalProxyUrl)));
        if (config.idpMode() == IdpMode.EXTERNAL) {
            return properties;
        }
        addSystemProperty(properties, "fluxzero.auth.external-base-url", proxyUrl);
        addSystemProperty(properties, "fluxzero.auth.oidc.issuer", proxyUrl);
        addSystemProperty(properties, "fluxzero.auth.oidc.client-id", "local-auth-app");
        addSystemProperty(properties, "fluxzero.auth.oidc.redirect-uri", proxyUrl + "/app/callback");
        addSystemProperty(properties, "fluxzero.auth.oidc.resource-audience", proxyUrl + "/api");
        addSystemProperty(properties, "fluxzero.auth.oidc.scope", "openid profile email");
        addSystemProperty(properties, "fluxzero.auth.oidc.login-state-secret",
                          "local-development-login-state-secret-change-me");
        addSystemProperty(properties, "fluxzero.auth.oidc.token-endpoint-auth-method", "none");
        return properties;
    }

    private static void addSystemProperty(List<String> command, String key, String value) {
        command.add("-D" + key + "=" + value);
    }

    private String classpath(ApplicationBuild application) throws IOException {
        List<Path> entries = new ArrayList<>();
        application.classesDirectories().stream().filter(Files::isDirectory).forEach(entries::add);
        application.runtimeClasspath().stream().filter(Files::exists)
                .filter(path -> !reactorClassesDirectory(path)).forEach(entries::add);
        if (application.testApplication()) {
            try {
                entries.add(Path.of(TestApplicationLauncher.class.getProtectionDomain().getCodeSource().getLocation()
                                            .toURI()));
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Could not locate test application launcher", e);
            }
        }
        return String.join(System.getProperty("path.separator"), entries.stream().map(Path::toString).toList());
    }

    private boolean reactorClassesDirectory(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path project = config.projectDirectory().toAbsolutePath().normalize();
        if (!normalized.startsWith(project) || normalized.getNameCount() < 2) {
            return false;
        }
        int count = normalized.getNameCount();
        return "target".equals(normalized.getName(count - 2).toString())
               && ("classes".equals(normalized.getName(count - 1).toString())
                   || "test-classes".equals(normalized.getName(count - 1).toString()));
    }

    private static String safeId(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private static int port(String url) {
        int port = java.net.URI.create(url).getPort();
        if (port < 0) {
            throw new IllegalArgumentException("URL has no explicit port: " + url);
        }
        return port;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                       System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }
}
