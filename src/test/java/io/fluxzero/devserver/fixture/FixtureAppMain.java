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

package io.fluxzero.devserver.fixture;

public final class FixtureAppMain {

    private FixtureAppMain() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("runtime=" + System.getenv("FLUXZERO_BASE_URL"));
        System.out.println("runtime.port=" + System.getenv("FLUX_PORT"));
        System.out.println("runtime.system.port=" + System.getProperty("FLUX_PORT"));
        System.out.println("proxy=" + System.getenv("FLUXZERO_PROXY_URL"));
        System.out.println("proxy.port=" + System.getenv("PROXY_PORT"));
        System.out.println("proxy.system.port=" + System.getProperty("PROXY_PORT"));
        System.out.println("application=" + System.getenv("FLUXZERO_APPLICATION_NAME"));
        System.out.println("namespace=" + System.getenv("FLUXZERO_NAMESPACE"));
        System.out.println("environment=" + System.getenv("ENVIRONMENT"));
        System.out.println("spring.profile=" + System.getProperty("spring.profiles.active"));
        System.out.println("session=" + System.getenv("FLUXZERO_DEV_SESSION_ID"));
        System.out.println("auth.issuer=" + System.getProperty("fluxzero.auth.oidc.issuer"));
        System.out.println("auth.method=" + System.getProperty("fluxzero.auth.oidc.token-endpoint-auth-method"));
        System.out.println("feature.mode=" + System.getenv("FEATURE_MODE"));
        System.out.println("encryption.present=" + (System.getenv("ENCRYPTION_KEY") != null));
        System.out.println("args=" + String.join(" ", args));
        System.err.println("fixture-stderr=available");
        System.out.flush();
        System.err.flush();
        Thread.sleep(DurationHolder.ONE_MINUTE);
    }

    private static final class DurationHolder {
        private static final long ONE_MINUTE = 60_000L;
    }
}
