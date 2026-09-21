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

import com.sun.tools.attach.VirtualMachine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Requests a Java service to run its shutdown hooks before Windows terminates its process tree. */
final class WindowsJavaShutdown {
    private static final String AGENT_CLASS = WindowsJavaShutdownAgent.class.getName();
    private static final String AGENT_RESOURCE = AGENT_CLASS.replace('.', '/') + ".class";
    private static final Object AGENT_LOCK = new Object();
    private static volatile Path agent;

    private WindowsJavaShutdown() {
    }

    static boolean isJava(ProcessHandle process) {
        return process.info().command().map(Path::of).map(Path::getFileName)
                .map(Path::toString)
                .map(name -> name.equalsIgnoreCase("java.exe") || name.equalsIgnoreCase("javaw.exe")
                             || name.equalsIgnoreCase("java"))
                .orElse(false);
    }

    static void request(ProcessHandle process) {
        Path agentJar = agentJar();
        if (agentJar == null || !process.isAlive()) {
            return;
        }
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(process.pid()));
            vm.loadAgent(agentJar.toString());
        } catch (Exception | LinkageError ignored) {
            // Services that disable attach, or non-Java runtimes, use the existing hard-stop fallback.
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException ignored) {
                    // The target may have exited while the agent was being loaded.
                }
            }
        }
    }

    private static Path agentJar() {
        Path cached = agent;
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (AGENT_LOCK) {
            cached = agent;
            if (cached != null && Files.isRegularFile(cached)) {
                return cached;
            }
            try (InputStream bytes = WindowsJavaShutdownAgent.class.getClassLoader()
                    .getResourceAsStream(AGENT_RESOURCE)) {
                if (bytes == null) {
                    return null;
                }
                Manifest manifest = new Manifest();
                Attributes attributes = manifest.getMainAttributes();
                attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
                attributes.putValue("Agent-Class", AGENT_CLASS);
                Path created = Files.createTempFile("fluxzero-java-shutdown-", ".jar");
                try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(created), manifest)) {
                    jar.putNextEntry(new JarEntry(AGENT_RESOURCE));
                    bytes.transferTo(jar);
                    jar.closeEntry();
                }
                created.toFile().deleteOnExit();
                agent = created;
                return created;
            } catch (IOException ignored) {
                return null;
            }
        }
    }
}
