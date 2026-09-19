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

package io.fluxzero.devserver.listener;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/** Loaded by JUnit Platform's public ServiceLoader hook in the isolated test classpath. */
public final class ConsoleTestListener implements TestExecutionListener {
    private volatile TestEventClient client;
    private volatile TestPlan plan;
    @Override public void testPlanExecutionStarted(TestPlan plan) {
        this.plan = plan;
        client = new TestEventClient("junit", System.getProperty("basedir", System.getProperty("user.dir", "")));
        discoverProject(plan);
        client.event("plan", "");
        for(TestIdentifier root:plan.getRoots()) for(TestIdentifier test:plan.getDescendants(root))
            if(test.isTest()) send("registered",test);
        client.event("discovered", "");
    }
    private void discoverProject(TestPlan selected) {
        try {
            Set<Path> roots = new LinkedHashSet<Path>();
            for (TestIdentifier root : selected.getRoots()) for (TestIdentifier test : selected.getDescendants(root)) {
                if (test.getSource().isPresent() && test.getSource().get() instanceof ClassSource) {
                    Class<?> type = ((ClassSource) test.getSource().get()).getJavaClass();
                    roots.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
                }
            }
            if (roots.isEmpty()) return;
            TestPlan inventory = LauncherFactory.create().discover(LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathRoots(roots))
                    .filters(ClassNameFilter.includeClassNamePatterns(".*")).build());
            for (TestIdentifier root : inventory.getRoots()) for (TestIdentifier test : inventory.getDescendants(root)) {
                if (test.isTest()) send("inventory-test", test);
                else if (test.getSource().isPresent() && test.getSource().get() instanceof MethodSource
                         && inventory.getChildren(test).isEmpty()) client.event("inventory-template", test.getUniqueId());
            }
            client.event("inventory-complete", "");
        } catch (Throwable ignored) { /* Runners without discovery support retain the last complete inventory. */ }
    }
    @Override public void dynamicTestRegistered(TestIdentifier test) {if(test.isTest()) send("registered",test);}
    @Override public void executionStarted(TestIdentifier test) {if(test.isTest())send("started",test);}
    @Override public void executionSkipped(TestIdentifier test,String reason) {
        if(test.isTest())send("skipped",test);
        else if(plan!=null) for(TestIdentifier child:plan.getDescendants(test)) if(child.isTest())send("skipped",child);
    }
    @Override public void executionFinished(TestIdentifier test,TestExecutionResult result) {
        if(test.isTest()) send(result.getStatus()==TestExecutionResult.Status.SUCCESSFUL?"passed":
                result.getStatus()==TestExecutionResult.Status.ABORTED?"skipped":"failed",test);
        else if(result.getStatus()==TestExecutionResult.Status.FAILED && client!=null)client.event("container-failed",test.getUniqueId());
    }
    @Override public void testPlanExecutionFinished(TestPlan plan) {
        if(client!=null){client.event("end", "");client.close();client=null;}
    }
    private void send(String kind, TestIdentifier test) {
        TestEventClient current = client;
        if (current == null) return;
        String label = test.getDisplayName();
        if (plan != null && (test.getUniqueId().contains("[test-template-invocation:") || test.getUniqueId().contains("[dynamic-test:"))) {
            java.util.Optional<TestIdentifier> parent = plan.getParent(test);
            if (parent.isPresent()) label = parent.get().getDisplayName() + " · " + label;
        }
        current.event("name", test.getUniqueId() + "\0" + label);
        current.event(kind, test.getUniqueId());
    }
}
