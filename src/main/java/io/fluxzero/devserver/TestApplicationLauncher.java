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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Child-process entry point for explicitly selected test applications, whose main method may be package-private.
 */
public final class TestApplicationLauncher {
    private TestApplicationLauncher() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing test application main class");
        }
        Class<?> applicationClass = Class.forName(args[0]);
        Method main = applicationClass.getDeclaredMethod("main", String[].class);
        if (!Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) {
            throw new IllegalArgumentException(args[0] + " does not declare static void main(String[])");
        }
        if (!main.trySetAccessible()) {
            throw new IllegalAccessException("Could not access main method of " + args[0]);
        }
        try {
            main.invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
