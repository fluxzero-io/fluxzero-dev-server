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

package com.example.scope;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.modeling.ModelTypes;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        RuntimeModel.checkStartup();
        // Reflect every discovered model: a leaked catalog must not silently pass startup.
        models().forEach(Class::getDeclaredMethods);
        Fluxzero fluxzero = DefaultFluxzero.builder().disableTrackingMetrics().disableCacheEvictionMetrics()
                .build(WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder().build()));
        fluxzero.registerHandlers(new App());
    }

    @HandleGet("/api/scope")
    public WebResponse scope() throws Exception {
        String resource;
        try (var input = App.class.getResourceAsStream("/runtime-version.txt")) {
            resource = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return WebResponse.ok(Map.of(
                "version", RuntimeModel.version(), "resource", resource,
                "models", models().stream().map(Class::getSimpleName).sorted().toList(),
                "testLibrary", present("org.junit.jupiter.api.Test"),
                "pid", ProcessHandle.current().pid()), Map.of("Content-Type", "application/json"));
    }

    private static List<Class<?>> models() {
        return ModelTypes.discover();
    }

    private static boolean present(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
