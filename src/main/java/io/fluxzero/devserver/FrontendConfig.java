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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Optional frontend process integration for the dev server.
 *
 * @param mode         frontend integration mode
 * @param command      command used to start a managed frontend
 * @param url          URL of an externally managed frontend
 * @param directory    optional working directory for managed frontend commands, relative to the project directory
 * @param setupCommand optional setup command executed once before each managed frontend launch
 * @param backendPaths environment gateway paths routed unchanged to the Fluxzero proxy
 * @param readinessPath HTTP path used to determine whether the frontend is accepting requests
 */
public record FrontendConfig(
        Mode mode,
        String command,
        String url,
        String directory,
        String setupCommand,
        List<String> backendPaths,
        String readinessPath
) {
    /** Default path routed unchanged from the public gateway to Fluxzero. */
    public static final List<String> DEFAULT_BACKEND_PATHS = List.of("/api");

    public FrontendConfig {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        directory = normalize(directory);
        setupCommand = normalize(setupCommand);
        backendPaths = normalizePaths(backendPaths == null ? DEFAULT_BACKEND_PATHS : backendPaths);
        readinessPath = normalizeReadinessPath(readinessPath);
        if (mode != Mode.COMMAND && (directory != null || setupCommand != null)) {
            throw new IllegalArgumentException("Frontend directory and setup command require a managed command");
        }
    }

    public FrontendConfig(Mode mode, String command, String url, String directory, String setupCommand,
                          List<String> backendPaths) {
        this(mode, command, url, directory, setupCommand, backendPaths, "/");
    }

    public FrontendConfig(Mode mode, String command, String url) {
        this(mode, command, url, null, null, DEFAULT_BACKEND_PATHS);
    }

    public FrontendConfig(Mode mode, String command, String url, List<String> backendPaths) {
        this(mode, command, url, null, null, backendPaths);
    }

    public static FrontendConfig none() {
        return new FrontendConfig(Mode.NONE, null, null, null, null, DEFAULT_BACKEND_PATHS);
    }

    public static FrontendConfig externalUrl(String url) {
        return new FrontendConfig(Mode.EXTERNAL_URL, null, url, null, null, DEFAULT_BACKEND_PATHS);
    }

    public static FrontendConfig command(String command) {
        return new FrontendConfig(Mode.COMMAND, command, null, null, null, DEFAULT_BACKEND_PATHS);
    }

    /**
     * Returns a copy with the public paths that should bypass the frontend and retain their path toward Fluxzero.
     */
    public FrontendConfig withBackendPaths(List<String> backendPaths) {
        return new FrontendConfig(mode, command, url, directory, setupCommand, backendPaths, readinessPath);
    }

    /** Returns a copy with the working directory and optional setup command for the managed frontend. */
    public FrontendConfig withLaunchSetup(String directory, String setupCommand) {
        return new FrontendConfig(mode, command, url, directory, setupCommand, backendPaths, readinessPath);
    }

    /** Returns a copy that probes the given HTTP path for frontend readiness. */
    public FrontendConfig withReadinessPath(String readinessPath) {
        return new FrontendConfig(mode, command, url, directory, setupCommand, backendPaths, readinessPath);
    }

    FrontendConfig resolve(DevPlaceholderResolver resolver) {
        return new FrontendConfig(mode, resolver.resolve(command), resolver.resolve(url),
                                  resolver.resolve(directory), resolver.resolve(setupCommand), backendPaths,
                                  resolver.resolve(readinessPath));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> normalizePaths(List<String> paths) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Backend path must not be blank");
            }
            String normalized = path.startsWith("/") ? path : "/" + path;
            while (normalized.length() > 1 && normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if ("/".equals(normalized)) {
                throw new IllegalArgumentException("Backend path must not capture the frontend root");
            }
            if (normalized.indexOf('?') >= 0 || normalized.indexOf('#') >= 0) {
                throw new IllegalArgumentException("Backend path must not contain a query or fragment: " + path);
            }
            if (DevGateway.BACKEND_PREFIX.equals(normalized)
                || normalized.startsWith(DevGateway.BACKEND_PREFIX + "/")) {
                throw new IllegalArgumentException(DevGateway.BACKEND_PREFIX + " is reserved by the dev gateway");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String normalizeReadinessPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String result = path.strip();
        if (!result.startsWith("/")) {
            throw new IllegalArgumentException("Frontend readiness path must start with '/': " + path);
        }
        if (result.indexOf('?') >= 0 || result.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "Frontend readiness path must not contain a query or fragment: " + path);
        }
        return result;
    }

    public enum Mode {
        NONE, EXTERNAL_URL, COMMAND
    }
}
