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

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.configuration.ApplicationProperties;
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.web.WebRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only adapter that exercises the managed IDP userinfo contract without exposing a production SDK API. */
final class FixtureOidcUserProvider extends AbstractUserProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final FixtureOidcUser SYSTEM_USER =
            new FixtureOidcUser("$system", null, null, Set.of("system"));

    private final URI userInfoEndpoint;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, FixtureOidcUser> users = new ConcurrentHashMap<>();

    static FixtureOidcUserProvider fromProperties() {
        String issuer = ApplicationProperties.getProperty("fluxzero.auth.oidc.issuer");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("Missing fluxzero.auth.oidc.issuer");
        }
        return new FixtureOidcUserProvider(URI.create(issuer.replaceAll("/+$", "") + "/userinfo"));
    }

    private FixtureOidcUserProvider(URI userInfoEndpoint) {
        super(FixtureOidcUser.class);
        this.userInfoEndpoint = userInfoEndpoint;
    }

    @Override
    public User getUserById(Object userId) {
        return userId == null ? null : users.get(userId.toString());
    }

    @Override
    public User getSystemUser() {
        return SYSTEM_USER;
    }

    @Override
    public User fromMessage(HasMessage message) {
        User metadataUser = super.fromMessage(message);
        if (metadataUser != null || !(message.toMessage() instanceof WebRequest request)) {
            return metadataUser;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : resolveUser(token);
    }

    private FixtureOidcUser resolveUser(String token) {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(userInfoEndpoint)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Fixture OIDC userinfo failed with status " + response.statusCode());
            }
            JsonNode claims = JsonUtils.writer.readTree(response.body());
            String subject = text(claims, "sub");
            if (subject == null) {
                return null;
            }
            FixtureOidcUser user = new FixtureOidcUser(
                    subject, text(claims, "email"), text(claims, "tenant_id"), Set.of("authenticated"));
            users.put(subject, user);
            return user;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture OIDC userinfo request was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Fixture OIDC userinfo request failed", e);
        }
    }

    private static String text(JsonNode node, String name) {
        String value = node.path(name).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
