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

import io.fluxzero.common.Registration;
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs the local IDP as a managed Fluxzero dev-environment service.
 */
final class ManagedIdpService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedIdpService.class);

    static final String CLIENT_ID = "local-auth-app";
    static final String SCOPE = "openid profile email";
    static final String LOGIN_STATE_SECRET = "local-development-login-state-secret-change-me";
    private static final Semaphore IDP_SLOT = new Semaphore(1);

    private final Fluxzero fluxzero;
    private final Registration handlers;
    private final String issuer;
    private final String clientId;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ManagedIdpService(Fluxzero fluxzero, Registration handlers, String issuer, String clientId) {
        this.fluxzero = fluxzero;
        this.handlers = handlers;
        this.issuer = issuer;
        this.clientId = clientId;
    }

    static ManagedIdpService start(DevServerConfig config, String runtimeBaseUrl, String proxyUrl,
                                   Consumer<String> output) {
        acquireSlot();
        boolean releaseSlot = true;
        PropertySnapshot snapshot = PropertySnapshot.capture(devProperties(proxyUrl));
        try {
            snapshot.apply();
            FluxzeroIdpStub.reset();
            FluxzeroIdpStub stub = new FluxzeroIdpStub();
            WebSocketClient.ClientConfig clientConfig = WebSocketClient.ClientConfig.builder()
                    .runtimeBaseUrl(runtimeBaseUrl)
                    .name(config.applicationName() + "-idp")
                    .namespace(config.namespace())
                    .id(config.applicationName() + "-idp")
                    .build();
            Fluxzero fluxzero = DefaultFluxzero.builder()
                    .disableShutdownHook()
                    .disableKeepalive()
                    .disableTrackingMetrics()
                    .disableCacheEvictionMetrics()
                    .build(WebSocketClient.newInstance(clientConfig));
            Registration handlers = fluxzero.registerHandlers(stub);
            output.accept("[idp] local issuer " + stub.issuer());
            releaseSlot = false;
            return new ManagedIdpService(fluxzero, handlers, stub.issuer(), stub.clientId());
        } finally {
            snapshot.restore();
            if (releaseSlot) {
                FluxzeroIdpStub.reset();
                IDP_SLOT.release();
            }
        }
    }

    private static void acquireSlot() {
        try {
            IDP_SLOT.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the local IDP slot", e);
        }
    }

    static Map<String, String> devProperties(String proxyUrl) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fluxzero.auth.external-base-url", proxyUrl);
        properties.put("fluxzero.auth.oidc.issuer", proxyUrl);
        properties.put("fluxzero.auth.oidc.client-id", CLIENT_ID);
        properties.put("fluxzero.auth.oidc.redirect-uri", proxyUrl + "/app/callback");
        properties.put("fluxzero.auth.oidc.resource-audience", proxyUrl + "/api");
        properties.put("fluxzero.auth.oidc.scope", SCOPE);
        properties.put("fluxzero.auth.oidc.login-state-secret", LOGIN_STATE_SECRET);
        properties.put("fluxzero.auth.oidc.token-endpoint-auth-method", "none");
        return properties;
    }

    String issuer() {
        return issuer;
    }

    String clientId() {
        return clientId;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            handlers.cancel();
        } catch (Exception e) {
            log.debug("Ignored failure while unregistering local IDP handlers", e);
        }
        try {
            fluxzero.close(true);
        } catch (Exception e) {
            log.debug("Ignored failure while closing local IDP Fluxzero client", e);
        } finally {
            FluxzeroIdpStub.reset();
            IDP_SLOT.release();
        }
    }

    private record PropertySnapshot(Map<String, String> desired, Map<String, String> previous) {
        static PropertySnapshot capture(Map<String, String> desired) {
            Map<String, String> previous = new LinkedHashMap<>();
            desired.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
            return new PropertySnapshot(desired, previous);
        }

        void apply() {
            desired.forEach(System::setProperty);
        }

        void restore() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }
}
