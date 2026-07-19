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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class TestPipelineTest {

    @Test
    void mavenLifecycleUsesCurrentUpstreamReactorClasses(@TempDir Path projectDirectory) throws Exception {
        Path repository = projectDirectory.resolve("repository");
        installRealMavenWrapper(projectDirectory, repository);
        installReactorFixture(projectDirectory);

        ProcessUtils.ProcessResult installed = ProcessUtils.run(
                List.of(projectDirectory.resolve("mvnw").toString(), "--batch-mode", "--no-transfer-progress",
                        "-f", "model/pom.xml", "install", "-DskipTests"),
                projectDirectory, Map.of(), ignored -> {
                });
        assertTrue(installed.success(), String.join(System.lineSeparator(), installed.output()));

        Path model = projectDirectory.resolve("model/src/main/java/com/acme/ModelVersion.java");
        Files.writeString(model, """
                package com.acme;
                public final class ModelVersion {
                    public static String value() { return "current-reactor"; }
                }
                """);
        Files.setLastModifiedTime(model, FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();
        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(model));
            assertTrue(awaitStatus(statuses, "passed", List.of()));
        }

        String command = Files.readString(projectDirectory.resolve("runs.log"));
        assertTrue(command.contains(" test "), command);
    }

    @Test
    void initialTestsRunOnceAndOnlyOfflineChangesRunAfterRestart(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.writeString(projectDirectory.resolve("pom.xml"), "v1");
        Path test = projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "v1");
        DevSessionStore store = new DevSessionStore(projectDirectory);

        List<TestStatus> firstStatuses = new CopyOnWriteArrayList<>();
        try (TestPipeline pipeline = new TestPipeline(
                config(projectDirectory), store, firstStatuses::add, ignored -> {
        })) {
            pipeline.requestInitial();
            assertTrue(awaitStatus(firstStatuses, "passed", List.of()));
            assertTrue(awaitBaseline(store, projectDirectory));
        }
        assertEquals("initial test baseline", store.readTestStatus().orElseThrow().reason());
        assertEquals("1", Files.readString(projectDirectory.resolve("run-count.txt")).strip());

        List<String> restartOutput = new CopyOnWriteArrayList<>();
        try (TestPipeline pipeline = new TestPipeline(
                config(projectDirectory), store, ignored -> {
        }, restartOutput::add)) {
            pipeline.requestInitial();
            assertTrue(await(() -> restartOutput.stream().anyMatch(
                    line -> line.contains("skipped initial tests because test inputs are unchanged"))));
        }
        assertEquals("1", Files.readString(projectDirectory.resolve("run-count.txt")).strip());

        Files.writeString(test, "v2");
        List<TestStatus> changedStatuses = new CopyOnWriteArrayList<>();
        try (TestPipeline pipeline = new TestPipeline(
                config(projectDirectory), store, changedStatuses::add, ignored -> {
        })) {
            pipeline.requestInitial();
            assertTrue(awaitStatus(changedStatuses, "passed", List.of("com.acme.OrderHandlerTest")));
            assertTrue(awaitBaseline(store, projectDirectory));
        }
        assertEquals("2", Files.readString(projectDirectory.resolve("run-count.txt")).strip());
        assertEquals("changed test class", store.readTestStatus().orElseThrow().reason());

        Files.writeString(projectDirectory.resolve("pom.xml"), "v2");
        List<TestStatus> buildStatuses = new CopyOnWriteArrayList<>();
        try (TestPipeline pipeline = new TestPipeline(
                config(projectDirectory), store, buildStatuses::add, ignored -> {
        })) {
            pipeline.requestInitial();
            assertTrue(awaitStatus(buildStatuses, "passed", List.of()));
            assertTrue(awaitBaseline(store, projectDirectory));
        }
        assertEquals("3", Files.readString(projectDirectory.resolve("run-count.txt")).strip());
        assertEquals("build/resource change fallback", store.readTestStatus().orElseThrow().reason());
    }

    @Test
    void coalescesRequestsWithoutBlockingActiveRun(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("wait"));
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")));
            assertTrue(awaitFile(projectDirectory.resolve("started-1")));

            long start = System.nanoTime();
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/PaymentHandlerTest.java")));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsedMillis < 250, "request should not wait for the active test process");

            Files.createFile(projectDirectory.resolve("release"));

            assertTrue(awaitRunCount(projectDirectory, 2));
            assertTrue(awaitStatus(statuses, "passed", List.of("com.acme.PaymentHandlerTest")));
        }

        String log = Files.readString(projectDirectory.resolve("runs.log"));
        assertTrue(log.lines().allMatch(line -> line.contains(" test ")), log);
        assertTrue(log.lines().allMatch(line -> line.contains("-DfailIfNoTests=false")), log);
        assertTrue(log.lines().allMatch(line -> line.contains("-Dsurefire.failIfNoSpecifiedTests=false")), log);
        assertTrue(log.contains("-Dtest=com.acme.OrderHandlerTest"), log);
        assertTrue(log.contains("-Dtest=com.acme.PaymentHandlerTest"), log);
    }

    @Test
    void retriesPreviouslyFailingTestsOnNextRequest(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("fail"));
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();
        List<String> output = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, output::add)) {
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")));
            assertTrue(awaitStatus(statuses, "failed", List.of("com.acme.OrderHandlerTest")));

            Files.delete(projectDirectory.resolve("fail"));
            pipeline.request(Set.of(projectDirectory.resolve("docs/readme.md")));

            assertTrue(awaitStatus(statuses, "passed", List.of("com.acme.OrderHandlerTest")));
        }

        assertEquals("2", Files.readString(projectDirectory.resolve("run-count.txt")).strip());
        assertTrue(output.stream().anyMatch(line -> line.contains(
                           "[test] selected com.acme.OrderHandlerTest because test source changed:")),
                   String.join(System.lineSeparator(), output));
        assertTrue(output.stream().anyMatch(line -> line.contains(
                           "[test] failed com.acme.OrderHandlerTest in ") && line.contains("(exit code 7)")),
                   String.join(System.lineSeparator(), output));
        assertTrue(output.stream().anyMatch(line -> line.contains(
                           "[test] selected com.acme.OrderHandlerTest because previously failed")),
                   String.join(System.lineSeparator(), output));
        assertTrue(output.stream().anyMatch(line -> line.contains("[test] passed com.acme.OrderHandlerTest in ")),
                   String.join(System.lineSeparator(), output));
        TestStatus storedStatus = store.readTestStatus().orElseThrow();
        assertEquals("previously failed", storedStatus.selectionReasons().get("com.acme.OrderHandlerTest"));
        assertTrue(storedStatus.durationMillis() >= 0);
    }

    @Test
    void coalescedFollowUpKeepsPreviouslyFailingSelectors(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("wait"));
        Files.createFile(projectDirectory.resolve("fail-first"));
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")));
            assertTrue(awaitFile(projectDirectory.resolve("started-1")));

            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/PaymentHandlerTest.java")));
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/InvoiceHandlerTest.java")));
            Files.createFile(projectDirectory.resolve("release"));

            assertTrue(awaitStatus(statuses, "failed", List.of("com.acme.OrderHandlerTest")));
            assertTrue(awaitStatus(statuses, "passed",
                                   List.of("com.acme.InvoiceHandlerTest", "com.acme.OrderHandlerTest",
                                           "com.acme.PaymentHandlerTest")));
        }

        assertEquals("2", Files.readString(projectDirectory.resolve("run-count.txt")).strip());
        String log = Files.readString(projectDirectory.resolve("runs.log"));
        assertTrue(log.contains("-Dtest=com.acme.OrderHandlerTest"), log);
        assertTrue(log.contains("-Dtest=com.acme.InvoiceHandlerTest,com.acme.OrderHandlerTest,com.acme.PaymentHandlerTest"), log);
    }

    @Test
    void failedRunsKeepUsefulDiagnosticTail(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("long-fail"));
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")));

            assertTrue(awaitStatus(statuses, "failed", List.of("com.acme.OrderHandlerTest")));
        }

        TestStatus status = store.readTestStatus().orElseThrow();
        assertTrue(status.detail().contains("useful failure marker"), status.detail());
    }

    @Test
    void appCompilePreemptsAndRequeuesActiveBackgroundTest(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("wait"));
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (MavenBuildCoordinator coordinator = new MavenBuildCoordinator();
             TestPipeline pipeline = new TestPipeline(
                     config(projectDirectory), store, coordinator, statuses::add, ignored -> {
             })) {
            pipeline.request(Set.of(projectDirectory.resolve("src/test/java/com/acme/OrderHandlerTest.java")));
            assertTrue(awaitFile(projectDirectory.resolve("started-1")));

            coordinator.withCompileLock(() -> {
                Files.createFile(projectDirectory.resolve("release"));
                return null;
            });

            assertTrue(awaitStatus(statuses, "queued", List.of("com.acme.OrderHandlerTest")));
            assertTrue(awaitStatus(statuses, "passed", List.of("com.acme.OrderHandlerTest")));
        }

        assertEquals("2", Files.readString(projectDirectory.resolve("run-count.txt")).strip());
        assertTrue(statuses.stream().noneMatch(status -> "failed".equals(status.state())));
    }

    @Test
    void moduleFailureUsesSurefireReportForPreviouslyFailingSelector(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        Files.createFile(projectDirectory.resolve("module-fail-first"));
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(projectDirectory.resolve("src/main/java/com/acme/OrderHandler.java")));
            assertTrue(awaitStatus(statuses, "failed", List.of("com.acme.OrderHandlerTest#fails")));

            pipeline.request(Set.of(projectDirectory.resolve("docs/readme.md")));

            assertTrue(awaitStatus(statuses, "passed", List.of("com.acme.OrderHandlerTest#fails")));
        }

        String log = Files.readString(projectDirectory.resolve("runs.log"));
        assertTrue(log.lines().allMatch(line -> line.contains(" test ")), log);
        assertTrue(log.contains("-Dtest=com.acme.OrderHandlerTest#fails"), log);
    }

    @Test
    void usesMavenTestLifecycleForChangedKotlinTest(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(projectDirectory.resolve("src/test/kotlin/com/acme/OrderHandlerTest.kt")));

            assertTrue(awaitStatus(statuses, "passed", List.of("com.acme.OrderHandlerTest")));
        }

        String log = Files.readString(projectDirectory.resolve("runs.log"));
        assertTrue(log.contains(" test "), log);
    }

    @Test
    void compilesChangedMainCodeBeforeCompilingItsNewTest(@TempDir Path projectDirectory) throws Exception {
        installFakeMaven(projectDirectory);
        DevSessionStore store = new DevSessionStore(projectDirectory);
        List<TestStatus> statuses = new CopyOnWriteArrayList<>();

        try (TestPipeline pipeline = new TestPipeline(config(projectDirectory), store, statuses::add, ignored -> {
        })) {
            pipeline.request(Set.of(
                    projectDirectory.resolve("src/main/java/com/acme/NewHandler.java"),
                    projectDirectory.resolve("src/test/java/com/acme/NewHandlerTest.java")));

            assertTrue(awaitStatus(statuses, "passed", List.of("com.acme.NewHandlerTest")));
        }

        String log = Files.readString(projectDirectory.resolve("runs.log"));
        assertTrue(log.contains(" test "), log);
    }

    private static DevServerConfig config(Path projectDirectory) {
        return new DevServerConfig(
                projectDirectory, null, "dev-test-app", null,
                false, false, true,
                DevServerConfig.DEFAULT_STARTUP_TIMEOUT,
                DevServerConfig.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT,
                DevServerConfig.DEFAULT_DEBOUNCE,
                FrontendConfig.none(), null);
    }

    private static void installFakeMaven(Path projectDirectory) throws Exception {
        Path mvnw = projectDirectory.resolve("mvnw");
        Files.writeString(mvnw, """
                #!/bin/sh
                set -eu
                count_file="$PWD/run-count.txt"
                count=0
                if [ -f "$count_file" ]; then
                  count="$(cat "$count_file")"
                fi
                count=$((count + 1))
                echo "$count" > "$count_file"
                echo "$count $*" >> "$PWD/runs.log"
                touch "$PWD/started-$count"
                if [ -f "$PWD/wait" ]; then
                  while [ ! -f "$PWD/release" ]; do
                    sleep 0.05
                  done
                fi
                if [ -f "$PWD/fail-first" ] && [ "$count" = "1" ]; then
                  echo "simulated first failing test run"
                  exit 7
                fi
                if [ -f "$PWD/long-fail" ]; then
                  echo "useful failure marker"
                  i=0
                  while [ "$i" -lt 40 ]; do
                    echo "later diagnostic line $i"
                    i=$((i + 1))
                  done
                  exit 7
                fi
                if [ -f "$PWD/module-fail-first" ] && [ "$count" = "1" ]; then
                  mkdir -p "$PWD/target/surefire-reports"
                  printf '%s\n' \
                    '<testsuite name="com.acme.OrderHandlerTest" tests="1" failures="1">' \
                    '  <testcase classname="com.acme.OrderHandlerTest" name="fails">' \
                    '    <failure message="expected failure"/>' \
                    '  </testcase>' \
                    '</testsuite>' > "$PWD/target/surefire-reports/TEST-com.acme.OrderHandlerTest.xml"
                  exit 7
                fi
                if [ -f "$PWD/fail" ]; then
                  echo "simulated failing test"
                  exit 7
                fi
                exit 0
                """);
        assertTrue(mvnw.toFile().setExecutable(true));
    }

    private static void installRealMavenWrapper(Path projectDirectory, Path repository) throws Exception {
        Path executable = findRepositoryMavenWrapper();
        Path wrapper = projectDirectory.resolve("mvnw");
        Files.writeString(wrapper, """
                #!/bin/sh
                echo "1 $*" >> "$PWD/runs.log"
                exec "%s" -Dmaven.repo.local="%s" "$@"
                """.formatted(executable, repository));
        assertTrue(wrapper.toFile().setExecutable(true));
    }

    private static Path findRepositoryMavenWrapper() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("mvnw");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not find repository Maven wrapper");
    }

    private static void installReactorFixture(Path projectDirectory) throws Exception {
        String cachedRepository = Path.of(System.getProperty("user.home"), ".m2", "repository").toUri().toString();
        Files.writeString(projectDirectory.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>reactor</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <modules><module>model</module><module>consumer</module></modules>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <repositories><repository><id>local-cache</id><url>%s</url></repository></repositories>
                  <pluginRepositories><pluginRepository><id>local-plugin-cache</id><url>%s</url></pluginRepository></pluginRepositories>
                  <build><pluginManagement><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.6</version>
                  </plugin></plugins></pluginManagement></build>
                </project>
                """.formatted(cachedRepository, cachedRepository));
        Path model = projectDirectory.resolve("model");
        Files.createDirectories(model.resolve("src/main/java/com/acme"));
        Files.writeString(model.resolve("pom.xml"), modulePom("model", ""));
        Files.writeString(model.resolve("src/main/java/com/acme/ModelVersion.java"), """
                package com.acme;
                public final class ModelVersion {
                    public static String value() { return "installed-old"; }
                }
                """);

        Path consumer = projectDirectory.resolve("consumer");
        Files.createDirectories(consumer.resolve("src/test/java/com/acme"));
        Files.writeString(consumer.resolve("pom.xml"), modulePom("consumer", """
                <dependency><groupId>com.acme</groupId><artifactId>model</artifactId><version>1</version></dependency>
                <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                  <version>5.14.2</version><scope>test</scope></dependency>
                """));
        Files.writeString(consumer.resolve("src/test/java/com/acme/ReactorVersionTest.java"), """
                package com.acme;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ReactorVersionTest {
                    @Test void usesCurrentReactorClass() {
                        assertEquals("current-reactor", ModelVersion.value());
                    }
                }
                """);
    }

    private static String modulePom(String artifactId, String dependencies) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>reactor</artifactId><version>1</version></parent>
                  <artifactId>%s</artifactId>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(artifactId, dependencies);
    }

    private static boolean awaitRunCount(Path projectDirectory, int expected) throws Exception {
        return await(() -> {
            Path file = projectDirectory.resolve("run-count.txt");
            return Files.isRegularFile(file) && Integer.parseInt(Files.readString(file).strip()) >= expected;
        });
    }

    private static boolean awaitFile(Path file) throws Exception {
        return await(() -> Files.isRegularFile(file));
    }

    private static boolean awaitBaseline(DevSessionStore store, Path projectDirectory) throws Exception {
        TestInputSnapshot current = TestInputSnapshot.capture(projectDirectory);
        return await(() -> store.readTestInputs().map(current::equals).orElse(false));
    }

    private static boolean awaitStatus(List<TestStatus> statuses, String state, List<String> selectors)
            throws Exception {
        return await(() -> statuses.stream().anyMatch(status -> state.equals(status.state())
                                                               && status.selectors().equals(selectors)));
    }

    private static boolean await(CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
