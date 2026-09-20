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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Starts a prepared distribution after the old environment has released its ports and lock. */
final class DevServerUpgrade {
    static List<String> command(Path artifact, String[] arguments) {
        var command = new ArrayList<>(DevServerBootstrap.javaCommand(DevServerBootstrapMain.class.getName(), List.of()));
        int classpath = command.indexOf("-cp");
        command.set(classpath + 1, artifact.toString());
        command.add("--bootstrap-background");
        command.add("--bootstrap-agent-ready");
        command.addAll(List.of(arguments));
        return command;
    }

    static boolean start(Path artifact, Path directory, String[] arguments) {
        try {
            DevServerUpdates.run(command(artifact, arguments), directory, Duration.ofMinutes(3));
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.setProperty("fluxzero.dev.updateError", "The update could not start. The previous version was restored.");
            System.err.println("The update could not start. Restoring the previous dev server. "
                    + "See .fluxzero/dev/bootstrap.log for details.");
            return false;
        }
    }
}
