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

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Run-scoped, authenticated loopback telemetry from test runners. Never part of the public gateway. */
final class TestTelemetry implements AutoCloseable {
    static final int MAX_TESTS = 100_000;
    private final String runId = UUID.randomUUID().toString();
    private final String token = UUID.randomUUID().toString();
    private final Runnable changed;
    private final TestInventory inventory;
    private final Map<String,String> scopes = new HashMap<>();
    private final Map<String,Set<String>> inventoryTests = new HashMap<>(), inventoryTemplates = new HashMap<>();
    private final Map<String,Set<String>> observed = new HashMap<>();
    synchronized Map<String,Set<String>> observedTests() {
        Map<String,Set<String>> result = new HashMap<>();
        observed.forEach((scope, ids) -> result.put(scope, Set.copyOf(ids)));
        return result;
    }
    private final Map<String,String> tests = new HashMap<>();
    private final Map<String,Boolean> streams = new HashMap<>();
    private final Set<String> ended = new HashSet<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private volatile boolean closed;
    private boolean junitOnly = true;
    private boolean lost;
    private int passed, failed, skipped, started, containerFailures;

    TestTelemetry(Runnable changed) {this(changed, null);}
    TestTelemetry(Runnable changed, TestInventory inventory) {this.changed = changed; this.inventory = inventory;}

    void configure(List<String> command, Map<String,String> environment, BuildTool tool, Path directory) {
        try {
            Path jar = listenerJar(directory);
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1",0));
            environment.put("FLUXZERO_DEV_TEST_PORT",String.valueOf(server.getLocalPort()));
            environment.put("FLUXZERO_DEV_TEST_TOKEN",token);
            environment.put("FLUXZERO_DEV_TEST_JAR",jar.toString());
            if (tool == BuildTool.MAVEN) command.add("-Dmaven.test.additionalClasspath=" + jar);
            else {
                Path script=directory.resolve("test-events.gradle");
                try(var input=TestTelemetry.class.getResourceAsStream("/test-events.gradle")) {
                    if(input==null)throw new java.io.IOException("Missing Gradle reporting script");
                    Files.write(script,input.readAllBytes());
                }
                command.add("--init-script");command.add(script.toString());
                // These per-run callback closures are incompatible with Gradle configuration caching.
                command.add("--no-configuration-cache");
            }
            Thread.ofVirtual().name("test-event-accept").start(this::accept);
        } catch(Exception ignored) {lost=true;close();}
    }

    private void accept() {
        while(!closed) {
            try {
                Socket socket=server.accept();
                if(sockets.size()>=64){socket.close();continue;}
                sockets.add(socket);
                Thread.ofVirtual().name("test-event-reader").start(()->read(socket));
            } catch(Exception ignored) {if(!closed)markLost();return;}
        }
    }

    private void read(Socket socket) {
        String stream=null;
        try(socket;var input=new DataInputStream(socket.getInputStream())) {
            socket.setSoTimeout(1000);
            if(!"fz-test-v1".equals(input.readUTF()) || !token.equals(input.readUTF())) return;
            stream=input.readUTF();
            String source=input.readUTF();String module=input.readUTF();
            if(!stream.matches("[a-f0-9-]{36}") || !Set.of("junit","gradle").contains(source))return;
            synchronized(this) {
                if(streams.size()>=256 || streams.containsKey(stream)){lost=true;return;}
                streams.put(stream,false);junitOnly &= source.equals("junit");
                scopes.put(stream, source + ":" + digest(module.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            socket.setSoTimeout(0);
            while(!closed) {
                String kind=input.readUTF(), id=input.readUTF();
                if(id.length()>16000){markLost();break;}
                record(stream,kind,id);
                if(kind.equals("end"))break;
            }
        } catch(Exception ignored) {
            synchronized(this) {if(stream!=null && !ended.contains(stream) && !closed)lost=true;}
        } finally {sockets.remove(socket);changed.run();}
    }

    synchronized void record(String stream,String kind,String id) {
        if(closed)return;
        String scope = scopes.getOrDefault(stream, stream);
        switch(kind) {
            case "inventory-test", "inventory-template" -> {
                var target = kind.equals("inventory-test") ? inventoryTests : inventoryTemplates;
                var ids = target.computeIfAbsent(stream, ignored -> new HashSet<>());
                if (ids.size() >= MAX_TESTS) {lost=true;return;}
                ids.add(id);
            }
            case "inventory-complete" -> {
                if (inventory != null) inventory.discover(scope, inventoryTests.getOrDefault(stream, Set.of()),
                        inventoryTemplates.getOrDefault(stream, Set.of()));
                inventoryTests.remove(stream); inventoryTemplates.remove(stream);
            }
            case "name" -> {
                int separator = id.indexOf('\0');
                if (inventory != null && separator > 0) inventory.name(scope, id.substring(0, separator), id.substring(separator + 1));
            }
            case "plan" -> streams.putIfAbsent(stream,false);
            case "discovered" -> streams.put(stream,true);
            case "end" -> ended.add(stream);
            case "container-failed" -> containerFailures++;
            case "registered", "started", "passed", "failed", "skipped" -> {
                String key=stream+":"+digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String previous=tests.get(key);
                if(previous==null && tests.size()>=MAX_TESTS){lost=true;return;}
                if(Set.of("passed","failed","skipped").contains(previous==null?"":previous))return;
                if(previous==null || !kind.equals("registered"))tests.put(key,kind);
                observed.computeIfAbsent(scope, ignored -> new HashSet<>()).add(id);
                if (inventory != null) inventory.event(scope, kind, id);
                if(kind.equals("started") && !kind.equals(previous))started++;
                if(kind.equals("passed"))passed++;
                if(kind.equals("failed"))failed++;
                if(kind.equals("skipped"))skipped++;
            }
            default -> {lost=true;return;}
        }
        changed.run();
    }

    synchronized Progress progress() {
        return new Progress(runId,!streams.isEmpty(),junitOnly && !streams.isEmpty() && streams.values().stream().allMatch(Boolean::booleanValue) && !lost,
                tests.size(),started,new TestCounts(passed,failed,skipped),lost,containerFailures,ended.size()==streams.size());
    }
    record Progress(String runId,boolean available,boolean totalKnown,int discovered,int started,TestCounts counts,
                    boolean lost,int containerFailures,boolean streamsEnded) {}
    private synchronized void markLost(){lost=true;}

    void drain() throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(1).toNanos();
        while(!sockets.isEmpty() && System.nanoTime()<deadline)Thread.sleep(10);
        if(!sockets.isEmpty())markLost();
    }

    @Override public void close() {
        closed=true;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}
        sockets.clear();
    }

    static Path listenerJar(Path directory) throws Exception {
        Files.createDirectories(directory);
        List<byte[]> classes=new ArrayList<>();
        List<String> names=List.of("io/fluxzero/devserver/listener/ConsoleTestListener.class","io/fluxzero/devserver/listener/TestEventClient.class");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        for(String name:names)try(var input=TestTelemetry.class.getResourceAsStream("/"+name)) {
            if(input==null)throw new java.io.IOException("Missing test listener");
            byte[] bytes=input.readAllBytes();classes.add(bytes);digest.update(bytes);
        }
        Path jar=directory.resolve("test-listener-"+java.util.HexFormat.of().formatHex(digest.digest()).substring(0,16)+".jar");
        if(Files.isRegularFile(jar))return jar.toAbsolutePath();
        try(var output=new JarOutputStream(Files.newOutputStream(jar))) {
            for(int i=0;i<names.size();i++){output.putNextEntry(new JarEntry(names.get(i)));output.write(classes.get(i));output.closeEntry();}
            output.putNextEntry(new JarEntry("META-INF/services/org.junit.platform.launcher.TestExecutionListener"));
            output.write("io.fluxzero.devserver.listener.ConsoleTestListener\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();
        }
        return jar.toAbsolutePath();
    }
    private static String digest(byte[] value) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
