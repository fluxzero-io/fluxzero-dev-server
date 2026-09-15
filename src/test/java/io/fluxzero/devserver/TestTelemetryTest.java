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
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.UUID;
import java.net.Socket;
import java.io.DataOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class TestTelemetryTest {
    @Test void aggregatesIndependentForksDynamicTestsAndDuplicatesWithoutInventingCompletions() {
        try(var events=new TestTelemetry(()->{})) {
            events.record("fork-a","plan","");events.record("fork-a","registered","case");
            events.record("fork-a","discovered","");events.record("fork-a","started","case");
            events.record("fork-b","plan","");events.record("fork-b","registered","case");
            events.record("fork-b","discovered","");events.record("fork-b","failed","case");
            events.record("fork-b","failed","case");
            events.record("fork-a","registered","dynamic");events.record("fork-a","skipped","dynamic");
            var live=events.progress();
            assertEquals(3,live.discovered());assertEquals(new TestCounts(0,1,1),live.counts());
            assertTrue(live.totalKnown());assertFalse(live.streamsEnded());
            events.record("fork-a","passed","case");events.record("fork-a","started","case");
            events.record("fork-a","end","");events.record("fork-b","end","");
            assertEquals(new TestCounts(1,1,1),events.progress().counts());assertTrue(events.progress().streamsEnded());
            events.close();events.record("fork-a","passed","late");
            assertEquals(3,events.progress().discovered());
        }
    }

    @Test void acceptsOnlyThisRunsTokenAndKeepsAnIncompleteStreamPartial(@TempDir Path directory) throws Exception {
        var notified=new CompletableFuture<Void>();
        try(var events=new TestTelemetry(()->notified.complete(null))) {
            var env=new HashMap<String,String>();events.configure(new java.util.ArrayList<>(),env,BuildTool.MAVEN,directory);
            int port=Integer.parseInt(env.get("FLUXZERO_DEV_TEST_PORT"));
            try(var bad=new Socket("127.0.0.1",port);var out=new DataOutputStream(bad.getOutputStream())) {
                out.writeUTF("fz-test-v1");out.writeUTF("wrong run token");out.flush();
            }
            try(var socket=new Socket("127.0.0.1",port);var out=new DataOutputStream(socket.getOutputStream())) {
                out.writeUTF("fz-test-v1");out.writeUTF(env.get("FLUXZERO_DEV_TEST_TOKEN"));out.writeUTF(UUID.randomUUID().toString());out.writeUTF("junit");out.writeUTF("module");
                out.writeUTF("plan");out.writeUTF("");out.writeUTF("registered");out.writeUTF("pending");out.writeUTF("started");out.writeUTF("pending");out.flush();
            }
            notified.get(2,TimeUnit.SECONDS);events.drain();
            assertTrue(events.progress().available());assertEquals(1,events.progress().discovered());
            assertEquals(0,events.progress().counts().total());assertTrue(events.progress().lost());
            assertFalse(events.progress().streamsEnded());
        }
    }

    @Test void listenerJarHasOnlyItsPublicSpiAndJava8Bytecode(@TempDir Path directory) throws Exception {
        Path path=TestTelemetry.listenerJar(directory);
        assertEquals(path,TestTelemetry.listenerJar(directory));
        try(var jar=new JarFile(path.toFile())) {
            assertEquals(3,jar.size());
            var bytes=jar.getInputStream(jar.getJarEntry("io/fluxzero/devserver/listener/ConsoleTestListener.class")).readAllBytes();
            assertEquals(52,java.nio.ByteBuffer.wrap(bytes).getShort(6));
            assertTrue(new String(jar.getInputStream(jar.getJarEntry("META-INF/services/org.junit.platform.launcher.TestExecutionListener")).readAllBytes()).contains("ConsoleTestListener"));
        }
    }

    @Test void testOutputKeepsABoundedTailAndRemovesTerminalEscapes() {
        TestOutput output=new TestOutput();
        for(int i=0;i<300;i++)output.add("app","line "+i);
        assertEquals(200,output.snapshot().size());assertEquals(101,output.snapshot().getFirst().sequence());
        output.add("app","\u001b[31mred\u001b[0m");assertEquals("red",output.snapshot().getLast().text());
        output.add("app","x".repeat(3000));assertEquals(2001,output.snapshot().getLast().text().length());
        long sequence=output.snapshot().getLast().sequence();output.clear();assertTrue(output.snapshot().isEmpty());
        output.add("app","after clear");assertEquals(sequence+1,output.snapshot().getFirst().sequence());
    }
}
