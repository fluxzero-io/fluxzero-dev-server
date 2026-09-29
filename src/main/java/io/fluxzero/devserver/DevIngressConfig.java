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

/** Public service selection and the internal gateway endpoint advertised to managed commands. */
public record DevIngressConfig(String service, String host, String bindAddress) {
    public DevIngressConfig {
        if (service == null || service.isBlank()) throw new IllegalArgumentException("publicIngress must name a service");
        host = host == null ? "localhost" : host;
        bindAddress = bindAddress == null ? "127.0.0.1" : bindAddress;
        if (!host.matches("[A-Za-z0-9._-]+") || host.equals("0.0.0.0"))
            throw new IllegalArgumentException("gateway.host must be a reachable hostname or IPv4 address");
        if (bindAddress.isBlank()) throw new IllegalArgumentException("gateway.bindAddress must not be blank");
    }
}
