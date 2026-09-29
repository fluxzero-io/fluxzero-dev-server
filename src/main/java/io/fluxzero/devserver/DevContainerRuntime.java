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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Runtime CLI adapter. Registry output is never forwarded into diagnostics. */
final class DevContainerRuntime {
    static final String SESSION_LABEL = "io.fluxzero.dev.session";
    static final String PROJECT_LABEL = "io.fluxzero.dev.project";
    static final String HOST_ALIAS = "fluxzero.host.internal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path JOURNAL = Path.of(".fluxzero", "dev", "containers.json");
    private final DevContainerConfig config;
    private final Path project;
    private final Path directory;
    private final Map<String, String> environment;
    private final Map<String, Integer> ports;
    private final DevPlaceholderResolver resolver;
    private final Owned owned;
    private final String id;
    private final Consumer<Map<String, String>> updates;
    private final Map<String, String> metadata = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch prepared = new CountDownLatch(1);
    private volatile Process operation;
    private volatile boolean preparing;

    DevContainerRuntime(DevContainerConfig config, Path project, Path directory, String session, String id,
                        Map<String, Integer> ports, Map<String, String> environment, DevPlaceholderResolver resolver,
                        Consumer<Map<String, String>> updates) {
        this.config = config;
        this.project = project;
        this.directory = directory;
        this.environment = environment;
        this.ports = ports;
        this.resolver = resolver;
        this.id = id;
        this.updates = updates;
        String prefix = "fz-" + session.replaceAll("[^a-zA-Z0-9-]", "");
        String network = config.network() == null ? prefix + "-" + hash(config.runtime()).substring(0, 8) : null;
        owned = new Owned(config.runtime(), prefix + "-service-" + id, network, session, hash(project.toAbsolutePath().normalize().toString()));
        metadata.putAll(Map.of("container.image", config.image(), "container.runtime", config.runtime(),
                "container.name", owned.name(), "container.network", network == null ? config.network() : network,
                "container.pullPolicy", config.pull(), "container.registryAccess", "not-checked", "container.phase", "pending"));
    }

    Map<String, String> metadata() { return Map.copyOf(metadata); }

    void prepare(Duration timeout) {
        preparing = true;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            checkOpen();
            journal(project, owned);
            phase("checking-runtime");
            require(run(List.of("info"), deadline), "Runtime unavailable; start the configured container runtime.");
            boolean cached = run(List.of("image", "inspect", "--format", "{{.Id}}", config.image()), deadline).code() == 0;
            metadata.put("container.cachedBeforePull", Boolean.toString(cached));
            boolean pull = List.of("always", "verify").contains(config.pull()) || (config.pull().equals("if-missing") && !cached);
            if (pull) {
                phase("pulling");
                Result result = verifyRegistry(deadline);
                boolean verified = result.code() == 0;
                metadata.put("container.registryAccess", verified ? "verified" : "failed");
                if (verified) {
                    List<String> pullArgs = new ArrayList<>(List.of("pull"));
                    if (config.driver().equals("podman") && Boolean.TRUE.equals(config.registryInsecure())) pullArgs.add("--tls-verify=false");
                    pullArgs.add(config.image());
                    result = run(pullArgs, deadline);
                }
                if (result.code() != 0) {
                    metadata.put("container.failure", verified ? "image-pull" : "registry-access");
                    throw new IllegalStateException("Image verification or pull failed; check the image digest, registry access and runtime credential helper. Cached images do not satisfy this pull policy.");
                }
                metadata.put("container.registryAccess", "verified");
            } else if (!cached) {
                metadata.put("container.failure", "image-missing");
                throw new IllegalStateException("Pinned image is not cached and pull policy is never.");
            }
            Result image = run(List.of("image", "inspect", "--format", "{{.Id}}", config.image()), deadline);
            require(image, "Pinned image is unavailable after pull.");
            String imageId = image.output().strip();
            if (!imageId.matches("sha256:[a-f0-9]{64}")) throw new IllegalStateException("Runtime returned an invalid image identity.");
            metadata.put("container.imageId", imageId);
            metadata.put("container.digest", config.image().substring(config.image().indexOf('@') + 1));
            phase("creating-network");
            if (owned.network() != null) ensureNetwork(deadline);
            phase("creating-container");
            List<String> create = new ArrayList<>(List.of("container", "create", "--pull=never", "--name", owned.name(),
                    "--label", SESSION_LABEL + "=" + owned.session(), "--label", PROJECT_LABEL + "=" + owned.project(),
                    "--network", owned.network() == null ? config.network() : owned.network(), "--network-alias", id,
                    "--add-host", HOST_ALIAS + ":" + config.hostGateway()));
            ports.forEach((name, port) -> create.addAll(List.of("--publish", "127.0.0.1:" + port + ":" + config.ports().get(name))));
            // Values are inherited from the child environment, never put in arguments or the ownership journal.
            environment.keySet().stream().sorted().forEach(name -> create.addAll(List.of("--env", name)));
            for (var mount : config.mounts()) {
                Path source = Path.of(resolver.resolve(mount.source()));
                if (!source.isAbsolute()) source = directory.resolve(source);
                source = source.normalize();
                if (!Files.exists(source)) throw new IllegalStateException("Container mount source does not exist.");
                if (source.toString().contains(",") || resolver.resolve(mount.target()).contains(","))
                    throw new IllegalStateException("Resolved container mount paths must not contain commas.");
                create.addAll(List.of("--mount", "type=bind,source=" + source + ",target=" + resolver.resolve(mount.target())
                        + (Boolean.TRUE.equals(mount.readOnly()) ? ",readonly" : "")));
            }
            if (config.user() != null) create.addAll(List.of("--user", config.user()));
            if (Boolean.TRUE.equals(config.readOnly())) create.add("--read-only");
            config.capDrop().forEach(value -> create.addAll(List.of("--cap-drop", value)));
            config.securityOpt().forEach(value -> create.addAll(List.of("--security-opt", value)));
            create.add(config.image());
            config.command().forEach(value -> create.add(resolver.resolve(value)));
            require(run(create, deadline), "Container creation failed; check ports, mounts, network and security options.");
            phase("starting");
        } catch (RuntimeException e) {
            if ("pulling".equals(metadata.get("container.phase")) && !"verified".equals(metadata.get("container.registryAccess")))
                metadata.put("container.registryAccess", "failed");
            metadata.putIfAbsent("container.failure", metadata.get("container.phase"));
            phase("failed");
            throw e;
        } finally { prepared.countDown(); }
    }

    private Result verifyRegistry(long deadline) {
        List<String> inspect = new ArrayList<>(List.of("manifest", "inspect"));
        if (config.driver().equals("podman")) {
            // Podman otherwise resolves locally-created manifest lists without contacting the registry.
            Result local = run(List.of("manifest", "exists", config.image()), deadline);
            if (local.code() == 0) return new Result(1, "Registry verification cannot use a local manifest list.");
            if (local.code() != 1) return new Result(1, "Runtime manifest lookup failed.");
            if (Boolean.TRUE.equals(config.registryInsecure())) inspect.add("--tls-verify=false");
        } else if (Boolean.TRUE.equals(config.registryInsecure())) inspect.add("--insecure");
        inspect.add(config.image());
        Result manifest = run(inspect, deadline);
        if (manifest.code() != 0 || config.driver().equals("podman")) return manifest;
        try {
            // A fresh Docker manifest transaction always resolves its member from the registry, even if
            // `manifest inspect` found a local list. Never publish this temporary local transaction.
            var parsed = JSON.readTree(manifest.output());
            String member = config.image();
            if (parsed.path("manifests").isArray()) {
                String digest = parsed.path("manifests").path(0).path("digest").asText();
                if (!digest.matches("sha256:[a-f0-9]{64}")) return new Result(1, "Invalid registry manifest.");
                member = config.image().substring(0, config.image().indexOf('@') + 1) + digest;
            }
            List<String> create = new ArrayList<>(List.of("manifest", "create"));
            if (Boolean.TRUE.equals(config.registryInsecure())) create.add("--insecure");
            create.add(verificationName(owned)); create.add(member);
            return run(create, deadline);
        } catch (IOException e) { return new Result(1, "Invalid registry manifest."); }
        finally { run(List.of("manifest", "rm", verificationName(owned)), deadline); }
    }

    private static String verificationName(Owned owned) { return "fluxzero-dev-verification:" + owned.name(); }

    List<String> startCommand() {
        checkOpen();
        return List.of(config.runtime(), "container", "start", "--attach", owned.name());
    }

    private void ensureNetwork(long deadline) {
        Result existing = run(List.of("network", "inspect", "--format", "{{index .Labels \"" + SESSION_LABEL + "\"}}|{{index .Labels \"" + PROJECT_LABEL + "\"}}", owned.network()), deadline);
        if (existing.code() == 0) {
            if (!existing.output().strip().equals(owned.session() + "|" + owned.project())) throw new IllegalStateException("Session network ownership does not match.");
        } else {
            require(run(List.of("network", "create", "--label", SESSION_LABEL + "=" + owned.session(),
                    "--label", PROJECT_LABEL + "=" + owned.project(), owned.network()), deadline), "Could not create session container network.");
        }
    }

    String close(Duration timeout) {
        closed.set(true);
        if (!preparing) return null;
        long deadline = System.nanoTime() + timeout.toNanos();
        Process current = operation;
        if (current != null && current.isAlive()) ProcessUtils.forceStopTree(current);
        try {
            if (preparing && !prepared.await(Math.max(1, remaining(deadline).toMillis() / 2), TimeUnit.MILLISECONDS)) {
                metadata.put("container.phase", "cleanup-incomplete");
                metadata.put("container.failure", "cleanup");
                return "container cleanup deferred; preparation has not stopped";
            }
            cleanup(owned, project, deadline, false, true);
            metadata.put("container.phase", "stopped");
            return null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            metadata.put("container.phase", "cleanup-incomplete");
            metadata.put("container.failure", "cleanup");
            return "container cleanup incomplete; retry on the next Dev Server start";
        }
    }

    private Result run(List<String> arguments, long deadline) {
        checkOpen();
        return execute(config.runtime(), arguments, directory, environment, remaining(deadline), process -> {
            operation = process;
            if (closed.get()) process.destroyForcibly();
        });
    }

    private void checkOpen() { if (closed.get()) throw new IllegalStateException("Container service is stopped."); }
    private void phase(String phase) { metadata.put("container.phase", phase); updates.accept(metadata()); }
    private static void require(Result result, String message) { if (result.code() != 0) throw new IllegalStateException(message); }
    private static Duration remaining(long deadline) { return Duration.ofNanos(Math.max(0, deadline - System.nanoTime())); }

    static void reconcile(Path project) {
        List<Owned> previous = readJournal(project);
        if (previous.isEmpty()) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (Owned owned : previous) {
            if (!owned.project().equals(hash(project.toAbsolutePath().normalize().toString())))
                throw new IllegalStateException("Container cleanup journal belongs to a different project.");
            cleanup(owned, project, deadline, false);
        }
        // All owned containers are gone before requiring shared network removal to succeed.
        for (Owned owned : previous) cleanup(owned, project, deadline, true);
        try { Files.deleteIfExists(project.resolve(JOURNAL)); }
        catch (IOException e) { throw new IllegalStateException("Could not clear container cleanup journal."); }
    }

    private static void cleanup(Owned owned, Path directory, long deadline, boolean strictNetwork) {
        cleanup(owned, directory, deadline, strictNetwork, false);
    }

    private static void cleanup(Owned owned, Path directory, long deadline, boolean strictNetwork, boolean graceful) {
        List<String> filters = List.of("--filter", "label=" + SESSION_LABEL + "=" + owned.session(),
                "--filter", "label=" + PROJECT_LABEL + "=" + owned.project(), "--format", "{{.Names}}");
        List<String> containers = new ArrayList<>(List.of("container", "ls", "--all")); containers.addAll(filters);
        Result listed = execute(owned.runtime(), containers, directory, Map.of(), remaining(deadline), ignored -> {});
        require(listed, "Cannot reconcile owned containers; check runtime availability.");
        if (listed.output().lines().anyMatch(owned.name()::equals)) {
            if (graceful) {
                // Reserve half of the shared shutdown budget for removal and network cleanup.
                long seconds = remaining(deadline).toSeconds() / 2;
                execute(owned.runtime(), List.of("container", "stop", "--time", Long.toString(seconds), owned.name()),
                        directory, Map.of(), remaining(deadline), ignored -> {});
            }
            require(execute(owned.runtime(), List.of("container", "rm", "--force", owned.name()), directory, Map.of(), remaining(deadline), ignored -> {}),
                    "Could not remove owned container.");
        }
        execute(owned.runtime(), List.of("manifest", "rm", verificationName(owned)), directory, Map.of(), remaining(deadline), ignored -> {});
        if (owned.network() != null) {
            List<String> networks = new ArrayList<>(List.of("network", "ls")); networks.addAll(filters);
            networks.set(networks.size() - 1, "{{.Name}}");
            Result networkList = execute(owned.runtime(), networks, directory, Map.of(), remaining(deadline), ignored -> {});
            require(networkList, "Cannot reconcile session container network.");
            if (networkList.output().lines().anyMatch(owned.network()::equals)) {
                Result result = execute(owned.runtime(), List.of("network", "rm", owned.network()), directory, Map.of(), remaining(deadline), ignored -> {});
                // Other services still use this session network during sequential shutdown. The final one removes it.
                if (strictNetwork) require(result, "Could not remove session container network.");
            }
        }
    }

    private static synchronized void journal(Path project, Owned owned) {
        List<Owned> entries = new ArrayList<>(readJournal(project));
        if (!entries.contains(owned)) entries.add(owned);
        Path file = project.resolve(JOURNAL), temporary = file.resolveSibling("containers.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            JSON.writeValue(temporary.toFile(), entries);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("Could not record container ownership before startup."); }
    }

    private static List<Owned> readJournal(Path project) {
        Path file = project.resolve(JOURNAL);
        if (!Files.exists(file)) return List.of();
        try { return JSON.readValue(file.toFile(), new TypeReference<List<Owned>>() {}); }
        catch (IOException e) { throw new IllegalStateException("Cannot read container cleanup journal."); }
    }

    private static Result execute(String runtime, List<String> args, Path directory, Map<String, String> environment,
                                  Duration timeout, Consumer<Process> started) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalStateException("Container operation timed out.");
        List<String> command = new ArrayList<>(List.of(runtime)); command.addAll(args);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            if (ProcessUtils.isWindows()) WindowsProcessEnvironment.apply(builder.environment(), environment);
            else builder.environment().putAll(environment);
            process = builder.start(); started.accept(process);
            Process running = process;
            var output = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var input = running.getInputStream()) {
                    byte[] bytes = new byte[4096]; int count;
                    while ((count = input.read(bytes)) != -1) {
                        if (output.size() < 65536) output.write(bytes, 0, Math.min(count, 65536 - output.size()));
                    }
                } catch (IOException ignored) { }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("Container operation timed out.");
            reader.join(Duration.ofSeconds(1));
            if (reader.isAlive()) throw new IllegalStateException("Container output did not finish.");
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Could not launch container runtime; check container.runtime and PATH."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Container operation interrupted."); }
        finally { if (process != null && process.isAlive()) ProcessUtils.forceStopTree(process); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    record Owned(String runtime, String name, String network, String session, String project) {}
    private record Result(int code, String output) {}
}
