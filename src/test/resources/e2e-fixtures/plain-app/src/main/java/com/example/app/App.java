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

package com.example.app;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.handling.authentication.OidcUserProvider;

import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        String environment = Objects.requireNonNullElse(System.getenv("ENVIRONMENT"), System.getProperty("ENVIRONMENT"));
        if (!"local".equals(environment)) {
            throw new IllegalStateException("Expected ENVIRONMENT=local but got " + environment);
        }
        Properties devProperties = loadDevProperties(environment);
        if (!"enabled".equals(devProperties.getProperty("fixture.dev-mode"))) {
            throw new IllegalStateException("application-local.properties was not loaded");
        }
        if (AppVersion.FAIL_ON_START) {
            throw new IllegalStateException("fixture startup failure for " + AppVersion.VALUE);
        }
        if (!AppVersion.CONNECT_TO_RUNTIME) {
            System.out.println("plain fixture app deliberately staying disconnected " + AppVersion.VALUE);
            new CountDownLatch(1).await();
        }

        Fluxzero fluxzero = DefaultFluxzero.builder()
                .registerUserProvider(OidcUserProvider.fromProperties())
                .disableTrackingMetrics()
                .disableCacheEvictionMetrics()
                .build(WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder().build()));
        fluxzero.registerHandlers(new GreetingHandlers(devProperties.getProperty("fixture.dev-mode")),
                                  new AuthenticatedWebHandlers());
        System.out.println("plain fixture app ready " + AppVersion.VALUE);
    }

    private static Properties loadDevProperties(String environment) throws Exception {
        Properties properties = new Properties();
        String resource = "/application-" + environment + ".properties";
        try (InputStream input = App.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            properties.load(input);
        }
        return properties;
    }
}
