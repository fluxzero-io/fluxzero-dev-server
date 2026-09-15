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
    private final Runnable progressChanged;
    private volatile TestTelemetry telemetry;
    private final TestInventory inventory;
    TestInventory.Snapshot inventory() {return inventory.snapshot();}
    TestTelemetry.Progress liveProgress() {var current=telemetry;return current==null?null:current.progress();}
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Path> pendingChanges = new LinkedHashSet<>();
    private final Set<String> failingSelectors = new LinkedHashSet<>();
    private final Set<String> incompleteSelectors = new LinkedHashSet<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean initialRequested;
    private boolean manualRequested;
    private boolean moduleFailurePending;
    private boolean moduleIncompletePending;

    TestPipeline(DevServerConfig config, DevSessionStore sessionStore, Consumer<TestStatus> statusConsumer,
                 Consumer<String> output) {
        this(config, sessionStore, new MavenBuildCoordinator(), statusConsumer, output);
    }

    TestPipeline(DevServerConfig config, DevSessionStore sessionStore, MavenBuildCoordinator coordinator,
                 Consumer<TestStatus> statusConsumer, Consumer<String> output) {
        this(config,sessionStore,coordinator,statusConsumer,output,()->{});
    }

    TestPipeline(DevServerConfig config, DevSessionStore sessionStore, MavenBuildCoordinator coordinator,
                 Consumer<TestStatus> statusConsumer, Consumer<String> output, Runnable progressChanged) {
        this.progressChanged=progressChanged;
        this.config = config;
        this.sessionStore = sessionStore;
        this.coordinator = coordinator;
        this.statusConsumer = statusConsumer;
        this.output = output;
        this.planner = new TestPlanner(config.projectDirectory());
        this.inventory = new TestInventory(sessionStore.directory());
        restoreIncompleteRun();
    }

    void request(Set<Path> changedFiles) {
        if (!config.testsEnabled()) {
            return;
        }
        synchronized (pendingChanges) {
            pendingChanges.addAll(changedFiles);
            manualRequested = false; // A newer automatic run supersedes a queued manual run.
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

    void requestFullRun() {
        if (!config.testsEnabled()) return;
        synchronized (pendingChanges) { manualRequested = true; }
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
                boolean initial, manual;
                synchronized (pendingChanges) {
                    changes = Set.copyOf(pendingChanges);
                    pendingChanges.clear();
                    initial = initialRequested;
                    initialRequested = false;
                    manual = manualRequested;
                    manualRequested = false;
                }
                if (changes.isEmpty() && !initial && !manual) {
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
                TestPlanner.TestPlan plan = manual ? TestPlanner.TestPlan.module("manual test run") : initial && previous.isEmpty()
                        ? TestPlanner.TestPlan.module(
                                "initial test baseline", "no previously tested project snapshot")
                        : moduleFailurePending
                                ? TestPlanner.TestPlan.module("previous module test failure")
                                : moduleIncompletePending
                                        ? TestPlanner.TestPlan.module("previous module test run did not complete")
                                        : planner.plan(changes, Set.copyOf(failingSelectors),
                                                       Set.copyOf(incompleteSelectors));
                if (!plan.shouldRun() && initial) {
                    output.accept("[test] skipped initial tests because test inputs are unchanged");
                }
                RunOutcome outcome = runPlan(plan, changes, manual);
                if (outcome == RunOutcome.RETRY) {
                    synchronized (pendingChanges) {
                        pendingChanges.addAll(changes);
                        initialRequested |= initial;
                        manualRequested |= manual;
                    }
                } else if (outcome == RunOutcome.FINISHED) {
                    sessionStore.writeTestInputs(inputs);
                }
            }
        } finally {
            running.set(false);
            synchronized (pendingChanges) {
                if ((!pendingChanges.isEmpty() || initialRequested || manualRequested) && running.compareAndSet(false, true)) {
                    executor.submit(this::drain);
                }
            }
        }
    }

    private void restoreIncompleteRun() {
        sessionStore.readTestStatus()
                .filter(status -> "failed".equals(status.state()) || "queued".equals(status.state())
                                  || "running".equals(status.state()) || "incomplete".equals(status.state()))
                .ifPresent(status -> {
                    if (status.selectors().isEmpty()) {
                        if ("failed".equals(status.state())) {
                            moduleFailurePending = true;
                        } else {
                            moduleIncompletePending = true;
                        }
                    } else if ("failed".equals(status.state())) {
                        failingSelectors.addAll(status.selectors());
                    } else {
                        incompleteSelectors.addAll(status.selectors());
                    }
                });
    }

    private enum RunOutcome { FINISHED, RETRY, SUPERSEDED }

    private RunOutcome runPlan(TestPlanner.TestPlan plan, Set<Path> changes, boolean manual) {
        if (!plan.shouldRun()) {
            return RunOutcome.FINISHED;
        }
        List<String> selectors = plan.stableSelectors();
        output.accept("[test] " + runningDescription(plan, selectors));
        plan.selectorReasons().forEach((selector, reason) ->
                output.accept("[test] selected " + selector + " because " + reason));
        inventory.reset(selectors);
        telemetry=new TestTelemetry(progressChanged, inventory);
        TestStatus runningStatus = TestStatus.running(selectors, plan.reason(), plan.selectorReasons());
        statusConsumer.accept(runningStatus);
        sessionStore.writeTestStatus(runningStatus);
        BuildTool buildTool = BuildTool.detect(config.projectDirectory());
        List<String> command = buildTool == BuildTool.MAVEN
                ? mavenTestCommand(plan, selectors)
                : gradleTestCommand(plan, selectors);
        if (manual && buildTool == BuildTool.GRADLE) command.add("--rerun-tasks");
        try {
            Map<String,String> environment=new java.util.HashMap<>(buildTool == BuildTool.MAVEN ? MavenCommand.environment() : GradleCommand.environment());
            telemetry.configure(command,environment,buildTool,sessionStore.directory());
            long reportStartedAt = System.currentTimeMillis();
            long startedNanos = System.nanoTime();
            TestReports.clear(config.projectDirectory(), buildTool);
            MavenBuildCoordinator.TestRun testRun = coordinator.runTest(
                    command, config.projectDirectory(),
                    environment, line -> output.accept("[test] " + line));
            telemetry.drain();
            if (testRun.cancelledByCompile()) {
                TestStatus status = (manual
                        ? TestStatus.incomplete(selectors, plan.reason(), plan.selectorReasons(), -1,
                                                "superseded by an app compile; changed-code tests take precedence", 0)
                        : TestStatus.queued(selectors, plan.reason(), plan.selectorReasons(),
                                            "interrupted for app compile; test run will resume"))
                        .withCounts(telemetry.progress().counts());
                output.accept(manual ? "[test] manual run superseded by app compile"
                                     : "[test] queued after interruption for app compile");
                statusConsumer.accept(status);
                sessionStore.writeTestStatus(status);
                return manual ? RunOutcome.SUPERSEDED : RunOutcome.RETRY;
            }
            ProcessUtils.ProcessResult result = testRun.result();
            TestReports.Result reports = TestReports.read(config.projectDirectory(), buildTool, reportStartedAt);
            if (result.success()) {
                if (plan.runModule()) {
                    failingSelectors.clear();
                    incompleteSelectors.clear();
                    moduleFailurePending = false;
                    moduleIncompletePending = false;
                } else {
                    failingSelectors.removeAll(selectors);
                    incompleteSelectors.removeAll(selectors);
                }
            } else if (reports.failureFound()) {
                if (plan.runModule()) {
                    failingSelectors.addAll(reports.failingSelectors());
                    moduleFailurePending = reports.failingSelectors().isEmpty();
                    moduleIncompletePending = false;
                } else if (!selectors.isEmpty()) {
                    failingSelectors.addAll(selectors);
                    incompleteSelectors.removeAll(selectors);
                }
            } else if (plan.runModule()) {
                moduleIncompletePending = true;
            } else if (!selectors.isEmpty()) {
                incompleteSelectors.addAll(selectors);
            }
            int detailLines = result.success() ? 20 : 80;
            long durationMillis = java.time.Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            List<String> statusSelectors = plan.runModule() && !reports.failingSelectors().isEmpty()
                    ? reports.failingSelectors().stream().sorted().toList() : selectors;
            TestStatus status = result.success() || reports.failureFound()
                    ? TestStatus.completed(statusSelectors, plan.reason(), plan.selectorReasons(),
                                           result.exitCode(), result.tail(detailLines),
                                           reports.firstFailure(), durationMillis)
                    : TestStatus.incomplete(statusSelectors, plan.reason(), plan.selectorReasons(),
                                            result.exitCode(), result.tail(detailLines), durationMillis);
            TestCounts eventCounts=telemetry.progress().counts();
            TestCounts finalCounts=reports.counts();
            if(finalCounts==null || eventCounts.total()>finalCounts.total())finalCounts=eventCounts;
            status = status.withCounts(finalCounts);
            output.accept("[test] " + resultDescription(status, plan, selectors));
            statusConsumer.accept(status);
            sessionStore.writeTestStatus(status);
            return RunOutcome.FINISHED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rememberIncomplete(plan, selectors);
            TestStatus status = TestStatus.incomplete(selectors, plan.reason(), plan.selectorReasons(),
                                                      -1, "test run interrupted", 0).withCounts(telemetry.progress().counts());
            output.accept("[test] incomplete " + selectionLabel(plan, selectors) + ": test run interrupted");
            statusConsumer.accept(status);
            sessionStore.writeTestStatus(status);
            return RunOutcome.FINISHED;
        } catch (Exception e) {
            rememberIncomplete(plan, selectors);
            TestStatus status = TestStatus.incomplete(selectors, plan.reason(), plan.selectorReasons(),
                                                      -1, e.getMessage(), 0).withCounts(telemetry.progress().counts());
            output.accept("[test] incomplete " + selectionLabel(plan, selectors) + ": " + e.getMessage());
            statusConsumer.accept(status);
            sessionStore.writeTestStatus(status);
            return RunOutcome.FINISHED;
        } finally {
            telemetry.close();
            var progress = telemetry.progress();
            inventory.finish(plan.runModule(), progress.available() && !progress.lost() && progress.streamsEnded() && progress.containerFailures() == 0,
                    telemetry.observedTests());
            progressChanged.run();
        }
    }

    private void rememberIncomplete(TestPlanner.TestPlan plan, List<String> selectors) {
        if (plan.runModule()) {
            moduleIncompletePending = true;
        } else {
            incompleteSelectors.addAll(selectors);
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
