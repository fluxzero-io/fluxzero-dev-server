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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class TestPipeline implements AutoCloseable {
    private final DevServerConfig config;
    private final DevSessionStore sessionStore;
    private final MavenBuildCoordinator coordinator;
    private final Consumer<TestStatus> statusConsumer;
    private final Consumer<String> output;
    private final TestPlanner planner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Path> pendingChanges = new LinkedHashSet<>();
    private final Set<String> failingSelectors = new LinkedHashSet<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean initialRequested;
    private boolean moduleFailurePending;

    TestPipeline(DevServerConfig config, DevSessionStore sessionStore, Consumer<TestStatus> statusConsumer,
                 Consumer<String> output) {
        this(config, sessionStore, new MavenBuildCoordinator(), statusConsumer, output);
    }

    TestPipeline(DevServerConfig config, DevSessionStore sessionStore, MavenBuildCoordinator coordinator,
                 Consumer<TestStatus> statusConsumer, Consumer<String> output) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.coordinator = coordinator;
        this.statusConsumer = statusConsumer;
        this.output = output;
        this.planner = new TestPlanner(config.projectDirectory());
        restoreIncompleteRun();
    }

    void request(Set<Path> changedFiles) {
        if (!config.testsEnabled()) {
            return;
        }
        synchronized (pendingChanges) {
            pendingChanges.addAll(changedFiles);
        }
        schedule();
    }

    void requestInitial() {
        if (!config.testsEnabled()) {
            return;
        }
        synchronized (pendingChanges) {
            initialRequested = true;
        }
        schedule();
    }

    private void schedule() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::drain);
        }
    }

    private void drain() {
        try {
            while (true) {
                Set<Path> changes;
                boolean initial;
                synchronized (pendingChanges) {
                    changes = Set.copyOf(pendingChanges);
                    pendingChanges.clear();
                    initial = initialRequested;
                    initialRequested = false;
                }
                if (changes.isEmpty() && !initial) {
                    return;
                }
                TestInputSnapshot inputs = TestInputSnapshot.capture(config.projectDirectory());
                Optional<TestInputSnapshot> previous = initial
                        ? sessionStore.readTestInputs().filter(TestInputSnapshot::compatible)
                        : Optional.empty();
                if (initial) {
                    if (previous.isEmpty()) {
                        changes = inputs.files().keySet().stream()
                                .map(config.projectDirectory()::resolve)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    } else {
                        Set<Path> initialChanges = new LinkedHashSet<>(changes);
                        initialChanges.addAll(inputs.changesSince(previous.get(), config.projectDirectory()));
                        changes = Set.copyOf(initialChanges);
                    }
                }
                TestPlanner.TestPlan plan = initial && previous.isEmpty()
                        ? TestPlanner.TestPlan.module(
                                "initial test baseline", "no previously tested project snapshot")
                        : moduleFailurePending
                                ? TestPlanner.TestPlan.module("previous module test failure")
                                : planner.plan(changes, Set.copyOf(failingSelectors));
                if (!plan.shouldRun() && initial) {
                    output.accept("[test] skipped initial tests because test inputs are unchanged");
                }
                if (runPlan(plan, changes)) {
                    synchronized (pendingChanges) {
                        pendingChanges.addAll(changes);
                        initialRequested |= initial;
                    }
                } else {
                    sessionStore.writeTestInputs(inputs);
                }
            }
        } finally {
            running.set(false);
            synchronized (pendingChanges) {
                if ((!pendingChanges.isEmpty() || initialRequested) && running.compareAndSet(false, true)) {
                    executor.submit(this::drain);
                }
            }
        }
    }

    private void restoreIncompleteRun() {
        sessionStore.readTestStatus()
                .filter(status -> "failed".equals(status.state()) || "queued".equals(status.state())
                                  || "running".equals(status.state()))
                .ifPresent(status -> {
                    if (status.selectors().isEmpty()) {
                        moduleFailurePending = true;
                    } else {
                        failingSelectors.addAll(status.selectors());
                    }
                });
    }

    private boolean runPlan(TestPlanner.TestPlan plan, Set<Path> changes) {
        if (!plan.shouldRun()) {
            return false;
        }
        List<String> selectors = plan.stableSelectors();
        output.accept("[test] " + runningDescription(plan, selectors));
        plan.selectorReasons().forEach((selector, reason) ->
                output.accept("[test] selected " + selector + " because " + reason));
        TestStatus runningStatus = TestStatus.running(selectors, plan.reason(), plan.selectorReasons());
        statusConsumer.accept(runningStatus);
        sessionStore.writeTestStatus(runningStatus);
        BuildTool buildTool = BuildTool.detect(config.projectDirectory());
        List<String> command = buildTool == BuildTool.MAVEN
                ? mavenTestCommand(plan, selectors)
                : gradleTestCommand(plan, selectors);
        try {
            long reportStartedAt = System.currentTimeMillis();
            long startedNanos = System.nanoTime();
            if (plan.runModule() && buildTool == BuildTool.MAVEN) {
                SurefireFailures.clear(config.projectDirectory());
            }
            MavenBuildCoordinator.TestRun testRun = coordinator.runTest(
                    command, config.projectDirectory(),
                    buildTool == BuildTool.MAVEN ? MavenCommand.environment() : GradleCommand.environment(),
                    line -> output.accept("[test] " + line));
            if (testRun.cancelledByCompile()) {
                TestStatus status = TestStatus.queued(selectors, plan.reason(), plan.selectorReasons(),
                                                      "interrupted for app compile; test run will resume");
                output.accept("[test] queued after interruption for app compile");
                statusConsumer.accept(status);
                sessionStore.writeTestStatus(status);
                return true;
            }
            ProcessUtils.ProcessResult result = testRun.result();
            SurefireFailures.Result moduleFailures = new SurefireFailures.Result(Set.of(), null);
            if (result.success()) {
                if (plan.runModule()) {
                    failingSelectors.clear();
                    moduleFailurePending = false;
                } else {
                    failingSelectors.removeAll(selectors);
                }
            } else if (plan.runModule()) {
                moduleFailures = buildTool == BuildTool.MAVEN
                        ? SurefireFailures.read(config.projectDirectory(), reportStartedAt)
                        : new SurefireFailures.Result(Set.of(), null);
                failingSelectors.addAll(moduleFailures.selectors());
                moduleFailurePending = moduleFailures.selectors().isEmpty();
            } else if (!selectors.isEmpty()) {
                failingSelectors.addAll(selectors);
            }
            int detailLines = result.success() ? 20 : 80;
            long durationMillis = java.time.Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            List<String> statusSelectors = moduleFailures.selectors().isEmpty() ? selectors
                    : moduleFailures.selectors().stream().sorted().toList();
            TestStatus status = TestStatus.completed(statusSelectors, plan.reason(), plan.selectorReasons(),
                                                     result.exitCode(), result.tail(detailLines),
                                                     moduleFailures.firstFailure(), durationMillis);
            output.accept("[test] " + resultDescription(status, plan, selectors));
            statusConsumer.accept(status);
            sessionStore.writeTestStatus(status);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TestStatus status = TestStatus.completed(selectors, plan.reason(), plan.selectorReasons(),
                                                     -1, "test run interrupted", 0);
            output.accept("[test] failed " + selectionLabel(plan, selectors) + ": test run interrupted");
            statusConsumer.accept(status);
            sessionStore.writeTestStatus(status);
            return false;
        } catch (Exception e) {
            TestStatus status = TestStatus.completed(selectors, plan.reason(), plan.selectorReasons(),
                                                     -1, e.getMessage(), 0);
            output.accept("[test] failed " + selectionLabel(plan, selectors) + ": " + e.getMessage());
            statusConsumer.accept(status);
            sessionStore.writeTestStatus(status);
            return false;
        }
    }

    private List<String> mavenTestCommand(TestPlanner.TestPlan plan, List<String> selectors) {
        // Maven lifecycle execution is required for reactor correctness. Invoking compiler:testCompile and
        // surefire:test directly can make downstream modules test stale upstream artifacts from the local repository.
        List<String> command = new ArrayList<>(MavenCommand.command(config.projectDirectory(), "test"));
        command.add("-DfailIfNoTests=false");
        command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        command.add("-Dfluxzero.testImpact.enabled=true");
        command.add("-Dfluxzero.testImpact.directory=" + sessionStore.directory());
        if (!plan.runModule()) {
            command.add("-Dtest=" + String.join(",", selectors));
        }
        return command;
    }

    private List<String> gradleTestCommand(TestPlanner.TestPlan plan, List<String> selectors) {
        List<String> command = new ArrayList<>(GradleCommand.command(config.projectDirectory(), "fluxzeroDevTest"));
        command.add("-Pfluxzero.testImpact.enabled=true");
        command.add("-Pfluxzero.testImpact.directory=" + sessionStore.directory().toAbsolutePath());
        if (!plan.runModule()) {
            command.add("-Pfluxzero.dev.testSelectors=" + String.join(",", selectors));
        }
        return command;
    }

    private static String runningDescription(TestPlanner.TestPlan plan, List<String> selectors) {
        return "running " + selectionLabel(plan, selectors) + " because " + plan.explanation();
    }

    private static String resultDescription(TestStatus status, TestPlanner.TestPlan plan, List<String> selectors) {
        return status.state() + " " + selectionLabel(plan, selectors) + " in "
               + CompileTiming.format(status.durationMillis())
               + (status.exitCode() == 0 ? "" : " (exit code " + status.exitCode() + ")");
    }

    private static String selectionLabel(TestPlanner.TestPlan plan, List<String> selectors) {
        if (plan.runModule()) {
            return "module tests";
        }
        if (selectors.size() == 1) {
            return selectors.getFirst();
        }
        return selectors.size() + " selected tests";
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(750, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
