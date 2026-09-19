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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real runner qualification, enabled by the existing dev-server-e2e profile. */
@EnabledIfSystemProperty(named = "fluxzero.devserver.e2e", matches = "true")
class TestTelemetryE2EIT {
    @Test void mavenReportsForkedParameterizedAndDynamicTestsWhileTheyRun(@TempDir Path directory) throws Exception {
        run(directory,BuildTool.MAVEN,false);
    }
    @Test void gradleReportsTheSameInvocationsWithoutTestClasspathChanges(@TempDir Path directory) throws Exception {
        run(directory,BuildTool.GRADLE,false);
    }
    @Test void interruptedMavenRunKeepsOnlyReportedCompletions(@TempDir Path directory) throws Exception {
        run(directory,BuildTool.MAVEN,true);
    }
    private void run(Path directory,BuildTool tool,boolean interrupt) throws Exception {
        Path source=Path.of("src/test/resources/e2e-fixtures/test-events");
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path target=directory.resolve(source.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
            }
        }
        boolean windows=System.getProperty("os.name").toLowerCase().contains("win");
        String executable=tool==BuildTool.MAVEN?Path.of(windows?"mvnw.cmd":"mvnw").toAbsolutePath().toString()
                :System.getProperty("fluxzero.dev.gradleExecutable",windows?"gradle.bat":"gradle");
        List<String> command=new ArrayList<>(List.of(executable,tool==BuildTool.MAVEN?"--batch-mode":"--console=plain","test"));
        if(tool==BuildTool.GRADLE)command.add("--no-daemon");
        var process=new AtomicReference<Process>();
        var ref=new AtomicReference<TestTelemetry>();
        var intermediate=new AtomicBoolean();
        var inventory = new TestInventory(directory.resolve(".inventory"));
        try(var telemetry=new TestTelemetry(()->{
            var current=ref.get();var child=process.get();
            if(current!=null && child!=null && child.isAlive()) {
                int completed=current.progress().counts().total();
                if(completed>0 && completed<9 && intermediate.compareAndSet(false,true) && interrupt) {
                    Thread.ofVirtual().start(()->ProcessUtils.stopTree(child,java.time.Duration.ofSeconds(2)));
                }
            }
        }, inventory)) {
            ref.set(telemetry);
            var environment=new HashMap<String,String>();
            telemetry.configure(command,environment,tool,directory.resolve(".fluxzero/dev"));
            var result=ProcessUtils.run(command,directory,environment,ignored->{},process::set);
            telemetry.drain();
            assertNotEquals(0,result.exitCode(),"The fixture deliberately contains one failing test");
            var progress=telemetry.progress();
            if(interrupt) {
                assertTrue(intermediate.get());assertTrue(progress.counts().total()>0);
                assertTrue(progress.counts().total()<9,"Cancellation must not invent completions");
                assertFalse(progress.streamsEnded());assertTrue(progress.lost());
                return;
            }
            assertEquals(new TestCounts(7,1,1),progress.counts(),result.tail(60));
            assertEquals(9,progress.discovered());assertTrue(progress.streamsEnded());
            inventory.finish(true, !progress.lost(), telemetry.observedTests());
            assertEquals(9, inventory.snapshot().total());
            assertEquals(new TestCounts(7, 1, 1), inventory.snapshot().counts());
            assertTrue(inventory.snapshot().known());
            assertFalse(progress.lost());assertTrue(intermediate.get(),"Counts must arrive before the test process completes");
            assertTrue(result.output().stream().anyMatch(line->line.contains("live test output")));
        }
    }
}
