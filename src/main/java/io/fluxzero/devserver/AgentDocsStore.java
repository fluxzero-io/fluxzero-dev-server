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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Shared on-disk archives with bounded, coalesced acquisition; no application lifecycle dependency. */
final class AgentDocsStore implements AutoCloseable {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[32];
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Map<String, String> COORDINATES = Map.of(
            "sdk", "io/fluxzero/fluxzero-sdk-java/{version}/fluxzero-sdk-java-{version}-agent-docs.zip");
    static {
        Arrays.setAll(LOCKS, ignored -> new ReentrantLock());
    }

    private final Path cacheRoot;
    private final URI repository;
    private final Map<String, Path> localArchives;
    private final Map<String, String> coordinates;
    private final Fetcher fetcher;
    private final Map<String, AgentDocsGraph> parsed = new LinkedHashMap<>(8, 0.75f, true);

    AgentDocsStore() {
        this(Path.of(setting("cacheDirectory", "CACHE_DIRECTORY",
                             Path.of(System.getProperty("user.home"), ".fluxzero/cache/agent-docs").toString())),
             repository(setting("repository", "REPOSITORY", "https://packages.fluxzero.io/maven/")),
             localArchives());
    }

    AgentDocsStore(Path cacheRoot, URI repository, Map<String, Path> localArchives) {
        this(cacheRoot, repository, localArchives, COORDINATES, new HttpFetcher());
    }

    AgentDocsStore(Path cacheRoot, URI repository, Map<String, Path> localArchives,
                   Map<String, String> coordinates, Fetcher fetcher) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.repository = repository(repository.toString());
        this.localArchives = Map.copyOf(localArchives);
        this.coordinates = Map.copyOf(coordinates);
        this.fetcher = fetcher;
    }

    Set<String> namespaces() { return coordinates.keySet(); }

    AgentDocsReleases.Release latest(String namespace) {
        String coordinate = coordinates.get(namespace);
        if (coordinate == null) throw new IllegalArgumentException("Unavailable documentation namespace: " + namespace);
        String component = coordinate.substring(0, coordinate.indexOf("{version}"));
        return AgentDocsReleases.resolve(cacheRoot.resolve(namespace), repository.resolve(component + "maven-metadata.xml"), fetcher);
    }

    Loaded load(AgentDocsGraph.Identity identity) {
        if (!coordinates.containsKey(identity.namespace())) {
            throw new IllegalArgumentException("Unavailable documentation namespace: " + identity.namespace());
        }
        Path directory = cacheRoot.resolve(identity.namespace()).resolve(identity.version());
        ReentrantLock processLock = LOCKS[Math.floorMod(directory.hashCode(), LOCKS.length)];
        boolean acquired = false;
        try {
            acquired = processLock.tryLock(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IOException("Timed out waiting for documentation cache access");
            }
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(directory.resolve("resolve.lock"),
                                                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lock(channel)) {
                Path archive = directory.resolve("agent-docs.zip");
                Path checksum = directory.resolve("agent-docs.zip.sha256");
                Path local = localArchives.get(identity.namespace());
                if (local != null) {
                    byte[] bytes = readLimited(local, AgentDocsGraph.MAX_ARCHIVE_BYTES);
                    String digest = AgentDocsGraph.sha256(bytes);
                    AgentDocsGraph graph = parse(bytes, identity, digest);
                    if (!validChecksum(archive, checksum, digest)) {
                        publish(archive, checksum, bytes, digest);
                    }
                    return new Loaded(graph, "local-archive");
                }
                if (Files.isRegularFile(archive) && Files.isRegularFile(checksum)) {
                    try {
                        byte[] bytes = readLimited(archive, AgentDocsGraph.MAX_ARCHIVE_BYTES);
                        String digest = checksum(readLimited(checksum, 256));
                        if (digest.equals(AgentDocsGraph.sha256(bytes))) {
                            return new Loaded(parse(bytes, identity, digest), "cache");
                        }
                    } catch (IOException | IllegalArgumentException ignoredCache) {
                        // Recover a corrupt cache only from an independently validated source.
                    }
                }
                if (identity.version().toUpperCase(java.util.Locale.ROOT).endsWith("-SNAPSHOT")) {
                    throw new IOException("Snapshot documentation needs an explicit local archive; no latest fallback");
                }
                String relative = coordinates.get(identity.namespace()).replace("{version}", identity.version());
                URI source = repository.resolve(relative);
                String digest = checksum(fetcher.read(URI.create(source + ".sha256"), 256));
                byte[] bytes = fetcher.read(source, AgentDocsGraph.MAX_ARCHIVE_BYTES);
                if (!digest.equals(AgentDocsGraph.sha256(bytes))) {
                    throw new IOException("Documentation archive SHA-256 mismatch");
                }
                AgentDocsGraph graph = parse(bytes, identity, digest);
                publish(archive, checksum, bytes, digest);
                return new Loaded(graph, "downloaded");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Documentation acquisition interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + identity.namespace() + " documentation " + identity.version()
                                            + ": " + e.getMessage() + ". Use the matching release or an explicit local archive; "
                                            + "do not upgrade the project to match documentation.", e);
        } finally {
            if (acquired) {
                processLock.unlock();
            }
        }
    }

    private AgentDocsGraph parse(byte[] bytes, AgentDocsGraph.Identity identity, String digest) {
        String key = identity + ":" + digest;
        synchronized (parsed) {
            AgentDocsGraph result = parsed.get(key);
            if (result == null) {
                result = AgentDocsGraph.read(bytes, identity);
                parsed.put(key, result);
                while (parsed.size() > 4) {
                    parsed.remove(parsed.keySet().iterator().next());
                }
            }
            return result;
        }
    }

    private static boolean validChecksum(Path archive, Path checksum, String expected) {
        try {
            return Files.isRegularFile(archive) && Files.isRegularFile(checksum)
                   && expected.equals(checksum(readLimited(checksum, 256)))
                   && expected.equals(AgentDocsGraph.sha256(readLimited(archive, AgentDocsGraph.MAX_ARCHIVE_BYTES)));
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static void publish(Path archive, Path checksum, byte[] bytes, String digest) throws IOException {
        // Readers hold the same process/file locks, so they cannot observe the two renames halfway through.
        atomicWrite(archive, bytes);
        atomicWrite(checksum, (digest + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".agent-docs-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static FileLock lock(FileChannel channel) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        do {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Another classloader in this JVM may own the same cache entry.
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new IOException("Timed out waiting for another documentation downloader");
    }

    static byte[] readLimited(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Documentation file exceeds the size limit");
            }
            return bytes;
        }
    }

    private static String checksum(byte[] bytes) {
        String result = new String(bytes, StandardCharsets.US_ASCII).strip().toLowerCase(java.util.Locale.ROOT);
        if (!result.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid documentation SHA-256 checksum");
        }
        return result;
    }

    private static String setting(String property, String environment, String fallback) {
        String result = System.getProperty("fluxzero.dev.docs." + property);
        if (result == null || result.isBlank()) {
            result = System.getenv("FLUXZERO_DEV_DOCS_" + environment);
        }
        return result == null || result.isBlank() ? fallback : result.strip();
    }

    private static Map<String, Path> localArchives() {
        String sdk = setting("sdk.archive", "SDK_ARCHIVE", null);
        return sdk == null ? Map.of() : Map.of("sdk", Path.of(sdk).toAbsolutePath().normalize());
    }

    private static URI repository(String value) {
        URI uri = URI.create(value.endsWith("/") ? value : value + "/");
        if (!Set.of("https", "http", "file").contains(uri.getScheme()) || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Documentation repository must be an HTTP(S) or file root without credentials");
        }
        return uri;
    }

    @Override
    public void close() { fetcher.close(); }

    record Loaded(AgentDocsGraph graph, String source) {}

    @FunctionalInterface
    interface Fetcher extends AutoCloseable {
        byte[] read(URI uri, int limit) throws IOException, InterruptedException;
        @Override
        default void close() {}
    }

    private static final class HttpFetcher implements Fetcher {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override
        public byte[] read(URI uri, int limit) throws IOException, InterruptedException {
            if (uri.getScheme().equals("file")) {
                return readLimited(Path.of(uri), limit);
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            var future = client.sendAsync(request, ignored -> new BoundedBody(limit));
            try {
                HttpResponse<byte[]> response = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) {
                    throw new IOException("Documentation repository returned HTTP " + response.statusCode());
                }
                return response.body();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new IOException("Documentation download failed or timed out", e);
            } finally {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        @Override
        public void close() { client.shutdownNow(); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBody(int limit) { this.limit = limit; }
        @Override
        public CompletionStage<byte[]> getBody() { return result; }
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Documentation response exceeds the size limit"));
                    return;
                }
                byte[] part = new byte[item.remaining()];
                item.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override
        public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
