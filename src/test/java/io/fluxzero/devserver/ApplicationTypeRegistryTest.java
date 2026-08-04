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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationTypeRegistryTest {

    @Test
    void readsAndResolvesGeneratedApplicationTypes(@TempDir Path tempDirectory) throws Exception {
        Path classes = tempDirectory.resolve("classes");
        Path registry = classes.resolve("META-INF/io.fluxzero.common.serialization.TypeRegistry");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, "com.example.account.CreateAccount\ncom.example.product.CreateProduct\n");
        ApplicationBuild application = new ApplicationBuild(
                "app", ".", "com.example.Application", List.of(classes), List.of());

        ApplicationTypeRegistry typeRegistry = new ApplicationTypeRegistry();
        typeRegistry.update("app", ApplicationTypeRegistry.read(List.of(application)));

        assertEquals("com.example.account.CreateAccount", typeRegistry.resolve("CreateAccount"));
        assertEquals("com.example.account.CreateAccount", typeRegistry.resolve("account.CreateAccount"));
        assertEquals("custom-wire-type", typeRegistry.resolve("custom-wire-type"));
    }

    @Test
    void keepsRegistrationsFromOtherBuildProjects() {
        ApplicationTypeRegistry typeRegistry = new ApplicationTypeRegistry();

        typeRegistry.update("dashboard", Set.of("com.example.dashboard.CreateAccount"));
        typeRegistry.update("auditlog", Set.of("com.example.auditlog.CreateAuditEntry"));

        assertEquals("com.example.dashboard.CreateAccount", typeRegistry.resolve("CreateAccount"));
        assertEquals("com.example.auditlog.CreateAuditEntry", typeRegistry.resolve("CreateAuditEntry"));
    }
}
