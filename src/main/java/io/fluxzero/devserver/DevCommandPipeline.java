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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.exception.FunctionalException;
import io.fluxzero.sdk.common.exception.TechnicalException;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.publishing.DefaultRequestHandler;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Discovers and submits retryable dev seed commands.
 */
@Slf4j
final class DevCommandPipeline implements AutoCloseable {
    static final Path COMMAND_DIRECTORY = Path.of("src", "test", "resources", "fluxzero", "dev", "commands");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    private final DevServerConfig config;
    private final DevSessionStore sessionStore;
    private final Consumer<DevCommandStatus> statusConsumer;
    private final Consumer<String> output;
    private final String sessionId;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean rerunRequested = new AtomicBoolean();
    private final WebSocketClient client;
    private final GatewayClient commandGateway;
    private final RequestHandler requestHandler;

    DevCommandPipeline(DevServerConfig config, DevSessionStore sessionStore, String runtimeBaseUrl,
                       Consumer<DevCommandStatus> statusConsumer, Consumer<String> output) {
        this(config, sessionStore, runtimeBaseUrl, statusConsumer, output, "test-session");
    }

    DevCommandPipeline(DevServerConfig config, DevSessionStore sessionStore, String runtimeBaseUrl,
                       Consumer<DevCommandStatus> statusConsumer, Consumer<String> output, String sessionId) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.statusConsumer = statusConsumer;
        this.output = output;
        this.sessionId = sessionId;
        WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl(runtimeBaseUrl)
                .name(config.applicationName() + "-dev-commands")
                .namespace(config.namespace())
                .id(config.applicationName() + "-dev-commands")
                .build();
        this.client = WebSocketClient.newInstance(clientConfig);
        this.commandGateway = client.getGatewayClient(MessageType.COMMAND);
        this.requestHandler = new DefaultRequestHandler(client, MessageType.RESULT, COMMAND_TIMEOUT,
                                                        config.applicationName() + "_dev_commands");
    }

    void requestRun() {
        rerunRequested.set(true);
        if (running.compareAndSet(false, true)) {
            executor.submit(this::runLoop);
        }
    }

    private void runLoop() {
        try {
            while (rerunRequested.getAndSet(false)) {
                runOnce();
            }
        } finally {
            running.set(false);
            if (rerunRequested.get() && running.compareAndSet(false, true)) {
                executor.submit(this::runLoop);
            }
        }
    }

    private void runOnce() {
        try {
            List<CommandFile> commands = discover();
            if (commands.isEmpty()) {
                update(DevCommandStatus.empty(sessionId));
                return;
            }
            Map<String, DevCommandStatus.Entry> previous = previousEntries();
            List<DevCommandStatus.Entry> entries = new ArrayList<>();
            for (CommandFile command : commands) {
                DevCommandStatus.Entry entry = previous.get(command.identity());
                if (entry == null || !command.hash().equals(entry.hash())) {
                    entry = new DevCommandStatus.Entry(command.path(), command.hash(), command.type(), "pending",
                                                       "waiting for app readiness", 0);
                }
                entries.add(entry);
            }
            update(status("running", entries));
            List<DevCommandStatus.Entry> updated = new ArrayList<>();
            for (int i = 0; i < commands.size(); i++) {
                CommandFile command = commands.get(i);
                DevCommandStatus.Entry entry = entries.get(i);
                if ("succeeded".equals(entry.state()) && command.hash().equals(entry.hash())) {
                    updated.add(entry);
                    continue;
                }
                DevCommandStatus.Entry result = execute(command, entry);
                updated.add(result);
                if ("failed".equals(result.state())) {
                    List<DevCommandStatus.Entry> blocked = blockRemaining(
                            entries.subList(i + 1, entries.size()), command.path());
                    updated.addAll(blocked);
                    long blockedCount = blocked.stream()
                            .filter(candidate -> "blocked".equals(candidate.state())).count();
                    if (blockedCount > 0) {
                        output.accept("[commands] blocked " + blockedCount + " command(s) after " + command.path());
                    }
                    update(status("failed", updated));
                    return;
                }
                update(status("running", updated, entries.subList(i + 1, entries.size())));
            }
            update(status(finalState(updated), updated));
        } catch (Exception e) {
            output.accept("[commands] failed: " + e.getMessage());
            update(new DevCommandStatus("failed", sessionId, 0, 0, 1, 0, 0, List.of(),
                                        Instant.now().toEpochMilli()));
        }
    }

    private DevCommandStatus.Entry execute(CommandFile command, DevCommandStatus.Entry entry) {
        try {
            output.accept("[commands] executing " + command.path());
            SerializedMessage response = requestHandler.sendRequest(
                    command.message(),
                    message -> commandGateway.append(Guarantee.SENT, message),
                    COMMAND_TIMEOUT).get();
            String responseType = response.data().getType();
            if (isFailureResponse(responseType)) {
                output.accept("[commands] failed " + command.path() + ": handler returned " + responseType);
                return entry.withState("failed", "handler returned " + responseType);
            }
            return entry.withState("succeeded", "processed by app: " + responseType);
        } catch (Exception e) {
            output.accept("[commands] failed " + command.path() + ": " + e.getMessage());
            return entry.withState("failed", e.getMessage());
        }
    }

    private static boolean isFailureResponse(String responseType) {
        if (responseType == null || responseType.isBlank()) {
            return false;
        }
        if (TechnicalException.class.getName().equals(responseType)
            || FunctionalException.class.getName().equals(responseType)
            || Throwable.class.getName().equals(responseType)) {
            return true;
        }
        try {
            return Throwable.class.isAssignableFrom(Class.forName(
                    responseType, false, DevCommandPipeline.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            return responseType.endsWith("Exception") || responseType.endsWith("Error");
        }
    }

    private List<CommandFile> discover() throws IOException {
        List<CommandFile> result = new ArrayList<>();
        DevProjectConfig.load(config.projectDirectory()).commands().forEach((id, command) -> {
            try {
                result.add(readConfiguredCommand(id, command));
            } catch (IOException e) {
                throw new IllegalStateException("Could not read commands." + id + ": " + e.getMessage(), e);
            }
        });
        Path directory = config.projectDirectory().resolve(COMMAND_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return List.copyOf(result);
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths.filter(path -> Files.isRegularFile(path)
                                                   && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(this::normalizedRelativePath))
                    .toList();
            for (Path file : files) {
                result.add(readCommand(file));
            }
            return List.copyOf(result);
        }
    }

    private CommandFile readConfiguredCommand(String id, DevCommandConfig command) throws IOException {
        String identity = "commands." + id;
        JsonNode payload = command.payload() == null || command.payload().isNull()
                ? objectMapper.createObjectNode() : command.payload();
        return createCommand(identity, commandHash(command.type(), command.effectiveRevision(), payload,
                                                   command.metadata()),
                             command.type(), command.effectiveRevision(), payload,
                             command.metadata());
    }

    private CommandFile readCommand(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        JsonNode root = objectMapper.readTree(bytes);
        String type = requiredText(root, "type", file);
        int revision = root.path("revision").isMissingNode() ? 0 : root.path("revision").asInt();
        JsonNode payload = root.path("payload").isMissingNode() ? objectMapper.createObjectNode()
                : root.path("payload");
        Map<String, Object> metadata = root.path("metadata").isObject()
                ? objectMapper.convertValue(root.path("metadata"), new TypeReference<>() {
                })
                : Map.of();
        String relativePath = normalizedRelativePath(file);
        return createCommand(relativePath, commandHash(type, revision, payload, metadata),
                             type, revision, payload, metadata);
    }

    private String commandHash(String type, int revision, JsonNode payload, Map<String, Object> metadata)
            throws IOException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", type);
        definition.put("revision", revision);
        definition.put("payload", objectMapper.convertValue(payload, Object.class));
        definition.put("metadata", metadata);
        return sha256(objectMapper.writeValueAsBytes(definition));
    }

    private CommandFile createCommand(String identity, String hash, String type, int revision, JsonNode payload,
                                      Map<String, Object> metadata) throws IOException {
        Metadata commandMetadata = Metadata.of(metadata)
                .with("fluxzero.dev.command.path", identity)
                .with("fluxzero.dev.command.hash", hash);
        SerializedMessage message = new SerializedMessage(
                new Data<>(objectMapper.writeValueAsBytes(payload), type, revision, Data.JSON_FORMAT),
                commandMetadata,
                "dev-seed:" + sha256((identity + "\0" + hash).getBytes(StandardCharsets.UTF_8)),
                Instant.now().toEpochMilli());
        return new CommandFile(identity, hash, type, message);
    }

    private Map<String, DevCommandStatus.Entry> previousEntries() {
        Optional<DevCommandStatus> status = sessionStore.readCommandStatus();
        Map<String, DevCommandStatus.Entry> result = new LinkedHashMap<>();
        status.filter(value -> java.util.Objects.equals(sessionId, value.sessionId()))
                .stream().flatMap(s -> s.commands().stream()).forEach(entry -> result.put(entry.path(), entry));
        return result;
    }

    private String normalizedRelativePath(Path file) {
        return config.projectDirectory().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private void update(DevCommandStatus status) {
        sessionStore.writeCommandStatus(status);
        statusConsumer.accept(status);
    }

    private DevCommandStatus status(String state, List<DevCommandStatus.Entry> entries) {
        return status(state, entries, List.of());
    }

    private DevCommandStatus status(String state, List<DevCommandStatus.Entry> entries,
                                    List<DevCommandStatus.Entry> remaining) {
        List<DevCommandStatus.Entry> all = new ArrayList<>(entries);
        all.addAll(remaining);
        int succeeded = count(all, "succeeded");
        int failed = count(all, "failed");
        int blocked = count(all, "blocked");
        int pending = count(all, "pending");
        return new DevCommandStatus(state, sessionId, all.size(), succeeded, failed, blocked, pending,
                                    List.copyOf(all),
                                    Instant.now().toEpochMilli());
    }

    private static List<DevCommandStatus.Entry> blockRemaining(List<DevCommandStatus.Entry> remaining,
                                                               String blockingPath) {
        return remaining.stream()
                .map(entry -> "succeeded".equals(entry.state()) ? entry : entry.blockedBy(blockingPath))
                .toList();
    }

    private static String finalState(List<DevCommandStatus.Entry> entries) {
        return entries.stream().anyMatch(entry -> "failed".equals(entry.state())) ? "failed" : "succeeded";
    }

    private static int count(List<DevCommandStatus.Entry> entries, String state) {
        return Math.toIntExact(entries.stream().filter(entry -> state.equals(entry.state())).count());
    }

    private static String requiredText(JsonNode root, String field, Path file) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Dev command " + file + " must contain a textual `" + field + "`");
        }
        return value.asText();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        requestHandler.close();
        commandGateway.close();
        client.shutDown();
        try {
            executor.awaitTermination(750, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record CommandFile(String path, String hash, String type, SerializedMessage message) {
        String identity() {
            return path;
        }
    }
}
