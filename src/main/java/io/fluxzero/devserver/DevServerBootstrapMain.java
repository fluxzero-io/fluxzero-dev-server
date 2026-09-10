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

import java.util.ArrayList;
import java.util.Arrays;

/** Version-discovered CLI entrypoint; resolves no artifacts and shares bootstrap with start_dev. */
public final class DevServerBootstrapMain {
    private DevServerBootstrapMain() {}

    public static void main(String[] arguments) throws Exception {
        var args = new ArrayList<>(Arrays.asList(arguments));
        boolean background = args.remove("--bootstrap-background");
        boolean agentReady = args.remove("--bootstrap-agent-ready");
        int result;
        try (var bootstrap = new DevServerBootstrap()) {
            Thread shutdown = new Thread(bootstrap::close, "fluxzero-bootstrap-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                result = bootstrap.run(DevMcpStdioMain.projectDirectory(args.toArray(String[]::new)),
                        args, background, agentReady);
            } catch (IllegalArgumentException | DevServerStartupException e) {
                System.err.println("Fluxzero dev could not start: " + e.getMessage());
                result = 2;
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* Shutdown owns cleanup. */ }
            }
        }
        if (result != 0) System.exit(result);
    }
}
