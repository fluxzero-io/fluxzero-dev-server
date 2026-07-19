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

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainClassDetectorTest {

    @Test
    void detectsSingleMainClassWithoutLoadingIt(@TempDir Path directory) throws Exception {
        Path classes = compile(directory, "com.acme.App", """
                package com.acme;
                public class App {
                    static { if (System.nanoTime() >= 0) throw new Error("must not initialize"); }
                    public static void main(String[] args) { }
                }
                """);

        assertEquals("com.acme.App", MainClassDetector.detect(classes));
    }

    @Test
    void rejectsMissingMainClass(@TempDir Path directory) throws Exception {
        Path classes = compile(directory, "com.acme.Handler", """
                package com.acme;
                public class Handler { }
                """);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> MainClassDetector.detect(classes));

        assertTrue(exception.getMessage().contains("No application main class detected"));
    }

    @Test
    void listsAmbiguousMainClasses(@TempDir Path directory) throws Exception {
        Path classes = compile(directory, "com.acme.FirstApp", """
                package com.acme;
                public class FirstApp { public static void main(String[] args) { } }
                """);
        compile(directory, "com.acme.SecondApp", """
                package com.acme;
                public class SecondApp { public static void main(String[] args) { } }
                """);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> MainClassDetector.detect(classes));

        assertTrue(exception.getMessage().contains("com.acme.FirstApp"));
        assertTrue(exception.getMessage().contains("com.acme.SecondApp"));
    }

    @Test
    void onlyAllowsPackagePrivateMainForExplicitTestApplicationDetection(@TempDir Path directory) throws Exception {
        Path classes = compile(directory, "com.acme.Rebound", """
                package com.acme;
                public class Rebound { static void main(String[] args) { } }
                """);

        assertEquals(java.util.List.of(), MainClassDetector.candidates(classes));
        assertEquals(java.util.List.of("com.acme.Rebound"), MainClassDetector.testCandidates(classes));
    }

    private static Path compile(Path directory, String className, String source) throws Exception {
        Path sourceFile = directory.resolve("src").resolve(className.replace('.', '/') + ".java");
        Path classes = directory.resolve("classes");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source);
        int result = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, result);
        return classes;
    }
}
