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

import io.fluxzero.common.application.DefaultPropertySource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Resolves optional local BFF defaults with the application's SDK, before invoking its main method. */
public final class ManagedBffLauncher {
    private ManagedBffLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 2) throw new IllegalArgumentException("Missing BFF key path or application main class");
        var properties = new DefaultPropertySource();
        String disabled = properties.get("fluxzero.auth.oidc.disabled");
        String key = properties.get("fluxzero.auth.session-key-file");
        if (!Boolean.parseBoolean(disabled == null ? null : disabled.trim()) && (key == null || key.isBlank())) {
            System.setProperty("fluxzero.auth.session-key-file", sessionKey(Path.of(args[0])).toString());
        }
        TestApplicationLauncher.main(Arrays.copyOfRange(args, 1, args.length));
    }

    static synchronized Path sessionKey(Path file) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        try (var channel = FileChannel.open(directory.resolve("session.lock"),
                                           StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Local BFF session key must be a regular file");
                }
                return file; // Retain existing material, including across dev-server restarts.
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Path pending = Files.getFileStore(directory).supportsFileAttributeView("posix")
                    ? Files.createTempFile(directory, "session-", ".tmp",
                          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(directory, "session-", ".tmp");
            try {
                Files.writeString(pending, """
                        {"activeKid":"local","keys":[{"kid":"local","kty":"oct","alg":"A256GCM","use":"enc","k":"%s"}]}
                        """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(key)));
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(pending); }
            return file;
        }
    }
}
