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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Version-controlled functional history, independent of a running development session. */
final class ProjectProgress {
    static final int MAX_BYTES = 1_048_576;
    private static final Object WRITE_LOCK = new Object();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    record Change(String at, String status, String verification) {}
    record Feature(String id, String title, String kind, String status, String description,
                   List<String> acceptance, String createdAt, String updatedAt, List<Change> history) {}
    record Milestone(String id, String title, String description, List<Feature> features) {}
    record Document(int version, List<Milestone> milestones) {}
    record Snapshot(String revision, Document data, String error) {}
    private final Path directory;
    ProjectProgress(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    Snapshot view() {
        try { return read(); }
        catch (RuntimeException e) { return new Snapshot(null, null, "Progress could not be loaded. Ask your agent to check the project history."); }
    }
    Snapshot read() {
        try {
            Path file = path();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Snapshot("missing", new Document(1, List.of()), null);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
                throw new IllegalArgumentException("Progress must be a regular file of at most 1 MiB.");
            byte[] bytes;
            try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Progress exceeds 1 MiB.");
            Document data = YAML.readValue(bytes, Document.class);
            validate(data);
            return new Snapshot(hash(bytes), data, null);
        } catch (IOException e) { throw new IllegalArgumentException("Cannot read .fluxzero/progress.yaml; check its schema and YAML syntax."); }
    }
    Snapshot update(String operation, Map<String, Object> args) {
        String expected = text(args, "revision", true, 64);
        synchronized (WRITE_LOCK) {
            try {
                Path file = path();
                Path dev = directory.resolve(".fluxzero/dev");
                rejectSymlink(dev);
                Files.createDirectories(dev);
                Path lock = dev.resolve("progress.lock");
                rejectSymlink(lock);
                try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS); var ignored = channel.tryLock()) {
                    if (ignored == null) throw new IllegalStateException("Progress is being updated. Retry after reading get_progress.");
                    Snapshot current = read();
                    if (!expected.equals(current.revision())) throw new IllegalArgumentException("Progress changed. Call get_progress and reapply only your intended update using its revision.");
                    List<Milestone> milestones = new ArrayList<>(current.data().milestones());
                    if (operation.equals("milestone")) updateMilestone(milestones, args);
                    else if (operation.equals("feature")) updateFeature(milestones, args);
                    else throw new IllegalArgumentException("Unknown progress operation.");
                    Document next = new Document(1, milestones);
                    validate(next);
                    byte[] bytes = YAML.writeValueAsBytes(next);
                    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Progress exceeds 1 MiB.");
                    Path temporary = Files.createTempFile(file.getParent(), ".progress-", ".tmp");
                    try {
                        Files.write(temporary, bytes);
                        // Atomic rename keeps dashboard and other agents from seeing half-written YAML.
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                    return new Snapshot(hash(bytes), next, null);
                }
            } catch (IOException e) { throw new IllegalStateException("Could not safely save .fluxzero/progress.yaml."); }
        }
    }
    private static void updateMilestone(List<Milestone> milestones, Map<String, Object> args) {
        String id = id(args, "id");
        int index = -1;
        for (int i=0;i<milestones.size();i++) if (milestones.get(i).id().equals(id)) index=i;
        Milestone old = index < 0 ? null : milestones.get(index);
        var next = new Milestone(id, field(args,"title",old == null ? null : old.title(),true,240),
                field(args,"description",old == null ? "" : old.description(),false,4000),
                old == null ? List.of() : old.features());
        if (index < 0) milestones.add(next); else milestones.set(index,next);
    }
    private static void updateFeature(List<Milestone> milestones, Map<String,Object> args) {
        String milestoneId = id(args,"milestoneId"), id = id(args,"id");
        int index=-1;
        for (int i=0;i<milestones.size();i++) if (milestones.get(i).id().equals(milestoneId)) index=i;
        if(index<0) throw new IllegalArgumentException("Create the milestone first with upsert_progress_milestone.");
        Milestone milestone=milestones.get(index);
        List<Feature> features = new ArrayList<>(milestone.features());
        int featureIndex=-1;
        for(int i=0;i<features.size();i++) if(features.get(i).id().equals(id)) featureIndex=i;
        Feature old=featureIndex<0?null:features.get(featureIndex);
        String status=field(args,"status",old==null?"planned":old.status(),true,30);
        String verification=text(args,"verification",false,4000);
        List<Change> history=new ArrayList<>(old==null?List.of():old.history());
        String now=Instant.now().toString();
        boolean changedStatus=old==null || !old.status().equals(status);
        if(changedStatus && status.equals("done") && verification.isBlank())
            throw new IllegalArgumentException("Done requires a short verification describing the confirmed functional outcome.");
        if(changedStatus || !verification.isBlank()) history.add(new Change(now,status,verification));
        List<String> acceptance=old==null?List.of():old.acceptance();
        if(args.containsKey("acceptance")) {
            if(!(args.get("acceptance") instanceof List<?> values) || values.stream().anyMatch(v->!(v instanceof String)))
                throw new IllegalArgumentException("acceptance must be a list of functional criteria.");
            acceptance=values.stream().map(String.class::cast).toList();
        }
        var next=new Feature(id,field(args,"title",old==null?null:old.title(),true,240),
                field(args,"kind",old==null?"feature":old.kind(),true,20),status,
                field(args,"description",old==null?"":old.description(),false,4000),acceptance,
                old==null?now:old.createdAt(),now,history);
        if(featureIndex<0) features.add(next); else features.set(featureIndex,next);
        milestones.set(index,new Milestone(milestone.id(),milestone.title(),milestone.description(),features));
    }
    private Path path() throws IOException {
        Path flux=directory.resolve(".fluxzero"), file=flux.resolve("progress.yaml");
        rejectSymlink(flux); rejectSymlink(file); return file;
    }
    private static void rejectSymlink(Path path) throws IOException {
        if(Files.isSymbolicLink(path)) throw new IOException("Progress paths cannot be symbolic links.");
    }
    private static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch (java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static String field(Map<String,Object> args,String key,String old,boolean required,int max) {
        return args.containsKey(key)?text(args,key,required,max):checked(old,key,required,max);
    }
    private static String text(Map<String,Object> args,String key,boolean required,int max) {
        Object value=args.get(key);
        if(value!=null && !(value instanceof String)) throw new IllegalArgumentException(key+" must be text.");
        return checked((String)value,key,required,max);
    }
    private static String checked(String value,String key,boolean required,int max) {
        if(value==null) value="";
        if((required && value.isBlank()) || value.length()>max) throw new IllegalArgumentException("Invalid "+key+" (maximum "+max+" characters).");
        return value;
    }
    private static String id(Map<String,Object> args,String key) {return validId(text(args,key,true,80));}
    private static String validId(String id) {
        if(id==null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) throw new IllegalArgumentException("Use a stable id of letters, numbers, dots, underscores or hyphens.");
        return id;
    }
    private static void status(String status) {
        if(status==null || !Set.of("planned","in_progress","done").contains(status)) throw new IllegalArgumentException("Status must be planned, in_progress or done.");
    }
    private static void instant(String value) {
        try { Instant.parse(value); } catch (RuntimeException e) {throw new IllegalArgumentException("Progress timestamps must be ISO-8601 instants.");}
    }
    private static void validate(Document doc) {
        if(doc==null || doc.version()!=1 || doc.milestones()==null || doc.milestones().size()>100)
            throw new IllegalArgumentException("Unsupported progress schema. Use version 1 and at most 100 milestones.");
        Set<String> milestones=new HashSet<>(), features=new HashSet<>();
        for(var milestone:doc.milestones()) {
            if(milestone==null || !milestones.add(validId(milestone.id()))) throw new IllegalArgumentException("Milestone ids must be unique.");
            checked(milestone.title(),"title",true,240); checked(milestone.description(),"description",false,4000);
            if(milestone.features()==null) throw new IllegalArgumentException("Milestone features must be a list.");
            for(var feature:milestone.features()) {
                if(feature==null || !features.add(validId(feature.id())) || features.size()>500)
                    throw new IllegalArgumentException("Use at most 500 features with globally unique ids.");
                checked(feature.title(),"title",true,240); checked(feature.description(),"description",false,4000);
                status(feature.status());
                if(!Set.of("feature","bug").contains(feature.kind()==null?"":feature.kind())) throw new IllegalArgumentException("Kind must be feature or bug.");
                if(feature.acceptance()==null || feature.acceptance().size()>30) throw new IllegalArgumentException("Use at most 30 acceptance criteria.");
                feature.acceptance().forEach(c->checked(c,"acceptance",true,1000));
                if(feature.status().equals("done") && feature.acceptance().isEmpty())
                    throw new IllegalArgumentException("Done requires functional acceptance criteria.");
                instant(feature.createdAt()); instant(feature.updatedAt());
                if(feature.history()==null || feature.history().isEmpty() || feature.history().size()>1000) throw new IllegalArgumentException("Features require status history (at most 1000 entries).");
                for(var change:feature.history()) {
                    if(change==null) throw new IllegalArgumentException("Invalid history entry.");
                    instant(change.at()); status(change.status()); checked(change.verification(),"verification",change.status().equals("done"),4000);
                }
                if(!feature.history().getLast().status().equals(feature.status())) throw new IllegalArgumentException("Feature status must match its latest history entry.");
            }
        }
    }
}
