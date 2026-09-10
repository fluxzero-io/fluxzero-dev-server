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

import java.util.Map;

import static io.fluxzero.devserver.AgentDocsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDocsGraphTest {
    @Test
    void readsProducerFormatAndRanksTitlesAndExactSymbols() throws Exception {
        AgentDocsGraph graph = AgentDocsGraph.read(archive("sdk", "1.2.3"), SDK);
        assertEquals(2, graph.size());
        assertEquals(COMMIT, graph.sourceCommit());
        assertEquals("/docs/local", graph.search("local", 1).getFirst().path());
        assertEquals("/docs/local", graph.lookup("@LocalOnly", 1).getFirst().path());
        assertTrue(graph.lookup("Local", 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> graph.article("/docs/missing"));
    }

    @Test
    void rejectsWrongIdentityAndContentHash() throws Exception {
        byte[] archive = archive("sdk", "1.2.3");
        assertThrows(IllegalArgumentException.class, () -> AgentDocsGraph.read(archive,
                new AgentDocsGraph.Identity("cli", "1.2.3")));
        assertThrows(IllegalArgumentException.class, () -> AgentDocsGraph.read(archive,
                new AgentDocsGraph.Identity("sdk", "1.2.4")));
        Map<String, byte[]> files = files("sdk", "1.2.3");
        files.put("articles/root.md", "Changed content".getBytes());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentDocsGraph.read(zip(files), SDK))
                           .getMessage().contains("contentHash"));
    }

    @Test
    void rejectsTraversalUnknownFilesAndOversizedExpansion() throws Exception {
        for (String name : new String[]{"../escape.md", "/absolute.md", "articles\\escape.md", "unexpected.md"}) {
            var files = files("sdk", "1.2.3");
            files.put(name, "bad".getBytes());
            assertThrows(IllegalArgumentException.class, () -> AgentDocsGraph.read(zip(files), SDK), name);
        }
        var files = files("sdk", "1.2.3");
        files.put("articles/root.md", new byte[2 * 1024 * 1024 + 1]);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentDocsGraph.read(zip(files), SDK))
                           .getMessage().contains("size limit"));
    }

    @Test
    void rejectsInvalidGraphSchemaAndEncoding() throws Exception {
        for (String mutation : new String[]{"schema", "unreachable", "missing", "duplicate", "utf8"}) {
            var files = files("sdk", "1.2.3");
            var manifest = JSON.readTree(files.get("manifest.json"));
            switch (mutation) {
                case "schema" -> ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("schemaVersion", 4294967297L);
                case "unreachable" -> ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.at("/articles/0/links")).removeAll();
                case "missing" -> ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.at("/articles/0/links/0")).put("path", "/docs/missing");
                case "duplicate" -> ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.at("/articles/1")).put("path", "/docs");
                case "utf8" -> files.put("articles/root.md", new byte[]{(byte) 0xc3, (byte) 0x28});
            }
            files.put("manifest.json", JSON.writeValueAsBytes(manifest));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AgentDocsGraph.read(zip(files), SDK), mutation);
            assertFalse(error.getMessage().contains("contentHash"), "should reject the structural defect first");
        }
    }
}
