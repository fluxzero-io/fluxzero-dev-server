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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebResponse;

import java.nio.file.Files;
import java.nio.file.Path;

/** Development application that runs the current dev-server sources without launching another application. */
public final class DevServerPreview {
    private DevServerPreview() {
    }

    public static void main(String[] args) throws Exception {
        Path previews = Path.of("target", "dev-previews").toAbsolutePath();
        Files.createDirectories(previews);
        // Rolling replacement briefly runs both versions: each needs its own session lock and free ports.
        Path workspace = Files.createTempDirectory(previews, "preview-");
        DevServerConfig config = DevServerConfig.fromArgs(new String[]{
                "--project-dir", workspace.toString(), "--application-name", "Dev server preview",
                "--port", "0", "--no-watch", "--no-compile-on-start", "--no-tests", "--no-frontend",
                "--idp", "external", "--idle-timeout", "off"
        });
        // Use the embedded entry point so disposable preview workspaces do not enter global project discovery.
        try (DevServer preview = new DevServer(config)) {
            Thread shutdown = new Thread(preview::close, "dev-preview-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                preview.start();
                String url = preview.session().gateway().url() + "/_fluxzero/dev/#projects";
                Fluxzero fluxzero = DefaultFluxzero.builder().disableTrackingMetrics().disableCacheEvictionMetrics()
                        .build(WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder().build()));
                try {
                    fluxzero.registerHandlers(new PreviewLink(url));
                    System.out.println("Fluxzero dev server preview: " + url);
                    preview.shutdownRequested().join();
                } finally {
                    fluxzero.close(true);
                }
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown already owns cleanup.
                }
            }
        }
    }

    record PreviewLink(String url) {
        @HandleGet("/")
        WebResponse open() {
            return WebResponse.builder().status(302).header("Location", url).build();
        }
    }
}
