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
import java.util.List;
import java.util.LinkedHashMap;
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
        var duplicate = new ProjectProgress.Milestone("next", "Next", "", first.data().milestones().getFirst().features());
        var invalid = new ProjectProgress.Document(1, List.of(first.data().milestones().getFirst(), duplicate));
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        byte[] bytes = yaml.writeValueAsBytes(invalid);
        Files.write(directory.resolve(".fluxzero/progress.yaml"), bytes);
        assertThrows(IllegalArgumentException.class, () -> store().read());
        assertThrows(IllegalArgumentException.class, () -> store().update("delete_feature", Map.of("revision", second.revision(), "id", "cancel")));
        assertArrayEquals(bytes, Files.readAllBytes(directory.resolve(".fluxzero/progress.yaml")));
        Path external=directory.resolve("external.yaml");Files.writeString(external,"untouched");
        Files.delete(directory.resolve(".fluxzero/progress.yaml"));
        try {Files.createSymbolicLink(directory.resolve(".fluxzero/progress.yaml"),external);} catch(UnsupportedOperationException|java.nio.file.FileSystemException e){return;}
        assertNotNull(store().view().error());assertEquals("untouched",Files.readString(external));
    }
    ProjectProgress.Snapshot populated() {
        var active = feature(milestone().revision(), "in_progress");
        var done = store().update("feature", Map.of("revision", active.revision(), "milestoneId", "booking",
                "id", "cancel", "kind", "bug", "description", "Cancel a booking", "status", "done",
                "verification", "Cancellation prevents admission."));
        var destination = store().update("milestone", Map.of("revision", done.revision(), "id", "next", "title", "Next"));
        return store().update("feature", Map.of("revision", destination.revision(), "milestoneId", "next",
                "id", "existing", "title", "Existing destination feature"));
    }
    @Test void movesWithoutTitlePreserveAllFieldsAndHistoryAcrossSessions() {
        var before = populated();
        var original = before.data().milestones().getFirst().features().getFirst();
        var moved = store().update("feature", Map.of("revision", before.revision(), "milestoneId", "next", "id", "cancel"));
        assertTrue(moved.data().milestones().getFirst().features().isEmpty());
        assertEquals(before.data().milestones().getLast().features().getFirst(), moved.data().milestones().getLast().features().getFirst());
        var actual = moved.data().milestones().getLast().features().getLast();
        assertEquals(new ProjectProgress.Feature(original.id(), original.title(), original.kind(), original.status(),
                original.description(), original.acceptance(), original.createdAt(), actual.updatedAt(), original.history()), actual);
        assertFalse(java.time.Instant.parse(actual.updatedAt()).isBefore(java.time.Instant.parse(original.updatedAt())));
        assertEquals(moved, store().read());
        var returned = store().update("feature", Map.of("revision", moved.revision(), "milestoneId", "booking", "id", "cancel"));
        assertEquals(original.history(), returned.data().milestones().getFirst().features().getFirst().history());
        assertEquals(before.data().milestones().getLast(), returned.data().milestones().getLast());
    }
    @Test void combinesMoveWithEditsAndStatusHistory() {
        var before = populated();
        var original = before.data().milestones().getFirst().features().getFirst();
        var moved = store().update("feature", Map.of("revision", before.revision(), "milestoneId", "next", "id", "cancel",
                "title", "Cancel and refund", "status", "in_progress", "acceptance", List.of("Refund cancelled tickets.")));
        var actual = moved.data().milestones().getLast().features().getLast();
        assertEquals("Cancel and refund", actual.title());
        assertEquals("in_progress", actual.status());
        assertEquals(List.of("Refund cancelled tickets."), actual.acceptance());
        assertEquals(original.createdAt(), actual.createdAt());
        assertEquals(original.description(), actual.description());
        assertEquals(original.kind(), actual.kind());
        assertEquals(original.history(), actual.history().subList(0, original.history().size()));
        assertEquals(original.history().size() + 1, actual.history().size());
        assertEquals("in_progress", actual.history().getLast().status());
        assertTrue(moved.data().milestones().getFirst().features().isEmpty());
        assertEquals(moved, store().read());
    }
    void assertRejectedUnchanged(String operation, Map<String, Object> args) throws Exception {
        var file = directory.resolve(".fluxzero/progress.yaml");
        byte[] before = Files.readAllBytes(file);
        var snapshot = store().read();
        assertThrows(IllegalArgumentException.class, () -> store().update(operation, args));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(snapshot, store().read());
    }
    @Test void failedMovesLeaveSourceDestinationAndRevisionUnchanged() throws Exception {
        var before = populated();
        for (var edit : List.<Map<String, Object>>of(Map.of("milestoneId", "unknown"), Map.of("revision", "stale"),
                Map.of("title", ""), Map.of("status", "blocked"), Map.of("acceptance", List.of()))) {
            var args = new LinkedHashMap<String, Object>(Map.of("revision", before.revision(), "milestoneId", "next", "id", "cancel"));
            args.putAll(edit);
            assertRejectedUnchanged("feature", args);
        }
        // Entering Done while moving still requires evidence, even when acceptance criteria already exist.
        var reopened = store().update("feature", Map.of("revision", before.revision(), "milestoneId", "booking", "id", "cancel", "status", "in_progress"));
        assertRejectedUnchanged("feature", Map.of("revision", reopened.revision(), "milestoneId", "next", "id", "cancel", "status", "done"));
    }
    @Test void deletesFeaturesByGlobalIdAndThenEmptyMilestones() {
        var before = populated();
        var deleted = store().update("delete_feature", Map.of("revision", before.revision(), "id", "existing"));
        assertEquals(before.data().milestones().getFirst(), deleted.data().milestones().getFirst());
        assertTrue(deleted.data().milestones().getLast().features().isEmpty());
        var remaining = store().update("delete_milestone", Map.of("revision", deleted.revision(), "id", "next"));
        assertEquals(List.of(before.data().milestones().getFirst()), remaining.data().milestones());
        assertEquals(remaining, store().read());
    }
    @Test void protectsNonEmptyMilestonesUnlessContentDeletionIsExplicit() throws Exception {
        var before = populated();
        assertRejectedUnchanged("delete_milestone", Map.of("revision", before.revision(), "id", "booking"));
        for (Object flag : List.of(false, "true", 1)) {
            assertRejectedUnchanged("delete_milestone", Map.of("revision", before.revision(), "id", "booking", "deleteFeatures", flag));
        }
        var deleted = store().update("delete_milestone", Map.of("revision", before.revision(), "id", "booking", "deleteFeatures", true));
        assertEquals(List.of(before.data().milestones().getLast()), deleted.data().milestones());
        assertEquals(deleted, store().read());
    }
    @Test void staleAndUnknownDeletionsLeaveFileUnchanged() throws Exception {
        var before = populated();
        for (String operation : List.of("delete_feature", "delete_milestone")) {
            assertRejectedUnchanged(operation, Map.of("revision", before.revision(), "id", "unknown"));
            assertRejectedUnchanged(operation, Map.of("revision", "stale", "id", operation.equals("delete_feature") ? "cancel" : "booking", "deleteFeatures", true));
        }
    }
    McpSchema.CallToolResult call(String name, Map<String, Object> args) {
        return ProgressTools.tools(() -> directory, new ObjectMapper()).stream().filter(t -> t.tool().name().equals(name))
                .findFirst().orElseThrow().callHandler().apply(null, McpSchema.CallToolRequest.builder(name).arguments(args).build());
    }
    @Test void mcpRoutesMovesAndDeletesWithAccurateSchemasAndAnnotations() throws Exception {
        var before = populated();
        var tools = ProgressTools.tools(() -> directory, new ObjectMapper());
        assertEquals(5, tools.size());
        for (var tool : tools) {
            assertEquals(tool.tool().name().startsWith("delete_"), tool.tool().annotations().destructiveHint());
        }
        var delete = tools.stream().filter(t -> t.tool().name().equals("delete_progress_milestone")).findFirst().orElseThrow().tool();
        assertEquals(List.of("revision", "id"), delete.inputSchema().get("required"));
        assertEquals("boolean", ((Map<?, ?>) ((Map<?, ?>) delete.inputSchema().get("properties")).get("deleteFeatures")).get("type"));
        assertFalse(Boolean.TRUE.equals(call("upsert_progress_feature", Map.of("revision", before.revision(), "id", "cancel", "milestoneId", "next")).isError()));
        var moved = store().read();
        assertTrue(moved.data().milestones().getFirst().features().isEmpty());
        assertTrue(Boolean.TRUE.equals(call("delete_progress_feature", Map.of("revision", moved.revision(), "id", "cancel", "unexpected", true)).isError()));
        assertTrue(Boolean.TRUE.equals(call("delete_progress_milestone", Map.of("revision", moved.revision(), "id", "next")).isError()));
        assertEquals(moved, store().read());
        assertFalse(Boolean.TRUE.equals(call("delete_progress_feature", Map.of("revision", moved.revision(), "id", "cancel")).isError()));
        assertEquals(1, store().read().data().milestones().getLast().features().size());
        assertFalse(Boolean.TRUE.equals(call("delete_progress_milestone", Map.of("revision", store().read().revision(), "id", "next", "deleteFeatures", true)).isError()));
        assertEquals(List.of(before.data().milestones().getFirst().id()), store().read().data().milestones().stream().map(ProjectProgress.Milestone::id).toList());
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
