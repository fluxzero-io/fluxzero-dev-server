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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** A pinned, checksum-verified native download shared only as an immutable executable. */
final class VictoriaLogsArtifact {
    static final String VERSION = "1.52.0";
    private static final Map<String, String> DIGESTS = Map.of(
            "darwin-arm64", "3157d4b6181d8a7e3e30918e2cbfcd4cc4cb66263e3ef21ea91e4f20f8980883",
            "darwin-amd64", "5ac429b81dfa007c258c537eeb63eb59bd6a8f10e8686507970c18a1b3d2dd5a",
            "linux-arm64", "91338c3e5e3d743a862c0a8665bf80862f639dbd4de6f6ff19ada7df5e9acf45",
            "linux-amd64", "d14f585144b8d6813f15e11f0041f487e15e10e5f5e5a31be0311367e93d3494",
            "windows-amd64", "cec110095b02da7f9ed3946d1defacbfc015b4e8ad767659939ee1bcadbf43e4");

    static String platform(String os, String arch) {
        os = os.toLowerCase(Locale.ROOT);
        arch = arch.toLowerCase(Locale.ROOT);
        String target = (os.contains("mac") ? "darwin" : os.contains("linux") ? "linux" : os.startsWith("windows") ? "windows" : "unsupported")
                + "-" + (arch.equals("aarch64") || arch.equals("arm64") ? "arm64"
                         : arch.equals("x86_64") || arch.equals("amd64") ? "amd64" : "unsupported");
        if (!DIGESTS.containsKey(target)) {
            throw new IllegalArgumentException("Native VictoriaLogs download is supported on macOS/Linux arm64/amd64 and Windows amd64; "
                                               + "use monitoring.storage=testserver on " + os + "/" + arch);
        }
        return target;
    }

    static Path resolve(Consumer<String> log) throws Exception {
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path cache = Path.of(System.getProperty("user.home"), ".fluxzero", "cache", "victorialogs", VERSION, platform);
        Files.createDirectories(cache);
        try (var channel = FileChannel.open(cache.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            Path binary = cache.resolve(platform.startsWith("windows-") ? "victoria-logs-prod.exe" : "victoria-logs-prod");
            if (Files.isRegularFile(binary) && (platform.startsWith("windows-") || Files.isExecutable(binary))) return binary;
            String name = archiveName(platform);
            Path archive = cache.resolve(name);
            if (!Files.isRegularFile(archive)) {
                log.accept("Downloading VictoriaLogs " + VERSION + " (" + platform + ")");
                try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15)).build()) {
                    var request = HttpRequest.newBuilder(URI.create("https://github.com/VictoriaMetrics/VictoriaLogs/releases/download/v"
                            + VERSION + "/" + name)).timeout(Duration.ofMinutes(2)).build();
                    Path download = cache.resolve("download.tmp");
                    var response = http.send(request, HttpResponse.BodyHandlers.ofFile(download));
                    if (response.statusCode() != 200) throw new IOException("VictoriaLogs download HTTP " + response.statusCode());
                    verify(download, DIGESTS.get(platform));
                    Files.move(download, archive, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            verify(archive, DIGESTS.get(platform));
            Path unpack = Files.createTempDirectory(cache, "unpack-");
            Path extracted = unpack.resolve(binary.getFileName());
            try {
                extract(archive, extracted, platform);
                Files.move(extracted, binary, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(extracted);
                Files.deleteIfExists(unpack);
            }
            if (!platform.startsWith("windows-") && !binary.toFile().setExecutable(true, true)) {
                throw new IOException("Cannot make VictoriaLogs executable");
            }
            return binary;
        }
    }

    static String archiveName(String platform) {
        return "victoria-logs-" + platform + "-v" + VERSION + (platform.startsWith("windows-") ? ".zip" : ".tar.gz");
    }

    static void extract(Path archive, Path destination, String platform) throws Exception {
        if (platform.startsWith("windows-")) {
            // Copy only the expected root entry; archive paths are never used as output paths.
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entry = zip.getEntry("victoria-logs-" + platform + "-prod.exe");
                if (entry == null || entry.isDirectory()) throw new IOException("VictoriaLogs executable missing from ZIP");
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } else {
            Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destination.getParent().toString(),
                                             "victoria-logs-prod").redirectErrorStream(true).start();
            if (!tar.waitFor(30, TimeUnit.SECONDS)) {
                ProcessUtils.forceStopTree(tar); throw new IOException("VictoriaLogs unpack timed out");
            }
            if (tar.exitValue() != 0) throw new IOException("Could not unpack VictoriaLogs: " + new String(tar.getInputStream().readAllBytes()));
        }
    }

    static void verify(Path file, String expected) throws Exception {
        if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("VictoriaLogs archive exceeds 64 MiB");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!actual.equals(expected)) throw new IOException("VictoriaLogs checksum mismatch: " + file);
    }
}
