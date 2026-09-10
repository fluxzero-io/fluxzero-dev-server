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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** Immutable documentation data, independent of MCP, project detection and download transport. */
final class AgentDocsGraph {
    static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONTENT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final Pattern TOKENS = Pattern.compile("[^a-z0-9_@.<>/-]+");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Identity identity;
    private final String sourceCommit;
    private final String contentHash;
    private final String root;
    private final Map<String, Article> articles;

    private AgentDocsGraph(Identity identity, String sourceCommit, String contentHash, String root,
                           Map<String, Article> articles) {
        this.identity = identity;
        this.sourceCommit = sourceCommit;
        this.contentHash = contentHash;
        this.root = root;
        this.articles = Map.copyOf(articles);
    }

    static AgentDocsGraph read(byte[] archive, Identity expected) {
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Documentation archive exceeds the size limit");
        }
        try {
            Map<String, byte[]> files = archiveEntries(archive);
            JsonNode manifest = JSON.readTree(requiredFile(files, "manifest.json"));
            JsonNode release = JSON.readTree(requiredFile(files, "release.json"));
            requireSchema(manifest);
            requireSchema(release);
            Identity actual = new Identity(text(release, "namespace"), text(release, "componentVersion"));
            if (!expected.equals(actual) || !expected.namespace().equals(text(manifest, "namespace"))) {
                throw new IllegalArgumentException("Documentation namespace/version does not match " + expected);
            }
            String commit = text(release, "sourceCommit");
            if (!commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
                throw new IllegalArgumentException("Documentation sourceCommit is not a full Git hash");
            }
            String root = path(text(manifest, "root"));
            Map<String, Article> articles = new LinkedHashMap<>();
            Set<String> sources = new HashSet<>(Set.of("manifest.json", "release.json"));
            for (JsonNode definition : array(manifest, "articles")) {
                String path = path(text(definition, "path"));
                String source = text(definition, "source");
                if (!source.startsWith("articles/") || !source.endsWith(".md") || !safeSource(source)
                    || !sources.add(source)) {
                    throw new IllegalArgumentException("Invalid or duplicate documentation source: " + source);
                }
                String content = decode(requiredFile(files, source));
                if (content.isBlank()) {
                    throw new IllegalArgumentException("Empty documentation article: " + path);
                }
                List<String> symbols = new ArrayList<>();
                for (JsonNode symbol : array(definition, "symbols")) {
                    if (!symbol.isTextual() || symbol.textValue().isBlank() || symbols.contains(symbol.textValue())) {
                        throw new IllegalArgumentException("Invalid or duplicate documentation symbol for " + path);
                    }
                    symbols.add(symbol.textValue());
                }
                List<Link> links = new ArrayList<>();
                Set<String> targets = new HashSet<>();
                for (JsonNode link : array(definition, "links")) {
                    String target = path(text(link, "path"));
                    if (!targets.add(target)) {
                        throw new IllegalArgumentException("Duplicate documentation link: " + target);
                    }
                    links.add(new Link(target, text(link, "description")));
                }
                Article article = new Article(path, text(definition, "title"), text(definition, "summary"),
                                              content, List.copyOf(symbols), List.copyOf(links));
                if (articles.put(path, article) != null) {
                    throw new IllegalArgumentException("Duplicate documentation article: " + path);
                }
            }
            if (!sources.equals(files.keySet())) {
                throw new IllegalArgumentException("Documentation archive contains unregistered or missing files");
            }
            for (Article article : articles.values()) {
                for (Link link : article.links()) {
                    if (!articles.containsKey(link.path())) {
                        throw new IllegalArgumentException("Missing documentation link target: " + link.path());
                    }
                }
            }
            if (!articles.containsKey(root)) {
                throw new IllegalArgumentException("Documentation root is missing");
            }
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> pending = new ArrayDeque<>(List.of(root));
            while (!pending.isEmpty()) {
                String path = pending.removeFirst();
                if (reachable.add(path)) {
                    articles.get(path).links().forEach(link -> pending.add(link.path()));
                }
            }
            if (reachable.size() != articles.size()) {
                throw new IllegalArgumentException("Documentation contains unreachable articles");
            }
            StringBuilder digestInput = new StringBuilder();
            new TreeMap<>(files).forEach((name, bytes) -> {
                if (!name.equals("release.json")) {
                    digestInput.append(name).append('\0').append(sha256(bytes)).append('\n');
                }
            });
            String hash = sha256(digestInput.toString().getBytes(StandardCharsets.UTF_8));
            if (!hash.equals(text(release, "contentHash"))) {
                throw new IllegalArgumentException("Documentation contentHash does not match its contents");
            }
            return new AgentDocsGraph(actual, commit, hash, root, articles);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid documentation ZIP or JSON", e);
        }
    }

    private static Map<String, byte[]> archiveEntries(byte[] archive) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory() || !safeSource(entry.getName()) || files.size() >= MAX_ENTRIES) {
                    throw new IllegalArgumentException("Unsafe or excessive documentation ZIP entries");
                }
                byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                total += bytes.length;
                if (bytes.length > MAX_ENTRY_BYTES || total > MAX_CONTENT_BYTES) {
                    throw new IllegalArgumentException("Expanded documentation exceeds the size limit");
                }
                if (files.put(entry.getName(), bytes) != null) {
                    throw new IllegalArgumentException("Duplicate documentation ZIP entry");
                }
            }
        }
        return files;
    }

    private static boolean safeSource(String name) {
        return !name.startsWith("/") && !name.contains("\\") && !name.contains(":")
               && List.of(name.split("/", -1)).stream().noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }

    private static byte[] requiredFile(Map<String, byte[]> files, String name) {
        byte[] result = files.get(name);
        if (result == null) {
            throw new IllegalArgumentException("Missing documentation file: " + name);
        }
        return result;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void requireSchema(JsonNode node) {
        if (node == null || !node.path("schemaVersion").isIntegralNumber()
            || !node.path("schemaVersion").canConvertToInt() || node.path("schemaVersion").intValue() != 1) {
            throw new IllegalArgumentException("Unsupported documentation schemaVersion");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing documentation " + field);
        }
        return value.textValue();
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw new IllegalArgumentException("Documentation " + field + " must be an array");
        }
        return value;
    }

    static String path(String path) {
        if (path == null || !path.matches("/docs(?:/[a-z0-9-]+)*")) {
            throw new IllegalArgumentException("Use a logical documentation path under /docs, without a URL or query");
        }
        return path;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Identity identity() { return identity; }
    String sourceCommit() { return sourceCommit; }
    String contentHash() { return contentHash; }
    String root() { return root; }
    int size() { return articles.size(); }

    Article article(String path) {
        Article result = articles.get(path(path));
        if (result == null) {
            throw new IllegalArgumentException("Unknown documentation article: " + path);
        }
        return result;
    }

    List<Article> search(String query, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT).strip();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> tokens = TOKENS.splitAsStream(normalized).filter(t -> !t.isBlank()).toList();
        return articles.values().stream().map(a -> new Scored(a, score(a, normalized, tokens)))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                                .thenComparing(s -> s.article().title()).thenComparing(s -> s.article().path()))
                .limit(limit).map(Scored::article).toList();
    }

    List<Article> lookup(String symbol, int limit) {
        return articles.values().stream().filter(a -> a.symbols().contains(symbol))
                .sorted(Comparator.comparing(Article::path)).limit(limit).toList();
    }

    private static int score(Article article, String query, List<String> tokens) {
        String title = article.title().toLowerCase(Locale.ROOT);
        String summary = article.summary().toLowerCase(Locale.ROOT);
        String symbols = String.join(" ", article.symbols()).toLowerCase(Locale.ROOT);
        String content = article.content().toLowerCase(Locale.ROOT);
        int score = (article.path().contains(query) ? 10 : 0) + (title.contains(query) ? 9 : 0)
                    + (symbols.contains(query) ? 8 : 0);
        for (String token : tokens) {
            score += (title.contains(token) ? 5 : 0) + (symbols.contains(token) ? 4 : 0)
                     + (summary.contains(token) ? 3 : 0) + (content.contains(token) ? 1 : 0);
        }
        return score;
    }

    record Identity(String namespace, String version) {
        Identity {
            if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("Invalid documentation namespace");
            }
            if (version == null || !version.matches("[0-9][0-9A-Za-z.+_-]{0,127}")) {
                throw new IllegalArgumentException("Use a concrete documentation component version");
            }
        }
    }
    record Article(String path, String title, String summary, String content, List<String> symbols, List<Link> links) {}
    record Link(String path, String description) {}
    private record Scored(Article article, int score) {}
}
