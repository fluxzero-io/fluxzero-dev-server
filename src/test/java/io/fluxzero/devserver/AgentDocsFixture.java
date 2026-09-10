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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class AgentDocsFixture {
    static final String COMMIT = "abcdef0123".repeat(4);
    static final AgentDocsGraph.Identity SDK = new AgentDocsGraph.Identity("sdk", "1.2.3");
    static final ObjectMapper JSON = new ObjectMapper();

    static byte[] archive(String namespace, String version) throws Exception {
        return zip(files(namespace, version));
    }

    static Map<String, byte[]> files(String namespace, String version) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("articles/root.md", "# Start\nUse selective documentation retrieval.\n".getBytes(StandardCharsets.UTF_8));
        files.put("articles/local.md", "# LocalOnly\nHandle locally 🦊.\n".repeat(1000).getBytes(StandardCharsets.UTF_8));
        files.put("manifest.json", JSON.writeValueAsBytes(Map.of(
                "schemaVersion", 1, "namespace", namespace, "root", "/docs", "articles", List.of(
                        Map.of("path", "/docs", "source", "articles/root.md", "title", "Start", "summary", "Getting started",
                               "symbols", List.of(), "links", List.of(Map.of("path", "/docs/local", "description", "Local handling"))),
                        Map.of("path", "/docs/local", "source", "articles/local.md", "title", "LocalOnly",
                               "summary", "Handle messages locally", "symbols", List.of("@LocalOnly"), "links", List.of())))));
        StringBuilder hashes = new StringBuilder();
        for (var file : new TreeMap<>(files).entrySet()) {
            hashes.append(file.getKey()).append('\0').append(hash(file.getValue())).append('\n');
        }
        files.put("release.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "namespace", namespace,
                "componentVersion", version, "sourceCommit", COMMIT,
                "contentHash", hash(hashes.toString().getBytes(StandardCharsets.UTF_8)))));
        return files;
    }

    static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void pom(Path directory, String version) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>
                  <artifactId>app</artifactId><version>1</version>
                  <properties><fluxzero.version>%s</fluxzero.version></properties>
                  <dependencies><dependency><groupId>io.fluxzero</groupId><artifactId>sdk</artifactId>
                    <version>${fluxzero.version}</version></dependency></dependencies>
                </project>
                """.formatted(version));
    }
}
