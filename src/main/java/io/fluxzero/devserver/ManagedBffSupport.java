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

import io.fluxzero.sdk.Fluxzero;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Local-only BFF defaults for applications that carry the public client-bff module. */
final class ManagedBffSupport {
    private static final String BFF_CLASS = "io/fluxzero/idp/bff/OidcIntegration.class";

    static Map<String, String> properties(ApplicationBuild application, boolean bff,
                                          Map<String, String> defaults, Map<String, String> environment,
                                          Map<String, String> inherited) {
        Map<String, String> result = new LinkedHashMap<>(defaults);
        if (!bff) return result;
        result.put("fluxzero.auth.oidc.client-id",
                   AppProcessRunner.applicationId(application.launchId(), environment, inherited));
        // The library owns the app-scoped callback convention. Do not force the old /app/callback route.
        result.remove("fluxzero.auth.oidc.redirect-uri");
        // The child resolves the session key using its own SDK and application configuration.
        return result;
    }

    static String configured(String key, Map<String, String> environment, Map<String, String> inherited) {
        String name = key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
        return environment.containsKey(name) ? environment.get(name) : inherited.get(name);
    }

    static boolean containsBff(ApplicationBuild application) throws IOException {
        for (Path entry : Stream.concat(application.classesDirectories().stream(),
                                        application.runtimeClasspath().stream()).toList()) {
            if (Files.isDirectory(entry)) {
                if (Files.isRegularFile(entry.resolve(BFF_CLASS))) return true;
            } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (var jar = new JarFile(entry.toFile())) {
                    if (jar.getEntry(BFF_CLASS) != null) return true;
                }
            }
        }
        return false;
    }

    static Path sessionKeyPath(Path project, String applicationId) {
        return project.resolve(DevSessionStore.DEV_DIRECTORY).resolve("oidc")
                .resolve(Fluxzero.idForName(applicationId)).resolve("session.jwks").toAbsolutePath();
    }

    /** Include only the bootstrap, never the dev server's shaded dependencies or configuration. */
    static Path launcherJar(Path project) throws IOException {
        List<String> names = List.of("io/fluxzero/devserver/ManagedBffLauncher.class",
                                     "io/fluxzero/devserver/TestApplicationLauncher.class");
        List<byte[]> classes = new ArrayList<>();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        for (String name : names) {
            try (var input = ManagedBffSupport.class.getResourceAsStream("/" + name)) {
                if (input == null) throw new IOException("Missing managed BFF launcher");
                byte[] bytes = input.readAllBytes();
                classes.add(bytes);
                digest.update(bytes);
            }
        }
        Path directory = project.resolve(DevSessionStore.DEV_DIRECTORY).resolve("launchers");
        Path jar = directory.resolve("bff-" + HexFormat.of().formatHex(digest.digest()) + ".jar");
        if (Files.isRegularFile(jar)) return jar.toAbsolutePath();
        Files.createDirectories(directory);
        Path pending = Files.createTempFile(directory, "bff-", ".tmp");
        try {
            try (var output = new JarOutputStream(Files.newOutputStream(pending))) {
                for (int i = 0; i < names.size(); i++) {
                    output.putNextEntry(new JarEntry(names.get(i)));
                    output.write(classes.get(i));
                    output.closeEntry();
                }
            }
            try {
                Files.move(pending, jar, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Another launcher may have installed this exact content while we wrote the temporary file.
                // Windows also refuses replacing a JAR already opened by that child.
                if (!Files.isRegularFile(jar)) throw e;
            }
        } finally { Files.deleteIfExists(pending); }
        return jar.toAbsolutePath();
    }
}
