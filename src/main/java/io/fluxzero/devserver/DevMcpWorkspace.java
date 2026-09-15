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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Connection-local project selection; selecting never starts or stops a dev environment. */
final class DevMcpWorkspace implements AutoCloseable {
    record Context(Path directory, DevMcpProjectClient project, AgentDocsService docs) {}
    private final Path root;
    private final ObjectMapper mapper;
    private final Map<Path, Context> contexts = new LinkedHashMap<>();
    private volatile Context selected;
    private volatile Consumer<String> listener = ignored -> {};
    private boolean closed;

    DevMcpWorkspace(Path root, Path initial, AgentDocsService docs, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper;
        selected = create(initial, docs);
    }

    Context current() { return selected; }

    synchronized Map<String, Object> select(Map<String, Object> arguments) {
        if (closed) throw new IllegalStateException("MCP workspace is closed");
        Object value = arguments.get("projectDirectory");
        if (value != null) {
            if (!(value instanceof String path) || path.isBlank())
                throw new IllegalArgumentException("projectDirectory must be a nonempty path relative to the workspace or absolute");
            Path target = root.resolve(path).toAbsolutePath().normalize();
            try {
                if (!target.toRealPath().startsWith(root.toRealPath()) || !Files.isDirectory(target))
                    throw new IllegalArgumentException("Select an existing directory inside " + root);
            } catch (IOException e) { throw new IllegalArgumentException("Project directory does not exist: " + target, e); }
            Context context = contexts.get(target);
            if (context == null) {
                if (contexts.size() >= 16) throw new IllegalArgumentException("Reconnect MCP before selecting more than 16 directories");
                context = create(target, docs(target));
            }
            if (selected != context) {
                selected = context;
                listener.accept(DevMcpServer.DIAGNOSTICS_RESOURCE);
            }
        }
        return Map.of("projectDirectory", selected.directory().toString(), "workspaceDirectory", root.toString(),
                "candidates", candidates(root).stream().map(Path::toString).toList(),
                "development", selected.project().availability());
    }

    void onResourceChange(Consumer<String> listener) { this.listener = listener; }

    private Context create(Path directory, AgentDocsService docs) {
        var project = new DevMcpProjectClient(directory, mapper);
        Context context = new Context(directory, project, docs);
        project.onResourceChange(uri -> { if (selected == context) listener.accept(uri); });
        contexts.put(directory, context);
        return context;
    }

    static AgentDocsService docs(Path directory) {
        return new AgentDocsService(() -> DevServerConfig.fromArgs(new String[]{"--project-dir", directory.toString()}),
                AgentDocsStore.forProject(directory));
    }

    static Path initialDirectory(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        if (hasProject(root)) return root;
        List<Path> candidates = candidates(root);
        return candidates.size() == 1 ? candidates.getFirst() : root;
    }

    private static boolean hasProject(Path directory) {
        return List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", ".fluxzero/dev.yaml")
                .stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)));
    }

    static List<Path> candidates(Path root) {
        Set<String> ignored = Set.of("node_modules", "target", "build", "dist");
        try (var children = Files.list(root)) {
            return children.filter(Files::isDirectory).filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> !p.getFileName().toString().startsWith(".") && !ignored.contains(p.getFileName().toString()))
                    .filter(p -> Files.isRegularFile(p.resolve(".fluxzero/dev.yaml"))
                            || !FluxzeroSdkVersionDetector.detectDeclared(p).isEmpty())
                    .sorted().toList();
        } catch (IOException e) { return List.of(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        contexts.values().forEach(context -> { context.docs().close(); context.project().close(); });
        contexts.clear();
    }
}
