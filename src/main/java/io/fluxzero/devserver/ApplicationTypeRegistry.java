/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Resolves fixture type aliases using the registry generated for the running applications. */
final class ApplicationTypeRegistry {
    private static final Path REGISTRY_FILE =
            Path.of("META-INF", "io.fluxzero.common.serialization.TypeRegistry");

    private final Map<String, Set<String>> projectTypes = new LinkedHashMap<>();
    private volatile Index index = Index.empty();

    static Set<String> read(List<ApplicationBuild> applications) throws IOException {
        Set<String> result = new TreeSet<>();
        for (ApplicationBuild application : applications) {
            for (Path classesDirectory : application.classesDirectories()) {
                Path registry = classesDirectory.resolve(REGISTRY_FILE);
                if (Files.isRegularFile(registry)) {
                    Files.readAllLines(registry).stream().map(String::trim).filter(value -> !value.isEmpty())
                            .forEach(result::add);
                }
            }
        }
        return Set.copyOf(result);
    }

    synchronized void update(String projectId, Collection<String> types) {
        projectTypes.put(projectId, Set.copyOf(types));
        index = Index.of(projectTypes.values().stream().flatMap(Collection::stream).toList());
    }

    String resolve(String type) {
        return index.resolve(type).orElse(type);
    }

    private record Index(Set<String> fullNames, Map<String, String> aliases) {
        static Index empty() {
            return new Index(Set.of(), Map.of());
        }

        static Index of(Collection<String> candidates) {
            Set<String> fullNames = new TreeSet<>();
            Map<String, String> aliases = new LinkedHashMap<>();
            candidates.stream().filter(value -> value != null && !value.isBlank()).sorted().forEach(type -> {
                fullNames.add(type);
                aliases.putIfAbsent(simpleName(type), type);
            });
            return new Index(Set.copyOf(fullNames), Map.copyOf(aliases));
        }

        Optional<String> resolve(String alias) {
            String exact = aliases.get(alias);
            if (exact != null) {
                return Optional.of(exact);
            }
            String suffix = alias.startsWith(".") ? alias : "." + alias;
            return fullNames.stream().sorted().filter(type -> type.endsWith(suffix)).findFirst();
        }

        private static String simpleName(String fullyQualifiedName) {
            int separator = Math.max(fullyQualifiedName.lastIndexOf('.'), fullyQualifiedName.lastIndexOf('$'));
            return separator < 0 ? fullyQualifiedName : fullyQualifiedName.substring(separator + 1);
        }
    }
}
