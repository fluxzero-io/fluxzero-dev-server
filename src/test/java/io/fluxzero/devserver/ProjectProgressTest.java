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
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class ProjectProgressTest {
    @TempDir Path directory;
    ProjectProgress store() {return new ProjectProgress(directory);}
    ProjectProgress.Snapshot milestone() {return store().update("milestone",Map.of("revision",store().read().revision(),"id","booking","title","Booking tickets"));}
    ProjectProgress.Snapshot feature(String revision,String status) {return store().update("feature",Map.of("revision",revision,"milestoneId","booking","id","cancel","title","Visitors can cancel tickets","status",status,"acceptance",java.util.List.of("A cancelled ticket can no longer be used.")));}

    @Test void missingIsReadOnlyAndTransitionsPreserveHistoryAcrossSessions() throws Exception {
        assertEquals("missing",store().read().revision());
        assertFalse(Files.exists(directory.resolve(".fluxzero")));
        var planned=feature(milestone().revision(),"planned");
        var active=feature(planned.revision(),"in_progress");
        assertThrows(IllegalArgumentException.class,()->feature(active.revision(),"done"));
        assertEquals(active.revision(),store().read().revision());
        var done=store().update("feature",Map.of("revision",active.revision(),"milestoneId","booking","id","cancel","status","done","verification","Confirmed that cancellation prevents admission."));
        var f=done.data().milestones().getFirst().features().getFirst();
        assertEquals(3,f.history().size()); assertEquals("Visitors can cancel tickets",f.title());
        assertEquals(planned.data().milestones().getFirst().features().getFirst().createdAt(),f.createdAt());
        assertEquals(done,new ProjectProgress(directory).read());
        assertTrue(Files.readString(directory.resolve(".fluxzero/progress.yaml")).contains("Confirmed that cancellation"));
        var reopened=store().update("feature",Map.of("revision",done.revision(),"milestoneId","booking","id","cancel","status","in_progress"));
        assertEquals(4,reopened.data().milestones().getFirst().features().getFirst().history().size());
    }
    @Test void staleUpdatesDoNotLoseAnotherAgentsChanges() {
        var initial=milestone();
        var updated=feature(initial.revision(),"in_progress");
        assertThrows(IllegalArgumentException.class,()->store().update("milestone",Map.of("revision",initial.revision(),"id","booking","title","New title")));
        assertEquals(updated,store().read());
        var renamed=store().update("milestone",Map.of("revision",updated.revision(),"id","booking","title","Book and cancel"));
        assertEquals(updated.data().milestones().getFirst().features(),renamed.data().milestones().getFirst().features());
    }
    @Test void concurrentWritersHaveOnlyOneWinner() throws Exception {
        var current=milestone(); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> update=()-> {start.await();try {feature(current.revision(),"planned");return true;}catch(IllegalArgumentException e){return false;}};
            var a=executor.submit(update);var b=executor.submit(update);start.countDown();
            assertNotEquals(a.get(),b.get()); assertEquals(1,store().read().data().milestones().getFirst().features().size());
        }
    }
    @Test void rejectsUnsupportedOrMalformedFilesWithoutOverwriting() throws Exception {
        Files.createDirectories(directory.resolve(".fluxzero")); var file=directory.resolve(".fluxzero/progress.yaml");
        for(String yaml:java.util.List.of("version: 2\nmilestones: []\n","version: 1\nversion: 1\nmilestones: []\n","version: 1\nmilestones: []\nunknown: retained\n","broken: [")) {
            Files.writeString(file,yaml);assertNotNull(store().view().error());
            assertThrows(RuntimeException.class,()->store().update("milestone",Map.of("revision","missing","id","new","title","New")));
            assertEquals(yaml,Files.readString(file));
        }
        Files.write(file,new byte[ProjectProgress.MAX_BYTES+1]);assertNotNull(store().view().error());
    }
    @Test void rejectsInvalidStatusesDuplicateFeatureIdsAndSymlinks() throws Exception {
        var initial=milestone();assertThrows(IllegalArgumentException.class,()->feature(initial.revision(),"blocked"));
        var first=feature(initial.revision(),"planned");
        var second=store().update("milestone",Map.of("revision",first.revision(),"id","next","title","Next"));
        assertThrows(IllegalArgumentException.class,()->store().update("feature",Map.of("revision",second.revision(),"milestoneId","next","id","cancel","title","Duplicate")));
        assertEquals(second,store().read());
        Path external=directory.resolve("external.yaml");Files.writeString(external,"untouched");
        Files.delete(directory.resolve(".fluxzero/progress.yaml"));
        try {Files.createSymbolicLink(directory.resolve(".fluxzero/progress.yaml"),external);} catch(UnsupportedOperationException|java.nio.file.FileSystemException e){return;}
        assertNotNull(store().view().error());assertEquals("untouched",Files.readString(external));
    }
    @Test void mcpToolsUseSelectedDirectoryAndAdvertiseMutations() {
        var selected=new java.util.concurrent.atomic.AtomicReference<>(directory);
        var tools=ProgressTools.tools(selected::get,new ObjectMapper());
        var read=tools.getFirst();var write=tools.get(1);
        assertTrue(read.tool().annotations().readOnlyHint());assertFalse(write.tool().annotations().readOnlyHint());
        var result=write.callHandler().apply(null,McpSchema.CallToolRequest.builder("upsert_progress_milestone").arguments(Map.of("revision","missing","id","m","title","A product milestone")).build());
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(1,store().read().data().milestones().size());
        Path other=directory.resolve("other");try{Files.createDirectory(other);}catch(Exception e){throw new RuntimeException(e);}
        selected.set(other);
        var otherResult=read.callHandler().apply(null,McpSchema.CallToolRequest.builder("get_progress").arguments(Map.of()).build());
        assertTrue(otherResult.content().toString().contains("missing"));
        assertFalse(Files.exists(other.resolve(".fluxzero")));
    }
}
