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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DevConsoleProjectsTest {
    @TempDir Path root;
    @Test void existingProjectsAreRememberedWithoutChangingFiles() throws Exception {
        var registry=new DevEnvironmentRegistry(root.resolve("registry"));
        Path app=Files.createDirectory(root.resolve("app"));
        Files.writeString(app.resolve("pom.xml"),"original");
        try(var projects=new DevConsoleProjects(registry)) {
            var entry=projects.open(app.toString());
            assertEquals("stopped",entry.status());
            registry.forget(entry.id());
            assertTrue(registry.listKnown().isEmpty());
            projects.open(app.toString());
            assertEquals(1,registry.listKnown().size());
            assertEquals("original",Files.readString(app.resolve("pom.xml")));
            assertThrows(IllegalArgumentException.class,()->projects.open(root.toString()));
        }
    }
    @Test void creationRejectsTraversalAndExistingFoldersAndKeepsPartialFiles() throws Exception {
        var calls=new AtomicInteger();
        var registry=new DevEnvironmentRegistry(root.resolve("registry"));
        try(var projects=new DevConsoleProjects(registry,(p,n)->{calls.incrementAndGet();Files.writeString(p.resolve("pom.xml"),"generated");})) {
            assertThrows(CompletionException.class,()->projects.create(root.toString(),"../outside").join());
            Path existing=Files.createDirectory(root.resolve("existing"));
            assertThrows(CompletionException.class,()->projects.create(root.toString(),"existing").join());
            assertEquals(0,calls.get());assertTrue(Files.isDirectory(existing));
            assertEquals("new-app",projects.create(root.toString(),"new-app").join().projectName());
            assertEquals(1,calls.get());
        }
        try(var projects=new DevConsoleProjects(registry,(p,n)->{Files.writeString(p.resolve("partial"),"keep");throw new java.io.IOException();})) {
            assertThrows(CompletionException.class,()->projects.create(root.toString(),"partial-app").join());
            assertEquals("keep",Files.readString(root.resolve("partial-app/partial")));
            assertEquals(1,registry.listKnown().size());
        }
    }
    @Test void foldersAreBoundedAndDoNotExposeFileContents() throws Exception {
        Files.writeString(root.resolve("secret.txt"),"secret");
        Files.createDirectory(root.resolve(".hidden"));
        for(int i=0;i<205;i++)Files.createDirectory(root.resolve("folder"+i));
        try(var projects=new DevConsoleProjects(new DevEnvironmentRegistry(root.resolve("registry")))) {
            var result=projects.folders(root.toString());
            assertEquals(true,result.get("truncated"));
            assertEquals(200,((java.util.List<?>)result.get("folders")).size());
            assertFalse(result.toString().contains("secret"));assertFalse(result.toString().contains(".hidden"));
            assertThrows(IllegalArgumentException.class,()->projects.folders("relative"));
        }
    }
}
