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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.fluxzero.devserver.AgentDocsFixture.*;
import static io.fluxzero.devserver.AgentDocsStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDocsServiceTest {
    @Test
    void selectsProjectVersionWithoutCompilationOrRuntimeOverrideAndDetectsEdits(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("docs.zip");
        Files.write(archive, archive("sdk", "1.2.3"));
        try (var service = new AgentDocsService(() -> DevServerConfig.defaults(directory),
                store(directory.resolve("cache"), Map.of("sdk", archive), offline()))) {
            assertThrows(IllegalStateException.class, () -> service.start(Map.of()));
            pom(directory, "1.2.3");
            String previous = System.getProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY);
            try {
                System.setProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY, "9.9.9");
                assertEquals("1.2.3", json(service.start(Map.of())).path("version").asText());
            } finally {
                if (previous == null) { System.clearProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY); }
                else { System.setProperty(FluxzeroSdkVersionDetector.VERSION_OVERRIDE_PROPERTY, previous); }
            }
            pom(directory, "1.2.4");
            assertThrows(IllegalArgumentException.class, () -> service.start(Map.of()));
            assertEquals("1.2.3", json(service.start(Map.of("version", "1.2.3"))).path("version").asText());
        }
    }

    @Test
    void selectsComposedProjectsSeparatelyAndRejectsMismatches(@TempDir Path directory) throws Exception {
        pom(directory.resolve("orders"), "1.2.3");
        pom(directory.resolve("audit"), "1.2.4");
        Files.createDirectories(directory.resolve(".fluxzero"));
        Files.writeString(directory.resolve(".fluxzero/dev.yaml"), """
                version: 1
                projects:
                  orders:
                    directory: orders
                  audit:
                    directory: audit
                """);
        Path archive = directory.resolve("docs.zip");
        Files.write(archive, archive("sdk", "1.2.3"));
        var config = DevServerConfig.fromArgs(new String[]{"--project-dir", directory.toString()});
        try (var service = new AgentDocsService(() -> config,
                store(directory.resolve("cache"), Map.of("sdk", archive), offline()))) {
            assertEquals("selection-required", json(service.start(Map.of())).path("status").asText());
            var start = json(service.start(Map.of("projectId", "orders")));
            assertEquals("orders", start.path("projectId").asText());
            assertEquals("1.2.3", start.path("version").asText());
            assertThrows(IllegalArgumentException.class, () -> service.start(Map.of("projectId", "audit", "version", "1.2.3")));
            assertThrows(IllegalArgumentException.class, () -> service.start(Map.of("projectId", "missing")));
        }
    }

    @Test
    void returnsBoundedPagesWithQualifiedReferences(@TempDir Path directory) throws Exception {
        pom(directory, "1.2.3");
        Path archive = directory.resolve("docs.zip");
        Files.write(archive, archive("sdk", "1.2.3"));
        try (var service = new AgentDocsService(() -> DevServerConfig.defaults(directory),
                store(directory.resolve("cache"), Map.of("sdk", archive), offline()))) {
            var search = json(service.search(Map.of("symbol", "@LocalOnly"), true));
            assertEquals("sdk", search.at("/results/0/namespace").asText());
            assertEquals("1.2.3", search.at("/results/0/version").asText());
            assertFalse(search.toString().contains("# LocalOnly"));
            StringBuilder content = new StringBuilder();
            int offset = 0;
            do {
                var page = json(service.read(Map.of("path", "/docs/local", "maxChars", 1001, "offset", offset), false));
                assertTrue(page.path("content").asText().length() <= 1001);
                content.append(page.path("content").asText());
                if (!page.path("hasMore").asBoolean()) { break; }
                offset = page.path("nextOffset").asInt();
            } while (true);
            assertEquals("# LocalOnly\nHandle locally 🦊.\n".repeat(1000), content.toString());
            var links = json(service.read(Map.of("path", "/docs"), true));
            assertEquals("1.2.3", links.at("/links/0/version").asText());
            assertEquals("/docs/local", links.at("/links/0/path").asText());
            assertThrows(IllegalArgumentException.class, () -> service.read(Map.of("path", "/docs", "offset", -1), false));
        }
    }

    @Test
    void doesNotTreatStaleClasspathAsProjectVersion(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("target/fluxzero-dev"));
        Files.writeString(directory.resolve("target/fluxzero-dev/runtime-classpath.txt"), "/old/io/fluxzero/sdk/1.2.3/sdk-1.2.3.jar");
        try (var service = new AgentDocsService(() -> DevServerConfig.defaults(directory),
                store(directory.resolve("cache"), Map.of(), offline()))) {
            assertThrows(IllegalStateException.class, () -> service.start(Map.of()));
        }
    }

    @Test
    void qualifiesIdenticalArticlePathsWithTheRequestedNamespace(@TempDir Path directory) throws Exception {
        Path sdk = directory.resolve("sdk.zip"), cli = directory.resolve("cli.zip");
        Files.write(sdk, archive("sdk", "1.2.3"));
        Files.write(cli, archive("cli", "1.2.3"));
        try (var service = new AgentDocsService(() -> DevServerConfig.defaults(directory),
                store(directory.resolve("cache"), Map.of("sdk", sdk, "cli", cli), offline()))) {
            for (String namespace : new String[]{"sdk", "cli"}) {
                var selectors = Map.<String, Object>of("namespace", namespace, "version", "1.2.3", "path", "/docs");
                var start = json(service.start(selectors));
                assertEquals(namespace, start.at("/root/namespace").asText());
                var page = json(service.read(selectors, false));
                assertEquals(namespace, page.path("namespace").asText());
                assertEquals(namespace, page.at("/links/0/namespace").asText());
            }
        }
    }

    @Test
    void explicitDocsRemainAvailableWithInvalidProjectConfiguration(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve(".fluxzero"));
        Files.writeString(directory.resolve(".fluxzero/dev.yaml"), "version: 99");
        Path archive = directory.resolve("docs.zip");
        Files.write(archive, archive("sdk", "1.2.3"));
        try (var service = new AgentDocsService(() -> DevServerConfig.fromArgs(
                new String[]{"--project-dir", directory.toString()}),
                store(directory.resolve("cache"), Map.of("sdk", archive), offline()))) {
            assertThrows(IllegalArgumentException.class, () -> service.start(Map.of()));
            assertEquals("ready", json(service.start(Map.of("version", "1.2.3"))).path("status").asText());
        }
    }

    static com.fasterxml.jackson.databind.JsonNode json(Object value) { return JSON.valueToTree(value); }
}
