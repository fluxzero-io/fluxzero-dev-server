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

import io.fluxzero.sdk.tracking.handling.authentication.OidcUser;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.WebResponse;

import java.util.Map;

public class AuthenticatedWebHandlers {

    @HandleGet("/secure/me")
    @RequiresUser
    public WebResponse me(User user) {
        OidcUser oidcUser = (OidcUser) user;
        return WebResponse.ok(Map.of(
                "subject", oidcUser.subject(),
                "email", oidcUser.email(),
                "tenantId", oidcUser.tenantId(),
                "authenticated", user.hasRole("authenticated"),
                "version", AppVersion.VALUE),
                              Map.of("Content-Type", "application/json"));
    }
}
