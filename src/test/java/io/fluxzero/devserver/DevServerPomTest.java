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
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerPomTest {

    @Test
    void standalonePomOwnsReleaseCoordinatesAndFluxzeroDependencyVersion() throws Exception {
        Document pom = readPom(Path.of("pom.xml"));

        assertEquals(0, pom.getElementsByTagName("parent").getLength());
        assertEquals("io.fluxzero.tools", element(pom, "groupId"));
        assertEquals("fluxzero-dev-server", element(pom, "artifactId"));
        assertTrue(element(pom, "version").matches("(?:[1-9]\\d*-SNAPSHOT|[1-9]\\d*\\.\\d+\\.\\d+)"));
        assertTrue(property(pom, "fluxzero.version").matches("[1-9]\\d*\\.\\d+\\.\\d+"));
        assertEquals("true", element(pom, "shadedArtifactAttached"));
        assertEquals("standalone", element(pom, "shadedClassifierName"));
    }

    private static Document readPom(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static String property(Document pom, String name) {
        return pom.getElementsByTagName(name).item(0).getTextContent().strip();
    }

    private static String element(Document pom, String name) {
        return pom.getElementsByTagName(name).item(0).getTextContent().strip();
    }
}
