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
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Resolves latest to an immutable release identity; retains bounded metadata for offline bootstrap. */
final class AgentDocsReleases {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TTL = Duration.ofHours(1);
    private static final ReentrantLock[] LOCKS = new ReentrantLock[32];
    static { Arrays.setAll(LOCKS, ignored -> new ReentrantLock()); }
    private AgentDocsReleases() {}

    static Release resolve(Path directory, URI metadata, AgentDocsStore.Fetcher fetcher) {
        ReentrantLock lock = LOCKS[Math.floorMod(directory.hashCode(), LOCKS.length)];
        boolean acquired = false;
        try {
            acquired = lock.tryLock(15, TimeUnit.SECONDS);
            if (!acquired) throw new IOException("Timed out waiting for release metadata");
            Files.createDirectories(directory);
            try (var channel = FileChannel.open(directory.resolve("latest.lock"), StandardOpenOption.CREATE,
                                               StandardOpenOption.WRITE);
                 var ignored = AgentDocsStore.lock(channel)) {
                Path file = directory.resolve("latest.json");
                Release cached = cached(file, metadata);
                if (cached != null && cached.checkedAt().plus(TTL).isAfter(Instant.now())) return cached;
                String version;
                try {
                    version = newest(fetcher.read(metadata, 256 * 1024));
                } catch (IOException e) {
                    if (cached != null) return new Release(cached.version(), "offline", cached.checkedAt());
                    throw e;
                }
                Instant checkedAt = Instant.now();
                AgentDocsStore.atomicWrite(file, JSON.writeValueAsBytes(Map.of("version", version,
                        "checkedAt", checkedAt.toString(), "metadata", metadata.toString())));
                return new Release(version, "repository", checkedAt);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Documentation release lookup interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot determine the latest documentation release. "
                    + "Connect to Fluxzero Packages or select a concrete cached version.", e);
        } finally {
            if (acquired) lock.unlock();
        }
    }

    private static Release cached(Path file, URI metadata) {
        try {
            var json = JSON.readTree(AgentDocsStore.readLimited(file, 4096));
            String version = json.path("version").asText();
            Instant checked = Instant.parse(json.path("checkedAt").asText());
            if (!stable(version) || !json.path("metadata").asText().equals(metadata.toString())
                || checked.isAfter(Instant.now())) return null;
            return new Release(version, "cache", checked);
        } catch (Exception e) { return null; }
    }

    static String newest(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document = builder.parse(new ByteArrayInputStream(bytes));
            String latest = null;
            for (String tag : new String[]{"version", "release"}) {
                NodeList versions = document.getElementsByTagName(tag);
                for (int i = 0; i < versions.getLength(); i++) {
                    String candidate = versions.item(i).getTextContent().strip();
                    if (stable(candidate) && (latest == null || compare(candidate, latest) > 0)) latest = candidate;
                }
            }
            if (latest == null) throw new IOException("No stable release in documentation metadata");
            return latest;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid documentation release metadata", e); }
    }

    private static boolean stable(String version) { return version.length() <= 128 && version.matches("[0-9]+(\\.[0-9]+){1,3}"); }

    private static int compare(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int comparison = new BigInteger(i < a.length ? a[i] : "0").compareTo(new BigInteger(i < b.length ? b[i] : "0"));
            if (comparison != 0) return comparison;
        }
        return left.compareTo(right);
    }

    record Release(String version, String source, Instant checkedAt) {}
}
