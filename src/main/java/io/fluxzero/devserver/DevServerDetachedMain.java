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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Internal child entrypoint: isolate the process before starting any managed resources. */
public final class DevServerDetachedMain {
    private DevServerDetachedMain() {}

    public static void main(String[] args) throws Exception {
        detach();
        DevServerMain.main(args);
    }

    static void detach() {
        try {
            var linker = Linker.nativeLinker();
            if (ProcessUtils.isWindows()) {
                // Redirected file handles remain valid; console close/control events no longer reach us.
                try (var arena = Arena.ofConfined()) {
                    var kernel = SymbolLookup.libraryLookup("Kernel32.dll", arena);
                    int result = (int) linker.downcallHandle(kernel.findOrThrow("FreeConsole"),
                            FunctionDescriptor.of(JAVA_INT)).invokeExact();
                    if (result == 0) throw new IllegalStateException("FreeConsole failed");
                }
            } else {
                // A fresh ProcessBuilder child is not a group leader. setsid also drops the controlling tty.
                int session = (int) linker.downcallHandle(linker.defaultLookup().findOrThrow("setsid"),
                        FunctionDescriptor.of(JAVA_INT)).invokeExact();
                if (session < 0) throw new IllegalStateException("setsid failed");
            }
        } catch (Throwable e) {
            // Never report startup success when isolation failed, or run services in the caller's group.
            throw new IllegalStateException("Could not detach the Fluxzero dev server from its launching session", e);
        }
    }
}
